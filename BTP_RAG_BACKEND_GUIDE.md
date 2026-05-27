# 基于 SAP BTP + Java + BTP 大语言模型的知识库（含 RAG）后端实现方案

> 目标：在你现有（刚修改过的）BTP 知识库前端基础上，落地一个 **Java 后端**，并使用 **SAP BTP 平台的大语言模型能力** 实现知识库问答、RAG 检索增强、文档导入、权限隔离与可观测性。

---

## 1. 目标架构（与前端对齐）

前端一般会有以下页面/模块：
- 知识库列表（创建、删除、配置）
- 文档管理（上传、解析状态、重建索引）
- 对话页（提问、追问、引用来源）
- 管理页（模型参数、检索参数、权限）

后端建议按 BFF + Domain Service 拆分：

1. **API 层（Spring Boot）**
   - 对接前端 REST/WebSocket
   - 统一鉴权（XSUAA/JWT）
   - 会话上下文管理
2. **Ingestion 层（导入管道）**
   - 文件上传、格式解析（PDF/Word/Markdown/HTML）
   - Chunk 切分、元数据抽取
   - 向量化并写入向量库
3. **Retrieval 层（检索）**
   - 向量检索 + 可选关键词检索（Hybrid Search）
   - 重排（rerank）
   - 多租户过滤（tenant_id / kb_id / acl）
4. **Generation 层（LLM）**
   - 使用 BTP 平台模型服务进行回答
   - Prompt 模板、引用拼装、拒答策略
5. **Observability/治理层**
   - 日志、追踪、审计
   - token 消耗、延迟、命中率

---

## 2. 推荐技术栈（Java + BTP）

- **Java 17/21 + Spring Boot 3.x**
- **Spring AI（可选）**：简化 embedding/chat/model 调用
- **SAP BTP 服务**（按你们账号可用项选择）：
  - Identity Authentication + XSUAA（鉴权）
  - HANA Cloud / PostgreSQL（结构化数据）
  - Object Store（原文存储）
  - 向量检索能力（HANA Vector / 外部向量数据库）
  - BTP 大模型推理服务（聊天、embedding）
- **异步任务**：Spring Batch / MQ / Job Scheduler
- **部署**：Cloud Foundry 或 Kyma

---

## 3. 数据模型设计（最小可用）

```text
kb_space(
  id, tenant_id, name, description, created_by, created_at
)

document(
  id, kb_id, tenant_id, file_name, file_type, file_uri, status,
  parse_error, version, uploaded_by, uploaded_at
)

chunk(
  id, doc_id, kb_id, tenant_id, content, token_count,
  section_title, page_no, metadata_json
)

chunk_vector(
  chunk_id, kb_id, tenant_id, embedding_vector, embedding_model
)

chat_session(
  id, kb_id, tenant_id, user_id, title, created_at
)

chat_message(
  id, session_id, role, content, citations_json, created_at
)
```

关键点：
- 所有核心表都要带 `tenant_id`、`kb_id`，方便做多租户/多知识库隔离。
- `chunk` 和 `chunk_vector` 分离，便于向量模型切换与重建索引。

---

## 4. 与前端对齐的 API 设计

### 4.1 知识库管理
- `POST /api/kbs`：创建知识库
- `GET /api/kbs`：分页查询
- `PATCH /api/kbs/{id}`：更新配置（检索 topK、阈值、prompt 模板）
- `DELETE /api/kbs/{id}`：删除

### 4.2 文档导入
- `POST /api/kbs/{id}/documents`：上传文档（返回 taskId）
- `GET /api/kbs/{id}/documents`：文档列表 + 处理状态
- `POST /api/kbs/{id}/documents/{docId}/reindex`：重建索引
- `DELETE /api/kbs/{id}/documents/{docId}`：删除文档

### 4.3 对话/RAG
- `POST /api/kbs/{id}/chat`：发起问答（同步）
- `POST /api/kbs/{id}/chat/stream`：流式输出（SSE/WebSocket）
- `GET /api/sessions/{sessionId}/messages`：历史消息

### 4.4 检索调试（给管理员）
- `POST /api/kbs/{id}/retrieve-debug`：返回召回片段、分数、过滤条件

---

## 5. RAG 主流程（后端）

1. 接收问题 + kb_id + user_context
2. 做 query rewrite（可选）
3. embedding 问题向量
4. 召回：`vector search topN` + （可选）`keyword search`
5. 过滤：tenant/kb/ACL/文档状态
6. rerank（可选）
7. 组装上下文（截断到 token 上限）
8. 调用 BTP LLM 生成答案
9. 返回答案 + citations（文档名、页码、chunk_id）
10. 记录日志与评估指标

### 5.1 伪代码（Spring Service）

```java
public ChatAnswer ask(ChatRequest req, UserPrincipal user) {
    validateAccess(req.kbId(), user);

    String rewritten = queryRewriteIfNeeded(req.question());
    float[] qVec = embeddingClient.embed(rewritten);

    List<ChunkHit> hits = retriever.hybridRetrieve(
        req.kbId(), user.tenantId(), qVec, rewritten, req.topK()
    );

    List<ChunkHit> filtered = aclFilter(hits, user);
    List<ChunkHit> reranked = rerankIfEnabled(filtered, rewritten);

    Prompt prompt = promptBuilder.buildRagPrompt(
        req.question(), reranked, req.chatHistory()
    );

    LlmResponse llm = llmClient.generate(prompt, req.modelConfig());
    List<Citation> citations = citationMapper.from(reranked);

    return ChatAnswer.of(llm.getText(), citations, llm.getUsage());
}
```

---

## 6. 文档导入与切分策略

建议：
- 先做“可维护优先”的切分，不要一上来太复杂。
- 默认 chunk 大小：500~800 tokens；overlap：80~120 tokens。
- 按标题层级/段落优先切分，再按长度二次切分。

导入状态机：
- `UPLOADED -> PARSING -> CHUNKING -> EMBEDDING -> INDEXED`
- 失败：`FAILED`（带错误码和可重试标记）

---

## 7. Prompt 与答案可信度控制

系统提示词建议包含：
- 仅根据提供的资料回答
- 资料不足时明确“未找到依据”
- 必须给出处（文档名 + 段落/页码）
- 禁止编造链接、文件、接口

可选增强：
- **Answer-then-cite** 或 **Cite-then-answer** 两种模板 A/B 测试
- 低置信度时自动触发“澄清问题”

---

## 8. 安全与多租户（BTP 场景重点）

- JWT 中解析 `tenant`、`sub`、`scope`
- 每个查询都强制 `tenant_id = currentTenant`
- 知识库级 ACL：owner/editor/viewer
- 文档级可选标签权限（例如机密等级）
- 审计日志：谁在何时问了什么、命中了哪些文档

---

## 9. 你可以直接按这个“落地顺序”推进

### 阶段 1（MVP，2~4 周）
- 完成 KB CRUD
- 完成上传->解析->向量化->检索
- 完成单轮问答 + 引用
- 完成基础鉴权

### 阶段 2（增强）
- 流式回答
- 多轮对话记忆
- 混合检索 + rerank
- 后台评估面板（命中率、延迟、成本）

### 阶段 3（企业化）
- 多租户隔离审计
- 数据脱敏
- Prompt/模型版本管理
- 自动评测与回归

---

## 10. 结合你“刚改过的前端”给一个接口对接清单

前端需要以下字段统一：
- `kbId`, `sessionId`, `messageId`
- `answer`, `citations[]`, `usage{promptTokens,completionTokens}`
- `docStatus`（用于上传进度）
- `retrievalConfig`（topK、threshold、hybrid 开关）

建议你在前端对话卡片里固定展示：
- 回答正文
- 引用来源（可点击跳文档）
- 模型与耗时信息（可选）

---

## 11. 最小返回结构示例

```json
{
  "sessionId": "s_123",
  "messageId": "m_789",
  "answer": "根据制度A第3节，报销上限为...",
  "citations": [
    {
      "docId": "d_1",
      "docName": "财务制度.pdf",
      "page": 12,
      "chunkId": "c_44",
      "score": 0.86
    }
  ],
  "usage": {
    "promptTokens": 1250,
    "completionTokens": 220
  },
  "latencyMs": 1830
}
```

---

## 12. 实施建议（避免常见坑）

- **先打通链路再优化精度**：先保证“可用”，再做召回率和答案质量优化。
- **明确可观测性**：没有日志和指标，RAG 调优会非常痛苦。
- **做好重建索引机制**：模型升级或切分策略变化后要可一键重建。
- **前后端协议先冻结**：先约定字段，避免页面频繁返工。

---

如果你希望，我下一步可以直接给你：
1) 一个可运行的 Spring Boot 项目骨架（Controller/Service/Repository）；
2) 基于 BTP 模型服务的 `ChatClient` 与 `EmbeddingClient` 接口模板；
3) 和你前端字段 1:1 对齐的 OpenAPI 3.0 文档。
