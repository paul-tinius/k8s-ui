package com.paultinius.k8s.dashboard.cluster;

import java.util.Set;

public record LogRequest(
        Set<String> namespaces,
        String deployment,
        String pod,
        String container,
        int tail,
        String query
) {
    public LogRequest {
        namespaces = namespaces == null ? Set.of() : Set.copyOf(namespaces);
        deployment = deployment == null ? "" : deployment;
        pod = pod == null ? "" : pod;
        container = container == null ? "" : container;
        query = query == null ? "" : query;
        if (tail <= 0) {
            tail = 100;
        }
        if (tail > 2000) {
            tail = 2000;
        }
    }
}
