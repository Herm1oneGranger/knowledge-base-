package com.example.btpkb.api;

import com.example.btpkb.domain.Models;
import com.example.btpkb.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class KbController {
    private final RagService ragService;

    public KbController(RagService ragService) {
        this.ragService = ragService;
    }

    private String tenant() { return "tenant_demo"; }
    private String user() { return "user_demo"; }

    @PostMapping("/kbs")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.IdResponse createKb(@RequestBody @Valid ApiDtos.CreateKbRequest req) {
        Models.KbSpace kb = ragService.createKb(tenant(), user(), req.name(), req.description());
        return new ApiDtos.IdResponse(kb.id());
    }

    @GetMapping("/kbs")
    public List<Models.KbSpace> listKbs() {
        return ragService.listKbs(tenant());
    }

    @PostMapping("/kbs/{id}/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiDtos.TaskResponse upload(@PathVariable("id") String kbId, @RequestBody @Valid ApiDtos.UploadDocumentRequest req) {
        Models.Document doc = ragService.uploadDocument(tenant(), user(), kbId, req.fileName(), req.fileType(), req.fileUri());
        return new ApiDtos.TaskResponse("task_" + doc.id());
    }

    @GetMapping("/kbs/{id}/documents")
    public List<Models.Document> listDocuments(@PathVariable("id") String kbId) {
        return ragService.listDocuments(tenant(), kbId);
    }

    @PostMapping("/kbs/{id}/chat")
    public Models.ChatAnswer chat(@PathVariable("id") String kbId, @RequestBody @Valid ApiDtos.ChatRequest req) {
        return ragService.ask(tenant(), user(), kbId, req.question(), req.topK());
    }

    @PostMapping("/kbs/{id}/retrieve-debug")
    public ApiDtos.RetrieveDebugResponse retrieveDebug(@PathVariable("id") String kbId,
                                                       @RequestBody @Valid ApiDtos.RetrieveDebugRequest req) {
        return new ApiDtos.RetrieveDebugResponse(ragService.retrieveDebug(tenant(), kbId, req.question(), req.topK()));
    }
}
