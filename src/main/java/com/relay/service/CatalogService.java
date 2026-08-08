package com.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CatalogService {
    private final ObjectMapper om;
    private Map<String, Object> catalog;

    @PostConstruct
    public void init() throws Exception {
        var r = new ClassPathResource("data/node_catalog.json");
        catalog = om.readValue(r.getInputStream(), Map.class);
    }

    public Map<String, Object> getCatalog() {
        return catalog;
    }
}