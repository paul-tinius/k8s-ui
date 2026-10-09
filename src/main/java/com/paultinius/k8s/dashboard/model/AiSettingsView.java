package com.paultinius.k8s.dashboard.model;

/**
 * The AI assist configuration shown and edited in the Management tab's AI
 * assist panel. {@code apiKey} itself is never returned - only whether one
 * is currently set.
 */
public record AiSettingsView(
        String providerName,
        String baseUrl,
        String model,
        String orgId,
        boolean hasApiKey
) {
}
