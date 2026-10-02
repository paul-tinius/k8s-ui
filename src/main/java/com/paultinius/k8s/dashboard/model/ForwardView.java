package com.paultinius.k8s.dashboard.model;

public record ForwardView(
        String id,
        String clusterId,
        String namespace,
        String targetKind,
        String targetName,
        String podName,
        int remotePort,
        int localPort,
        boolean simulated,
        String url,
        String note
) {
}
