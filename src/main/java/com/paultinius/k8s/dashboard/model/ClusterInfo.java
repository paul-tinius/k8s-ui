package com.paultinius.k8s.dashboard.model;

public record ClusterInfo(
        String id,
        String name,
        String server,
        String namespace,
        boolean demo,
        boolean active,
        String source
) {
}
