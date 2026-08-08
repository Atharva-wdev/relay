package com.relay.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TemplateService {
    private static final Pattern P = Pattern.compile("\\{\\{\\s*([^}]+)\\s*}}");

    public String resolve(String s, Map<String, Object> ctx) {
        if (s == null) return null;
        Matcher m = P.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            Object v = readPath(ctx, m.group(1).trim());
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    public Object readPath(Object root, String path) {
        Object cur = root;
        for (String p : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> mm)) return null;
            cur = ((Map<String, Object>) mm).get(p);
            if (cur == null) return null;
        }
        return cur;
    }
}