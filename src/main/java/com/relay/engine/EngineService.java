package com.relay.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.domain.*;
import com.relay.repo.*;
import com.relay.service.AiService;
import com.relay.service.TemplateService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class EngineService {

    private final RunRepo runs;
    private final RunStepRepo steps;
    private final ApprovalRepo approvals;
    private final QueueRepo queue;
    private final ObjectMapper om;
    private final TemplateService tmpl;
    private final AiService aiService;

    private final RestTemplate rt = new RestTemplate();

    @Value("${relay.mockWorldUrl}")
    private String world;

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        for (QueueJobEntity j : queue.findAll()) {
            if (!"queued".equals(j.getStatus())) continue;
            if (j.getAvailableAt() != null && j.getAvailableAt().isAfter(Instant.now())) continue;

            j.setStatus("processing");
            j.setLeasedUntil(Instant.now().plusSeconds(30));
            queue.save(j);

            try {
                processRun(j.getRunId());

                RunEntity r = runs.findById(j.getRunId()).orElse(null);
                if (r == null || Set.of("succeeded", "failed", "cancelled", "waiting_approval").contains(r.getStatus())) {
                    j.setStatus("done");
                } else {
                    j.setStatus("queued");
                    j.setAvailableAt(Instant.now().plusSeconds(1));
                }

                j.setLeasedUntil(null);
                queue.save(j);

            } catch (Exception e) {
                int attempts = (j.getAttempts() == null ? 0 : j.getAttempts()) + 1;
                j.setAttempts(attempts);

                if (attempts >= 5) {
                    runs.findById(j.getRunId()).ifPresent(r -> {
                        r.setStatus("failed");
                        r.setErrorJson("{\"message\":\"worker retries exhausted: " + safe(e.getMessage()) + "\"}");
                        r.setFinishedAt(Instant.now());
                        runs.save(r);
                    });
                    j.setStatus("done");
                } else {
                    j.setStatus("queued");
                    j.setAvailableAt(Instant.now().plusSeconds(2));
                }

                j.setLeasedUntil(null);
                queue.save(j);
                e.printStackTrace();
            }
        }
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void processRun(String runId) throws Exception {
        RunEntity run = runs.findById(runId).orElseThrow();

        if (Set.of("succeeded", "failed", "cancelled").contains(run.getStatus())) return;
        if ("waiting_approval".equals(run.getStatus())) return;

        run.setStatus("running");
        runs.save(run);

        Map<String, Object> def = om.readValue(run.getDefinitionSnapshot(), new TypeReference<>() {});
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) def.get("nodes");
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> n : nodes) byId.put((String) n.get("id"), n);

        String nodeId = run.getCurrentNodeId() == null ? (String) def.get("entry") : run.getCurrentNodeId();

        while (nodeId != null) {
            Map<String, Object> limits = (Map<String, Object>) def.getOrDefault("limits", Map.of("max_steps", 50));
            Object ms = limits.getOrDefault("max_steps", 50);
            int maxSteps = (ms instanceof Number) ? ((Number) ms).intValue() : Integer.parseInt(String.valueOf(ms));

            if (run.getStepsExecuted() >= maxSteps) {
                fail(run, "step cap exceeded max_steps");
                return;
            }

            Map<String, Object> node = byId.get(nodeId);
            if (node == null) {
                fail(run, "unknown node " + nodeId);
                return;
            }

            String type = String.valueOf(node.get("type"));

            if ("order_action".equals(type) && !approvals.existsByRunIdAndStatus(runId, "approved")) {
                fail(run, "approval required");
                return;
            }

            int seq = run.getStepsExecuted() + 1;
            String idem = runId + ":" + nodeId + ":" + seq;
            long t0 = System.currentTimeMillis();

            Map<String, Object> ctx = buildContext(runId, run.getInputJson());

            NodeExecResult execResult = executeWithRetry(type, node, ctx, idem, runId, nodeId, seq);

            if (!execResult.success) {
                run.setStatus("failed");
                run.setErrorJson("{\"message\":\"" + safe(execResult.errorMessage) + "\",\"node_id\":\"" + safe(nodeId) + "\"}");
                run.setFinishedAt(Instant.now());
                runs.save(run);
                return;
            }

            Map<String, Object> out = execResult.output;

            Integer tp = null, tc = null;
            if (out.containsKey("_tokens_prompt")) tp = ((Number) out.remove("_tokens_prompt")).intValue();
            if (out.containsKey("_tokens_completion")) tc = ((Number) out.remove("_tokens_completion")).intValue();

            RunStepEntity s = new RunStepEntity();
            s.setRunId(runId);
            s.setNodeId(nodeId);
            s.setNodeType(type);
            s.setSequenceNo(seq);
            s.setStatus("succeeded");
            s.setAttempt(execResult.attemptsUsed);
            s.setResolvedInputJson(om.writeValueAsString(node.get("params")));
            s.setOutputJson(om.writeValueAsString(out));
            s.setIdempotencyKey(idem);
            s.setTokensPrompt(tp);
            s.setTokensCompletion(tc);
            s.setStartedAt(Instant.now());
            s.setDurationMs(System.currentTimeMillis() - t0);
            steps.save(s);

            run.setStepsExecuted(seq);
            if (tp != null) run.setAiTokensUsed(run.getAiTokensUsed() + tp);
            if (tc != null) run.setAiTokensUsed(run.getAiTokensUsed() + tc);

            nodeId = nextNode(node, out);
            run.setCurrentNodeId(nodeId);
            runs.save(run);

            if ("approval".equals(type)) {
                run.setStatus("waiting_approval");
                runs.save(run);
                return;
            }
        }

        run.setStatus("succeeded");
        run.setFinishedAt(Instant.now());
        runs.save(run);
    }

    private NodeExecResult executeWithRetry(String type, Map<String, Object> node, Map<String, Object> ctx,
                                            String idem, String runId, String nodeId, int seq) throws Exception {
        int maxAttempts = isRetriableNode(type) ? 3 : 1;
        Exception last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long t0 = System.currentTimeMillis();
            try {
                Map<String, Object> out = executeNodeOnce(type, node, ctx, idem, runId, nodeId);
                return NodeExecResult.success(out, attempt);
            } catch (Exception ex) {
                last = ex;

                RunStepEntity fs = new RunStepEntity();
                fs.setRunId(runId);
                fs.setNodeId(nodeId);
                fs.setNodeType(type);
                fs.setSequenceNo(seq);
                fs.setStatus("failed");
                fs.setAttempt(attempt);
                fs.setResolvedInputJson(om.writeValueAsString(node.get("params")));
                fs.setOutputJson("{\"error\":\"" + safe(ex.getMessage()) + "\"}");
                fs.setIdempotencyKey(idem);
                fs.setStartedAt(Instant.now());
                fs.setDurationMs(System.currentTimeMillis() - t0);
                steps.save(fs);

                if (!isTransient(ex) || attempt == maxAttempts) {
                    return NodeExecResult.failure("node failed after " + attempt + " attempt(s): " + ex.getMessage(), attempt);
                }

                backoff(attempt);
            }
        }

        return NodeExecResult.failure("node failed: " + (last == null ? "unknown" : last.getMessage()), maxAttempts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeNodeOnce(String type, Map<String, Object> node, Map<String, Object> ctx,
                                                String idem, String runId, String nodeId) throws Exception {
        Map<String, Object> p = (Map<String, Object>) node.getOrDefault("params", Map.of());

        if ("delay".equals(type)) {
            long sec = ((Number) p.getOrDefault("seconds", 1)).longValue();
            Thread.sleep(sec * 1000L);
            return Map.of();
        }

        if ("condition".equals(type)) {
            String l = tmpl.resolve(String.valueOf(p.get("left")), ctx);
            String r = tmpl.resolve(String.valueOf(p.get("right")), ctx);
            String op = String.valueOf(p.get("op"));
            boolean res = switch (op) {
                case "equals" -> l.equals(r);
                case "not_equals" -> !l.equals(r);
                case "greater_than" -> Double.parseDouble(l) > Double.parseDouble(r);
                case "less_than" -> Double.parseDouble(l) < Double.parseDouble(r);
                case "contains" -> l.contains(r);
                default -> throw new IllegalArgumentException("unsupported condition op: " + op);
            };
            return Map.of("result", res);
        }

        if ("notify".equals(type)) {
            return executeNotify(p, ctx, idem);
        }

        if ("http_request".equals(type)) {
            String method = String.valueOf(p.get("method")).toUpperCase(Locale.ROOT);
            String url = tmpl.resolve(String.valueOf(p.get("url")), ctx);
            Object body = p.get("body");

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            if (!"GET".equals(method)) h.set("Idempotency-Key", idem);

            ResponseEntity<Map> r = rt.exchange(url, HttpMethod.valueOf(method), new HttpEntity<>(body, h), Map.class);
            return Map.of("status", r.getStatusCode().value(), "body", r.getBody() == null ? Map.of() : r.getBody());
        }

        if ("approval".equals(type)) {
            ApprovalEntity a = new ApprovalEntity();
            a.setId("apr_" + UUID.randomUUID().toString().substring(0, 8));
            a.setRunId(runId);
            a.setNodeId(nodeId);
            a.setStatus("pending");
            a.setMessage(tmpl.resolve(String.valueOf(p.get("message")), ctx));
            a.setCreatedAt(Instant.now());
            approvals.save(a);
            return Map.of("approval_id", a.getId(), "status", "pending");
        }

        if ("order_action".equals(type)) {
            String action = String.valueOf(p.get("action"));
            String orderId = tmpl.resolve(String.valueOf(p.get("order_id")), ctx);

            String url = "refund".equals(action)
                    ? world + "/orders/" + orderId + "/refund"
                    : world + "/orders/" + orderId + "/replacement";

            Map<String, Object> body = p.containsKey("amount_usd")
                    ? Map.of("amount_usd", p.get("amount_usd"))
                    : Map.of();

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set("Idempotency-Key", idem);

            Map<String, Object> resp = rt.postForObject(url, new HttpEntity<>(body, h), Map.class);
            return resp == null ? Map.of() : resp;
        }

        if ("ai".equals(type)) {
            String prompt = tmpl.resolve(String.valueOf(p.get("prompt")), ctx);
            Map<String, Object> schema = (Map<String, Object>) p.get("output_schema");
            AiService.AiResult r = aiService.runStructured(prompt, schema);
            Map<String, Object> out = new HashMap<>(r.getOutput());
            out.put("_tokens_prompt", r.getPromptTokens());
            out.put("_tokens_completion", r.getCompletionTokens());
            return out;
        }

        throw new IllegalArgumentException("unknown node type " + type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeNotify(Map<String, Object> p, Map<String, Object> ctx, String idem) throws Exception {
        String ch = String.valueOf(p.get("channel"));
        String to = tmpl.resolve(String.valueOf(p.get("to")), ctx);
        String msg = tmpl.resolve(String.valueOf(p.get("message")), ctx);

        if (to == null || to.isBlank()) throw new IllegalArgumentException("notify.to resolved blank");
        if (msg == null || msg.isBlank()) throw new IllegalArgumentException("notify.message resolved blank");

        String url;
        Map<String, Object> body = new LinkedHashMap<>();

        if ("email".equalsIgnoreCase(ch)) {
            url = world + "/email/send";
            body.put("to", to);
            body.put("message", msg);
            if (p.get("subject") != null) {
                body.put("subject", tmpl.resolve(String.valueOf(p.get("subject")), ctx));
            }
        } else if ("chat".equalsIgnoreCase(ch)) {
            url = world + "/chat/message";
            body.put("channel", to); // strict mock_world contract
            body.put("message", msg);
        } else {
            throw new IllegalArgumentException("notify.channel must be email or chat");
        }

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", idem);
//        System.out.println("[notify-debug] url=" + url + " body=" + om.writeValueAsString(body));
//        Map<String, Object> resp = rt.postForObject(url, new HttpEntity<>(body, h), Map.class);
//        return resp == null ? Map.of() : resp;

        System.out.println("[notify-debug] url=" + url + " body=" + om.writeValueAsString(body));
        return postJson(url, body, idem);
    }

    private boolean isRetriableNode(String type) {
        return Set.of("notify", "http_request", "order_action", "ai").contains(type);
    }

    private boolean isTransient(Exception ex) {
        if (ex instanceof ResourceAccessException) return true;
        if (ex instanceof HttpServerErrorException) return true;
        if (ex instanceof UnknownHttpStatusCodeException) return true;
        if (ex instanceof HttpClientErrorException.BadRequest) return false; // payload/contract bug, not transient

        Throwable c = ex;
        while (c != null) {
            if (c instanceof SocketTimeoutException || c instanceof SocketException || c instanceof IOException) return true;
            c = c.getCause();
        }
        return false;
    }

    private void backoff(int attempt) {
        long ms = (attempt == 1) ? 500L : 1000L;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private String nextNode(Map<String, Object> node, Map<String, Object> out) {
        String type = String.valueOf(node.get("type"));
        if ("condition".equals(type)) {
            boolean b = Boolean.TRUE.equals(out.get("result"));
            Object target = node.get(b ? "on_true" : "on_false");
            return target == null ? null : String.valueOf(target);
        }
        Object target = node.get("next");
        return target == null ? null : String.valueOf(target);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildContext(String runId, String inputJson) throws Exception {
        Map<String, Object> triggerBody = om.readValue(inputJson, Map.class);
        Map<String, Object> nodesCtx = new HashMap<>();
        for (RunStepEntity s : steps.findByRunIdOrderBySequenceNoAsc(runId)) {
            Map<String, Object> output = om.readValue(Optional.ofNullable(s.getOutputJson()).orElse("{}"), Map.class);
            nodesCtx.put(s.getNodeId(), Map.of("output", output));
        }
        return Map.of("trigger", Map.of("body", triggerBody), "nodes", nodesCtx);
    }

    private void fail(RunEntity r, String msg) {
        r.setStatus("failed");
        r.setErrorJson("{\"message\":\"" + safe(msg) + "\"}");
        r.setFinishedAt(Instant.now());
        runs.save(r);
    }

    private String safe(String s) {
        return (s == null ? "" : s).replace("\"", "\\\"");
    }

    private static class NodeExecResult {
        final boolean success;
        final Map<String, Object> output;
        final String errorMessage;
        final int attemptsUsed;

        private NodeExecResult(boolean success, Map<String, Object> output, String errorMessage, int attemptsUsed) {
            this.success = success;
            this.output = output;
            this.errorMessage = errorMessage;
            this.attemptsUsed = attemptsUsed;
        }

        static NodeExecResult success(Map<String, Object> out, int attempts) {
            return new NodeExecResult(true, out, null, attempts);
        }

        static NodeExecResult failure(String msg, int attempts) {
            return new NodeExecResult(false, Map.of(), msg, attempts);
        }
    }

    private Map<String, Object> postJson(String url, Map<String, Object> body, String idem) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();

        String json = om.writeValueAsString(body);

        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idem)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build();

        java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());

        int code = resp.statusCode();
        String respBody = resp.body();

        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + " from " + url + ": " + (respBody == null ? "" : respBody));
        }

        if (respBody == null || respBody.isBlank()) return Map.of();

        return om.readValue(respBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
}