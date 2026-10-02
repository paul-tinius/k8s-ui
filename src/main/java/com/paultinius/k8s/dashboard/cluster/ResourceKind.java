package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.error.DashboardException;

import java.util.Locale;

public enum ResourceKind {
    POD("Pod", true),
    DEPLOYMENT("Deployment", true),
    SERVICE("Service", true),
    CONFIG_MAP("ConfigMap", true),
    NODE("Node", false),
    EVENT("Event", true);

    private final String apiName;
    private final boolean namespaced;

    ResourceKind(String apiName, boolean namespaced) {
        this.apiName = apiName;
        this.namespaced = namespaced;
    }

    public String apiName() {
        return apiName;
    }

    public boolean namespaced() {
        return namespaced;
    }

    public static ResourceKind from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw DashboardException.badRequest("Kind is required");
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (key) {
            case "pod", "pods" -> POD;
            case "deployment", "deployments", "deploy" -> DEPLOYMENT;
            case "service", "services", "svc" -> SERVICE;
            case "configmap", "configmaps", "cm" -> CONFIG_MAP;
            case "node", "nodes" -> NODE;
            case "event", "events" -> EVENT;
            default -> throw DashboardException.badRequest("Unsupported kind " + raw);
        };
    }
}
