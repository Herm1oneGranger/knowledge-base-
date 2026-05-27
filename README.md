# BTP Java Knowledge Base + RAG Backend

可运行的 Spring Boot 后端，实现了基于 SAP BTP 场景的知识库与 RAG 最小可用版本（MVP）接口：

- `POST /api/kbs`
- `GET /api/kbs`
- `POST /api/kbs/{id}/documents`
- `GET /api/kbs/{id}/documents`
- `POST /api/kbs/{id}/chat`
- `POST /api/kbs/{id}/retrieve-debug`

## 运行

```bash
mvn spring-boot:run
```

## 测试

```bash
mvn test
```
