package com.example.btpkb.service;

import com.example.btpkb.domain.Models;
import com.example.btpkb.infra.InMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class RagService {
    private final InMemoryStore store;
    private final int defaultTopK;

    public RagService(InMemoryStore store, @Value("${app.rag.default-top-k:5}") int defaultTopK) {
        this.store = store;
        this.defaultTopK = defaultTopK;
    }

    public Models.KbSpace createKb(String tenantId, String userId, String name, String description) {
        String id = "kb_" + UUID.randomUUID();
        Models.KbSpace kb = new Models.KbSpace(id, tenantId, name, description, userId, Instant.now());
        store.kbs.put(id, kb);
        return kb;
    }

    public List<Models.KbSpace> listKbs(String tenantId) {
        return store.kbs.values().stream().filter(k -> k.tenantId().equals(tenantId)).toList();
    }

    public Models.Document uploadDocument(String tenantId, String userId, String kbId, String fileName, String fileType, String fileUri) {
        assertKbAccess(kbId, tenantId);
        String id = "doc_" + UUID.randomUUID();
        Models.Document doc = new Models.Document(id, kbId, tenantId, fileName, fileType, fileUri,
                Models.DocumentStatus.INDEXED, null, 1, userId, Instant.now());
        store.docs.put(id, doc);

        // 简化实现：同步切分并“向量化”
        for (int i = 1; i <= 3; i++) {
            store.addChunk(kbId, new Models.Chunk(
                    "chunk_" + UUID.randomUUID(), id, kbId, tenantId,
                    fileName + " 的示例内容片段 " + i,
                    120, "section-" + i, i, Map.of("source", fileName)
            ));
        }
        return doc;
    }

    public List<Models.Document> listDocuments(String tenantId, String kbId) {
        assertKbAccess(kbId, tenantId);
        return store.docsByKb(kbId);
    }

    public Models.ChatAnswer ask(String tenantId, String userId, String kbId, String question, Integer topK) {
        assertKbAccess(kbId, tenantId);
        long start = System.currentTimeMillis();

        List<Models.Chunk> chunks = store.chunksByKb.getOrDefault(kbId, List.of());
        int k = topK == null ? defaultTopK : topK;
        List<Models.ChunkHit> hits = chunks.stream()
                .limit(k)
                .map(c -> new Models.ChunkHit(c, 0.8))
                .toList();

        String answer = hits.isEmpty()
                ? "未检索到相关知识，请补充问题或上传文档。"
                : "基于知识库检索，给出回答：" + question + "\n参考片段数=" + hits.size();

        List<Models.Citation> citations = hits.stream().map(hit ->
                new Models.Citation(hit.chunk().docId(),
                        store.docs.get(hit.chunk().docId()).fileName(),
                        hit.chunk().pageNo(), hit.chunk().id(), hit.score())).toList();

        String sessionId = "s_" + UUID.randomUUID();
        String messageId = "m_" + UUID.randomUUID();
        store.sessions.computeIfAbsent(sessionId, x -> new ArrayList<>())
                .add(Map.of("role", "user", "content", question));
        store.sessions.get(sessionId).add(Map.of("role", "assistant", "content", answer));

        return new Models.ChatAnswer(sessionId, messageId, answer, citations,
                new Models.Usage(800, 160), System.currentTimeMillis() - start);
    }

    public List<Map<String, Object>> retrieveDebug(String tenantId, String kbId, String question, Integer topK) {
        assertKbAccess(kbId, tenantId);
        int k = topK == null ? defaultTopK : topK;
        return store.chunksByKb.getOrDefault(kbId, List.of()).stream().limit(k).map(c -> Map.of(
                "chunkId", c.id(), "content", c.content(), "score", 0.8, "question", question
        )).toList();
    }

    private void assertKbAccess(String kbId, String tenantId) {
        Models.KbSpace kb = store.kbs.get(kbId);
        if (kb == null || !kb.tenantId().equals(tenantId)) {
            throw new NoSuchElementException("KB not found or forbidden: " + kbId);
        }
    }
}
