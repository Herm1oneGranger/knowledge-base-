package com.example.btpkb.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class Models {
    public enum DocumentStatus {UPLOADED, PARSING, CHUNKING, EMBEDDING, INDEXED, FAILED}

    public record KbSpace(String id, String tenantId, String name, String description, String createdBy, Instant createdAt) {}

    public record Document(String id, String kbId, String tenantId, String fileName, String fileType, String fileUri,
                           DocumentStatus status, String parseError, int version, String uploadedBy, Instant uploadedAt) {}

    public record Chunk(String id, String docId, String kbId, String tenantId, String content, int tokenCount,
                        String sectionTitle, Integer pageNo, Map<String, Object> metadata) {}

    public record ChunkHit(Chunk chunk, double score) {}

    public record Citation(String docId, String docName, Integer page, String chunkId, double score) {}

    public record Usage(int promptTokens, int completionTokens) {}

    public record ChatAnswer(String sessionId, String messageId, String answer, List<Citation> citations,
                             Usage usage, long latencyMs) {}
}
