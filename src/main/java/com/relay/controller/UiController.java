package com.relay.controller;

import com.relay.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/ui")
public class UiController {
    private final WorkflowRepo workflows;
    private final RunRepo runs;
    private final RunStepRepo steps;
    private final ApprovalRepo approvals;

    @GetMapping("/workflows")
    public String workflows(Model m) {
        m.addAttribute("workflows", workflows.findAll());
        return "workflows";
    }

    @GetMapping("/runs")
    public String runs(Model m) {
        m.addAttribute("runs", runs.findAll());
        return "runs";
    }

    @GetMapping("/runs/{id}")
    public String run(@PathVariable String id, Model m) {
        m.addAttribute("run", runs.findById(id).orElse(null));
        m.addAttribute("steps", steps.findByRunIdOrderBySequenceNoAsc(id));
        return "run-detail";
    }

    @GetMapping("/approvals")
    public String approvals(Model m) {
        m.addAttribute("approvals", approvals.findByStatus("pending"));
        return "approvals";
    }
}