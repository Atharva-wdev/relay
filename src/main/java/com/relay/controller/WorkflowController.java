package com.relay.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.domain.QueueJobEntity;
import com.relay.domain.RunEntity;
import com.relay.domain.WorkflowEntity;
import com.relay.repo.QueueRepo;
import com.relay.repo.RunRepo;
import com.relay.repo.WorkflowRepo;
import com.relay.service.CatalogService;
import com.relay.service.PublishValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class WorkflowController {
    private final WorkflowRepo workflows;
    private final RunRepo runs;
    private final QueueRepo queue;
    private final ObjectMapper om;
    private final PublishValidationService validator;
    private final CatalogService catalogService;

    @GetMapping("/workflows")
    public List<Map<String, Object>> list() {
        return workflows.findAll().stream().map(w -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", w.getId());
            m.put("name", w.getName());
            m.put("status", w.getStatus());
            return m;
        }).toList();
    }

    @GetMapping("/workflows/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return workflows.findById(id)
                .<ResponseEntity<?>>map(w -> ResponseEntity.ok(read(w.getDefinitionJson())))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/workflows")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> def) {
        String id = (String) def.get("id");
        if (id == null || id.isBlank()) return ResponseEntity.badRequest().body(err("id required"));

        WorkflowEntity w = workflows.findById(id).orElse(new WorkflowEntity());
        w.setId(id);
        w.setName((String) def.getOrDefault("name", id));
        w.setStatus("draft");
        w.setDefinitionJson(write(def));
        if (w.getCreatedAt() == null) w.setCreatedAt(Instant.now());
        w.setUpdatedAt(Instant.now());
        workflows.save(w);

        return ResponseEntity.ok(Map.of("id", id, "status", "draft"));
    }

    @PostMapping("/workflows/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable String id) {
        var ow = workflows.findById(id);
        if (ow.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> def = read(ow.get().getDefinitionJson());
        var err = validator.validate(def, catalogService.getCatalog());
        if (err.isPresent()) return ResponseEntity.badRequest().body(err.get());

        WorkflowEntity w = ow.get();
        w.setStatus("published");
        w.setUpdatedAt(Instant.now());
        workflows.save(w);

        return ResponseEntity.ok(Map.of("id", id, "status", "published"));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/workflows/{id}/trigger")
    public ResponseEntity<?> trigger(@PathVariable String id, @RequestBody Map<String, Object> body) {
        var ow = workflows.findById(id);
        if (ow.isEmpty()) return ResponseEntity.notFound().build();
        if (!"published".equals(ow.get().getStatus()))
            return ResponseEntity.badRequest().body(err("workflow not published"));

        String runId = "run_" + UUID.randomUUID().toString().substring(0, 8);

        RunEntity r = new RunEntity();
        r.setRunId(runId);
        r.setWorkflowId(id);
        r.setStatus("queued");
        r.setTriggerType("manual");
        r.setDefinitionSnapshot(ow.get().getDefinitionJson());
        r.setInputJson(write((Map<String, Object>) body.getOrDefault("input", Map.of())));
        r.setStartedAt(Instant.now());
        runs.save(r);

        QueueJobEntity q = new QueueJobEntity();
        q.setRunId(runId);
        q.setStatus("queued");
        q.setAttempts(0);
        q.setCreatedAt(Instant.now());
        q.setAvailableAt(Instant.now());
        queue.save(q);

        return ResponseEntity.ok(Map.of("run_id", runId));
    }

    private Map<String, Object> err(String m) {
        return Map.of("error", Map.of("message", m));
    }

    private String write(Object o) {
        try { return om.writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String s) {
        try { return om.readValue(s, Map.class); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}