package com.workflow.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = Map.of(
                "status", "healthy",
                "service", "Workflow Integration Hub"
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/doc")
    public ResponseEntity<String> getApiDoc() {
        try {
            String content = Files.readString(
                    Paths.get("src/main/resources/static/api-docs.yaml"),
                    StandardCharsets.UTF_8
            );
            return ResponseEntity.ok()
                    .header("Content-Type", "application/x-yaml")
                    .body(content);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}