package com.paultinius.k8s.dashboard.ai;

import java.time.Duration;

public record ModelRequest(
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        String orgId,
        Duration timeout
) {
}
