package com.example.btpkb.infra;

import com.example.btpkb.domain.Models;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryStore {
    public final Map<String, Models.KbSpace> kbs = new ConcurrentHashMap<>();
    public final Map<String, Models.Document> docs = new ConcurrentHashMap<>();
    public final Map<String, List<Models.Chunk>> chunksByKb = new ConcurrentHashMap<>();
    public final Map<String, List<Map<String, String>>> sessions = new ConcurrentHashMap<>();

    public List<Models.Document> docsByKb(String kbId) {
        return docs.values().stream().filter(d -> d.kbId().equals(kbId)).toList();
    }

    public void addChunk(String kbId, Models.Chunk chunk) {
        chunksByKb.computeIfAbsent(kbId, k -> new ArrayList<>()).add(chunk);
    }
}
