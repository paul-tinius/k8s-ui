package com.paultinius.k8s.dashboard.cluster;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class QuerySupport {

    private QuerySupport() {
    }

    public static Set<String> namespaces(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            for (String part : item.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !"_".equals(trimmed)) {
                    names.add(trimmed);
                }
            }
        }
        return Set.copyOf(names);
    }

    public static String scope(String namespace) {
        if (namespace == null || namespace.isBlank() || "_".equals(namespace)) {
            return "";
        }
        return namespace;
    }
}
