package com.paultinius.k8s.dashboard.model;

import java.util.List;

public record ResourceDetail(ResourceView resource, String yaml, List<String> containers) {
    public ResourceDetail {
        yaml = yaml == null ? "" : yaml;
        containers = containers == null ? List.of() : List.copyOf(containers);
    }
}
