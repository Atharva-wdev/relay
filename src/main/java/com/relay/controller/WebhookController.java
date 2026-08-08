package com.relay.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.domain.QueueJobEntity;
import com.relay.domain.RunEntity;
import com.relay.repo.QueueRepo;
import com.relay.repo.RunRepo;
import com.relay.repo.WorkflowRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class WebhookController {
    private final WorkflowRepo workflows;
    private final RunRepo runs;
    private final QueueRepo queue;
    private final ObjectMapper om;

    @SuppressWarnings("unchecked")
    @PostMapping("/hooks/{id}")
    public ResponseEntity<?> hook(@PathVariable String id,
                                  @RequestHeader(value = "X-Relay-Secret", required = false) String secret,
                                  @RequestBody Map<String, Object> body) {
        var ow = workflows.findById(id);
        if (ow.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> def = read(ow.get().getDefinitionJson());
        Map<String, Object> trig = (Map<String, Object>) def.get("trigger");
        if (!Objects.equals(secret, trig.get("secret"))) {
            return ResponseEntity.status(403).body(Map.of("error", Map.of("message", "invalid secret")));
        }

        String runId = "run_" + UUID.randomUUID().toString().substring(0, 8);

        RunEntity r = new RunEntity();
        r.setRunId(runId);
        r.setWorkflowId(id);
        r.setStatus("queued");
        r.setTriggerType("webhook");
        r.setDefinitionSnapshot(ow.get().getDefinitionJson());
        r.setInputJson(write(body));
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