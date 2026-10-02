package com.paultinius.k8s.dashboard.model;

import java.util.List;
import java.util.Map;

public record ResourceView(
        String kind,
        String namespace,
        String name,
        String status,
        String node,
        List<String> images,
        Map<String, String> labels,
        String created,
        int ready,
        int desired,
        String summary,
        Map<String, String> attributes
) {
    public ResourceView {
        namespace = namespace == null ? "" : namespace;
        status = status == null ? "" : status;
        node = node == null ? "" : node;
        images = images == null ? List.of() : List.copyOf(images);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        created = created == null ? "" : created;
        summary = summary == null ? "" : summary;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
