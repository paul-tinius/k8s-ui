package com.paultinius.k8s.dashboard.model;

import java.util.List;

public record Overview(
        String clusterId,
        String clusterName,
        boolean demo,
        String version,
        int namespaces,
        int pods,
        int readyPods,
        int deployments,
        int services,
        int configMaps,
        int nodes,
        int warnings,
        List<PhaseCount> phases,
        List<ResourceView> attention,
        List<MetricRow> metrics
) {
    public Overview {
        phases = phases == null ? List.of() : List.copyOf(phases);
        attention = attention == null ? List.of() : List.copyOf(attention);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        version = version == null ? "" : version;
    }

    public record PhaseCount(String phase, int count) {
    }

    public record MetricRow(String namespace, String name, String cpu, String memory) {
    }
}
