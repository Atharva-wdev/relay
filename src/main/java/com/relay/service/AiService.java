package com.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@RequiredArgsConstructor
public class AiService {
    private final ObjectMapper om;
    private final RestTemplate rt = new RestTemplate();

    @Value("${relay.ai.baseUrl}")
    private String baseUrl;

    @Value("${relay.ai.apiKey}")
    private String apiKey;

    @Value("${relay.ai.model}")
    private String model;

    @Data
    @AllArgsConstructor
    public static class AiResult {
        private Map<String, Object> output;
        private int promptTokens;
        private int completionTokens;
    }

    @SuppressWarnings("unchecked")
    public AiResult runStructured(String prompt, Map<String, Object> schema) throws Exception {
        String currentPrompt = prompt;
        Exception last = null;

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Map<String, Object> raw = callProvider(currentPrompt);

                List<Object> choices = (List<Object>) raw.getOrDefault("choices", List.of());
                if (choices.isEmpty()) throw new IllegalArgumentException("AI provider returned no choices");

                Map<String, Object> c0 = (Map<String, Object>) choices.get(0);
                Map<String, Object> message = (Map<String, Object>) c0.get("message");
                if (message == null) throw new IllegalArgumentException("AI provider returned no message");

                String content = String.valueOf(message.getOrDefault("content", "{}"));
                Map<String, Object> out = om.readValue(content, Map.class);

                validateSchema(out, schema);

                Map<String, Object> usage = (Map<String, Object>) raw.getOrDefault("usage", Map.of());
                int promptTokens = ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue();
                int completionTokens = ((Number) usage.getOrDefault("completion_tokens", 0)).intValue();

                return new AiResult(out, promptTokens, completionTokens);
            } catch (Exception e) {
                last = e;
                if (attempt == 1) {
                    currentPrompt = prompt + "\n\nYour previous output failed schema validation."
                            + " Return ONLY valid JSON matching the required schema. Error: " + e.getMessage();
                }
            }
        }

        throw last == null ? new IllegalStateException("AI execution failed") : last;
    }

    private void validateSchema(Map<String, Object> out, Map<String, Object> schemaMap) throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        JsonSchema schema = factory.getSchema(om.writeValueAsString(schemaMap));
        JsonNode node = om.valueToTree(out);
        Set<ValidationMessage> msgs = schema.validate(node);
        if (!msgs.isEmpty()) {
            throw new IllegalArgumentException(msgs.iterator().next().getMessage());
        }
    }

    private Map<String, Object> callProvider(String prompt) {
        Map<String, Object> req = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "stream", false
        );

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(apiKey);

        ResponseEntity<Map> resp = rt.exchange(
                baseUrl + "/v1/chat/completions",
                HttpMethod.POST,
                new HttpEntity<>(req, h),
                Map.class
        );

        return resp.getBody() == null ? Map.of() : resp.getBody();
    }
}