package com.paultinius.k8s.dashboard.model;

public record AiStatus(boolean configured, String provider, String model, String baseUrlHost, String apiKeyEnv) {
}
