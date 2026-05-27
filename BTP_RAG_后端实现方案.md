# BTP 平台 Java 后端知识库（RAG）实现方案（对接现有前端）

## 1. 目标与边界

基于你现有的 BTP 知识库前端界面，后端使用 Java（建议 Spring Boot）在 SAP BTP 上实现：

- 文档上传与知识库管理
- 文档切片、向量化、入库
- 检索增强生成（RAG）问答
- 会话上下文与引用返回
- 权限隔离（按租户/空间/知识库）

> 关键原则：前端只负责交互，后端统一负责编排 LLM、Embedding、检索、权限与审计。

---

## 2. 推荐架构（BTP 原生友好）

```text
[前端 BTP UI]
   -> [API Gateway / Approuter]
      -> [Java Spring Boot RAG Service]
           |- AuthN/AuthZ (XSUAA)
           |- Knowledge API
           |- Ingestion Pipeline
           |- Retrieval Service
           |- Prompt Orchestrator
           |- LLM Adapter (BTP LLM)
           |- Embedding Adapter (BTP Embedding)
           |- Vector Store Adapter (HANA Cloud Vector / pgvector)
           |- Metadata DB (HANA Cloud)
           |- Object Storage (文档原文)
```

### 组件说明

1. **Object Storage**：存 PDF/Word/TXT 原文。
2. **Metadata DB**：保存知识库、文档、chunk、权限、索引状态。
3. **Vector Store**：保存 chunk embedding，支持 ANN 检索。
4. **LLM/Embedding Adapter**：统一封装 BTP 平台大模型调用，便于后续模型切换。
5. **Prompt Orchestrator**：把检索结果拼装进 Prompt，输出答案+引用。

---

## 3. 与现有前端界面对接的 API 设计

假设你的前端已有“知识库列表 / 上传 / 检索问答 / 命中片段展示”模块，后端可按如下 API 对接：

### 3.1 知识库管理

- `POST /api/kb`：创建知识库
- `GET /api/kb`：获取当前用户可见知识库
- `DELETE /api/kb/{kbId}`：删除知识库（软删）

### 3.2 文档上传与索引

- `POST /api/kb/{kbId}/documents:upload`：上传文件（返回 `docId`）
- `POST /api/kb/{kbId}/documents/{docId}:index`：触发切片+向量化
- `GET /api/kb/{kbId}/documents/{docId}/status`：索引进度（PENDING/RUNNING/SUCCEEDED/FAILED）

### 3.3 问答（RAG）

- `POST /api/chat:ask`

请求：

```json
{
  "kbId": "kb-001",
  "question": "合同违约条款有哪些？",
  "topK": 5,
  "sessionId": "s-123",
  "filters": {
    "docType": ["contract"],
    "lang": "zh"
  }
}
```

响应：

```json
{
  "answer": "根据知识库中的合同模板，违约条款主要包括...",
  "citations": [
    {
      "docId": "d-01",
      "docName": "采购合同模板.pdf",
      "chunkId": "c-99",
      "score": 0.87,
      "snippet": "若乙方未按时交付..."
    }
  ],
  "traceId": "rag-20260527-0001",
  "tokenUsage": {
    "promptTokens": 1200,
    "completionTokens": 260
  }
}
```

---

## 4. RAG 核心流程（后端实现）

## 4.1 Ingestion（入库）

1. 上传文档到对象存储
2. 解析文本（PDF/Word）
3. 文本清洗（页眉页脚、空白、乱码）
4. 切片（建议 500~800 中文字 + 50~100 overlap）
5. 调用 BTP Embedding 模型生成向量
6. 写入向量库（chunk 向量 + 元数据）
7. 更新索引状态

## 4.2 Retrieval（检索）

1. 对用户问题做 embedding
2. 向量检索 topK
3. 结合元数据过滤（kbId、权限、文档类型）
4. 可选：重排（rerank）提升命中精度
5. 组装上下文（限制 token）

## 4.3 Generation（生成）

1. System Prompt 强约束（仅基于给定上下文回答）
2. 注入检索片段
3. 调用 BTP LLM
4. 后处理：引用对齐、敏感信息过滤、答案长度控制

---

## 5. Java 工程分层建议

```text
com.example.kb
 ├─ controller
 ├─ service
 │   ├─ ingestion
 │   ├─ retrieval
 │   ├─ rag
 │   └─ auth
 ├─ adapter
 │   ├─ llm
 │   ├─ embedding
 │   ├─ vectorstore
 │   └─ storage
 ├─ repository
 ├─ domain
 └─ config
```

### 关键接口示例

```java
public interface EmbeddingClient {
    List<Float> embed(String text);
    List<List<Float>> embedBatch(List<String> texts);
}

public interface VectorStore {
    void upsertChunks(String kbId, List<ChunkVector> chunks);
    List<RetrievedChunk> search(String kbId, List<Float> queryVector, int topK, Map<String, Object> filters);
}

public interface LlmClient {
    LlmAnswer generate(String systemPrompt, String userPrompt, List<RetrievedChunk> contextChunks);
}
```

---

## 6. Prompt 模板建议（RAG）

### System Prompt（示意）

- 你是企业知识库助手。
- 必须仅依据提供的 `CONTEXT` 回答。
- 若上下文不足，明确回复“知识库暂无足够信息”。
- 输出分两段：`结论` + `依据`。

### User Prompt 拼装

```text
问题:
{{question}}

CONTEXT:
[1] {{chunk_1}}
[2] {{chunk_2}}
...
```

---

## 7. 多租户与权限（BTP 场景重点）

- 每条 chunk 带 `tenantId`、`kbId`、`aclTags`
- 检索时强制注入租户和用户权限过滤
- API 层从 JWT 提取用户身份（XSUAA）
- 审计日志记录：谁在什么时候问了什么、命中了哪些文档

---

## 8. 性能与稳定性建议

- 批量 embedding（降低调用开销）
- 异步索引（消息队列 / 任务表）
- 热门问答缓存（短 TTL）
- 超时与熔断（LLM 调用）
- 检索失败降级（关键词检索兜底）

---

## 9. 你可以直接落地的最小版本（MVP）

第一阶段（1~2 周）：

1. 单知识库 + PDF 上传
2. 切片 + 向量检索 + 基础问答
3. 前端展示答案 + 3 条引用片段

第二阶段（2~4 周）：

1. 多知识库与权限
2. 重排与答案质量评估
3. 会话上下文与反馈闭环（👍/👎）

---

## 10. 与你当前前端页面的对接建议

- 前端“上传按钮” -> 调 `documents:upload`
- 前端“开始训练/入库” -> 调 `documents/{id}:index`
- 前端“提问输入框” -> 调 `chat:ask`
- 前端“引用卡片” -> 直接消费 `citations[]`
- 前端“状态轮询” -> 调 `documents/{id}/status`

这样可以最大程度复用你已经改好的 BTP 知识库前端，后端只补齐 RAG 能力。

---

## 11. 常见坑（提前规避）

- chunk 太大导致检索噪音高
- 仅做向量检索不做权限过滤（严重风险）
- prompt 不限制“仅依据上下文”导致幻觉
- 没有引用回传，前端无法做可解释展示

