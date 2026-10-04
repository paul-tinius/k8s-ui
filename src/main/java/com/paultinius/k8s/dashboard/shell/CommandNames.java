package com.paultinius.k8s.dashboard.shell;

import java.util.List;

interface CommandNames {

    List<String> namespaces();

    /**
     * Resource names for a catalog list key such as {@code pod} or {@code namespace}.
     * An empty namespace means every namespace.
     */
    List<String> names(String listAs, String namespace);
}
