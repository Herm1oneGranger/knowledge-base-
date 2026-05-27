package com.example.btpkb.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public class ApiDtos {
    public record CreateKbRequest(@NotBlank String name, String description) {}
    public record UpdateKbRequest(Integer topK, Double threshold, Boolean hybrid, String promptTemplate) {}

    public record UploadDocumentRequest(@NotBlank String fileName, @NotBlank String fileType, @NotBlank String fileUri) {}

    public record ChatRequest(@NotBlank String question, Integer topK, Map<String, Object> modelConfig,
                              List<HistoryMessage> chatHistory) {}

    public record HistoryMessage(@NotBlank String role, @NotBlank String content) {}

    public record RetrieveDebugRequest(@NotBlank String question, Integer topK) {}

    public record IdResponse(@NotBlank String id) {}
    public record TaskResponse(@NotBlank String taskId) {}

    public record RetrieveDebugResponse(List<Map<String, Object>> hits) {}

    public record ErrorResponse(String code, String message) {}
}
