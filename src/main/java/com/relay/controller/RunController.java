package com.relay.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.repo.RunRepo;
import com.relay.repo.RunStepRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RunController {
    private final RunRepo runs;
    private final RunStepRepo steps;
    private final ObjectMapper om;

    @GetMapping("/runs/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        var r = runs.findById(id);
        if (r.isEmpty()) return ResponseEntity.notFound().build();

        var rs = steps.findByRunIdOrderBySequenceNoAsc(id).stream().map(s -> Map.of(
                "node_id", s.getNodeId(),
                "status", s.getStatus(),
                "type", s.getNodeType(),
                "attempt", s.getAttempt(),
                "input", json(s.getResolvedInputJson()),
                "output", json(s.getOutputJson())
        )).toList();

        return ResponseEntity.ok(Map.of(
                "run_id", id,
                "workflow_id", r.get().getWorkflowId(),
                "status", r.get().getStatus(),
                "steps", rs
        ));
    }

    private Object json(String s) {
        try { return s == null ? Map.of() : om.readValue(s, Object.class); }
        catch (Exception e) { return Map.of(); }
    }
}