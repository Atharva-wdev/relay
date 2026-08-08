package com.relay.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PublishValidationService {

    private static final Pattern TEMPLATE_RE =
            Pattern.compile("\\{\\{\\s*(trigger\\.body(?:\\.[A-Za-z0-9_]+)*|nodes\\.([A-Za-z0-9_]+)\\.output(?:\\.[A-Za-z0-9_]+)*)\\s*}}");

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> validate(Map<String, Object> wf, Map<String, Object> catalog) {
        try {
            for (String f : List.of("id", "name", "trigger", "entry", "nodes", "limits")) {
                if (!wf.containsKey(f)) return err("missing_field", "Workflow missing '" + f + "'");
            }

            Map<String, Object> trig = (Map<String, Object>) wf.get("trigger");
            Set<String> triggerTypes = new HashSet<>();
            for (Map<String, Object> t : (List<Map<String, Object>>) catalog.get("triggers")) {
                triggerTypes.add((String) t.get("type"));
            }
            if (!triggerTypes.contains((String) trig.get("type"))) {
                return err("invalid_trigger_type", "Unknown trigger type '" + trig.get("type") + "'");
            }
            if ("webhook".equals(trig.get("type")) &&
                    (trig.get("secret") == null || String.valueOf(trig.get("secret")).isBlank())) {
                return err("missing_webhook_secret", "Webhook trigger missing secret");
            }

            Map<String, Map<String, Object>> specs = new HashMap<>();
            for (Map<String, Object> n : (List<Map<String, Object>>) catalog.get("nodes")) {
                specs.put((String) n.get("type"), n);
            }

            List<Map<String, Object>> nodes = (List<Map<String, Object>>) wf.get("nodes");
            Set<String> ids = new HashSet<>();
            for (Map<String, Object> n : nodes) {
                String id = (String) n.get("id");
                if (id == null || id.isBlank()) return err("invalid_node_id", "Node missing id");
                if (!ids.add(id)) return err("duplicate_node_id", "Duplicate node id '" + id + "'");
            }

            String entry = (String) wf.get("entry");
            if (!ids.contains(entry)) return err("invalid_entry", "Entry points to unknown node '" + entry + "'");

            Set<String> typesUsed = new HashSet<>();
            for (Map<String, Object> n : nodes) {
                String nid = (String) n.get("id");
                String type = (String) n.get("type");
                typesUsed.add(type);

                Map<String, Object> spec = specs.get(type);
                if (spec == null) return err("invalid_node_type", "Node '" + nid + "' has unknown type '" + type + "'");

                Map<String, Object> params = (Map<String, Object>) n.getOrDefault("params", Map.of());
                Map<String, Object> paramSpec = (Map<String, Object>) spec.get("params");
                if (paramSpec != null) {
                    for (var e : paramSpec.entrySet()) {
                        String pname = e.getKey();
                        Map<String, Object> pdef = (Map<String, Object>) e.getValue();
                        if (Boolean.TRUE.equals(pdef.get("required")) && !params.containsKey(pname)) {
                            return err("missing_required_param", "Node '" + nid + "' missing required param '" + pname + "'");
                        }
                    }
                }

                if ("condition".equals(type)) {
                    for (String edge : List.of("on_true", "on_false")) {
                        if (!n.containsKey(edge)) return err("missing_edge", "Node '" + nid + "' missing edge '" + edge + "'");
                        Object target = n.get(edge);
                        if (target != null && !ids.contains(String.valueOf(target))) {
                            return err("bad_reference", "Node '" + nid + "' edge '" + edge + "' points to unknown node '" + target + "'");
                        }
                    }
                } else {
                    if (!n.containsKey("next")) return err("missing_edge", "Node '" + nid + "' missing edge 'next'");
                    Object target = n.get("next");
                    if (target != null && !ids.contains(String.valueOf(target))) {
                        return err("bad_reference", "Node '" + nid + "' edge 'next' points to unknown node '" + target + "'");
                    }
                }

                for (String txt : allStrings(params)) {
                    Matcher m = TEMPLATE_RE.matcher(txt);
                    while (m.find()) {
                        String refNode = m.group(2);
                        if (refNode != null && !ids.contains(refNode)) {
                            return err("bad_template_ref", "Node '" + nid + "' references unknown node '" + refNode + "' in template");
                        }
                    }
                }
            }

            for (Map<String, Object> n : nodes) {
                String t = (String) n.get("type");
                Map<String, Object> spec = specs.get(t);
                if (Boolean.TRUE.equals(spec.get("requires_approval")) && !typesUsed.contains("approval")) {
                    return err("approval_required", "Sensitive node '" + n.get("id") + "' requires an approval node in workflow");
                }
            }

            return Optional.empty();
        } catch (Exception e) {
            return err("validation_error", "Validation failed: " + e.getMessage());
        }
    }

    private Optional<Map<String, Object>> err(String code, String message) {
        return Optional.of(Map.of("error", Map.of("code", code, "message", message)));
    }

    private List<String> allStrings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof String s) out.add(s);
        else if (v instanceof Map<?, ?> m) m.values().forEach(x -> out.addAll(allStrings(x)));
        else if (v instanceof List<?> l) l.forEach(x -> out.addAll(allStrings(x)));
        return out;
    }
}