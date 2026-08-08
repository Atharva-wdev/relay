package com.relay.service;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(1)
public class AuthFilter implements Filter {

    @Value("${relay.authToken}")
    private String token;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest r = (HttpServletRequest) req;
        HttpServletResponse w = (HttpServletResponse) res;
        String p = r.getRequestURI();

        if (p.startsWith("/hooks/") || p.startsWith("/ui/")) {
            chain.doFilter(req, res);
            return;
        }

        String auth = r.getHeader("Authorization");
        if (auth == null || !auth.equals("Bearer " + token)) {
            w.setStatus(401);
            w.setContentType("application/json");
            w.getWriter().write("{\"error\":{\"message\":\"Unauthorized\"}}");
            return;
        }

        chain.doFilter(req, res);
    }
}