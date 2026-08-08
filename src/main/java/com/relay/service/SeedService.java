package com.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.domain.WorkflowEntity;
import com.relay.repo.WorkflowRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SeedService implements CommandLineRunner {

    private final WorkflowRepo workflows;
    private final ObjectMapper om;

    @Override
    @SuppressWarnings("unchecked")
    public void run(String... args) throws Exception {
        var res = new ClassPathResource("data/seed_workflows.json");
        Map<String, Object> root = om.readValue(res.getInputStream(), Map.class);
        List<Map<String, Object>> ws = (List<Map<String, Object>>) root.get("workflows");

        for (var w : ws) {
            String id = (String) w.get("id");
            WorkflowEntity e = workflows.findById(id).orElseGet(WorkflowEntity::new);
            e.setId(id);
            e.setName((String) w.get("name"));
            e.setStatus("published");
            e.setDefinitionJson(om.writeValueAsString(w));
            if (e.getCreatedAt() == null) e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            workflows.save(e);
        }
    }
}