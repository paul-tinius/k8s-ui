package com.paultinius.k8s.dashboard.model;

public record AiPreset(
        String id,
        String label,
        String baseUrl,
        String model,
        String apiKeyEnv,
        boolean compatible,
        String note
) {
}
