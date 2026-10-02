package com.paultinius.k8s.dashboard.health;

import com.paultinius.k8s.dashboard.cluster.ClusterRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class ClusterHealthIndicator implements HealthIndicator {

    private final ClusterRegistry clusters;

    public ClusterHealthIndicator(ClusterRegistry clusters) {
        this.clusters = clusters;
    }

    @Override
    public Health health() {
        return Health.up().withDetail("clusters", clusters.list().size()).build();
    }
}
