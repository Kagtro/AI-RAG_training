package com.kagtro.rag.controller;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final JdbcClient jdbcClient;

    public HealthController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        try {
            Integer one = jdbcClient.sql("select 1").query(Integer.class).single();
            result.put("db", one == 1 ? "up" : "unknown");
        } catch (Exception e) {
            result.put("db", "down: " + e.getMessage());
        }
        result.put("app", "ai-rag");
        return result;
    }
}
