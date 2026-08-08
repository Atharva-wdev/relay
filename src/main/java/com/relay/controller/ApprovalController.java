package com.relay.controller;

import com.relay.domain.ApprovalEntity;
import com.relay.domain.QueueJobEntity;
import com.relay.domain.RunEntity;
import com.relay.repo.ApprovalRepo;
import com.relay.repo.QueueRepo;
import com.relay.repo.RunRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ApprovalController {
    private final ApprovalRepo approvals;
    private final RunRepo runs;
    private final QueueRepo queue;

    @GetMapping("/approvals")
    public Object list(@RequestParam(defaultValue = "pending") String status) {
        return approvals.findByStatus(status).stream()
                .map(a -> Map.of("id", a.getId(), "run_id", a.getRunId(), "message", a.getMessage()))
                .toList();
    }

    @PostMapping("/approvals/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable String id) {
        var oa = approvals.findById(id);
        if (oa.isEmpty()) return ResponseEntity.notFound().build();

        ApprovalEntity a = oa.get();
        if (!"pending".equals(a.getStatus())) {
            return ResponseEntity.status(409).body(Map.of("error", Map.of("message", "already decided")));
        }

        a.setStatus("approved");
        a.setDecidedBy("demo-approver");
        a.setDecidedAt(Instant.now());
        approvals.save(a);

        RunEntity r = runs.findById(a.getRunId()).orElseThrow();
        r.setStatus("queued");
        runs.save(r);

        queue.findByRunId(r.getRunId()).ifPresentOrElse(q -> {
            q.setStatus("queued");
            q.setAvailableAt(Instant.now());
            q.setLeasedUntil(null);
            queue.save(q);
        }, () -> {
            QueueJobEntity q = new QueueJobEntity();
            q.setRunId(r.getRunId());
            q.setStatus("queued");
            q.setAttempts(0);
            q.setCreatedAt(Instant.now());
            q.setAvailableAt(Instant.now());
            queue.save(q);
        });

        return ResponseEntity.ok(Map.of("status", "approved"));
    }

    @PostMapping("/approvals/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable String id) {
        var oa = approvals.findById(id);
        if (oa.isEmpty()) return ResponseEntity.notFound().build();

        ApprovalEntity a = oa.get();
        if (!"pending".equals(a.getStatus())) {
            return ResponseEntity.status(409).body(Map.of("error", Map.of("message", "already decided")));
        }

        a.setStatus("rejected");
        a.setDecidedBy("demo-approver");
        a.setDecidedAt(Instant.now());
        approvals.save(a);

        RunEntity r = runs.findById(a.getRunId()).orElseThrow();
        r.setStatus("cancelled");
        r.setFinishedAt(Instant.now());
        runs.save(r);

        return ResponseEntity.ok(Map.of("status", "rejected"));
    }
}