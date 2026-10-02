package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.error.DashboardException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class KubeconfigCatalog {

    private KubeconfigCatalog() {
    }

    public record ContextRef(String name, String server, String namespace) {
    }

    public static List<ContextRef> read(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw DashboardException.badRequest("Kubeconfig is empty");
        }
        Map<String, Object> root = YamlMaps.object(YamlMaps.load(yaml));
        if (root.isEmpty()) {
            throw DashboardException.badRequest("Kubeconfig is empty");
        }
        Map<String, String> servers = new LinkedHashMap<>();
        for (Map<String, Object> entry : YamlMaps.children(root.get("clusters"))) {
            String name = YamlMaps.text(entry, "name");
            String server = YamlMaps.text(YamlMaps.child(entry, "cluster"), "server");
            if (!name.isEmpty() && !server.isEmpty()) {
                servers.put(name, server);
            }
        }
        List<ContextRef> contexts = new ArrayList<>();
        for (Map<String, Object> entry : YamlMaps.children(root.get("contexts"))) {
            String name = YamlMaps.text(entry, "name");
            Map<String, Object> context = YamlMaps.child(entry, "context");
            String cluster = YamlMaps.text(context, "cluster");
            String server = servers.get(cluster);
            if (name.isEmpty() || server == null) {
                throw DashboardException.badRequest("Context " + name + " does not name a cluster with a server");
            }
            contexts.add(new ContextRef(name, server, YamlMaps.text(context, "namespace")));
        }
        if (contexts.isEmpty()) {
            throw DashboardException.badRequest("Kubeconfig has no contexts");
        }
        return List.copyOf(contexts);
    }

    public static String isolate(String yaml, String contextName) {
        Map<String, Object> root = YamlMaps.object(YamlMaps.load(yaml));
        Map<String, Object> selected = null;
        for (Map<String, Object> entry : YamlMaps.children(root.get("contexts"))) {
            if (contextName.equals(YamlMaps.text(entry, "name"))) {
                selected = entry;
                break;
            }
        }
        if (selected == null) {
            throw DashboardException.badRequest("Context " + contextName + " was not found");
        }
        Map<String, Object> context = YamlMaps.child(selected, "context");
        String clusterName = YamlMaps.text(context, "cluster");
        String userName = YamlMaps.text(context, "user");
        List<Map<String, Object>> clusters = YamlMaps.children(root.get("clusters")).stream()
                .filter(entry -> clusterName.equals(YamlMaps.text(entry, "name")))
                .toList();
        List<Map<String, Object>> users = YamlMaps.children(root.get("users")).stream()
                .filter(entry -> userName.equals(YamlMaps.text(entry, "name")))
                .toList();
        Map<String, Object> isolated = new LinkedHashMap<>();
        isolated.put("apiVersion", "v1");
        isolated.put("kind", "Config");
        isolated.put("current-context", contextName);
        isolated.put("contexts", List.of(selected));
        isolated.put("clusters", clusters);
        isolated.put("users", users);
        return YamlMaps.dump(isolated);
    }

    public static String idFor(String contextName) {
        String cleaned = contextName == null ? "" : contextName.toLowerCase(Locale.ROOT);
        cleaned = cleaned.replaceAll("[^a-z0-9._-]", "-").replaceAll("-{2,}", "-");
        cleaned = cleaned.replaceAll("^-|-$", "");
        if (cleaned.isBlank()) {
            cleaned = "cluster";
        }
        if (cleaned.length() > 64) {
            cleaned = cleaned.substring(0, 64);
        }
        if ("demo".equals(cleaned) || "in-cluster".equals(cleaned)) {
            cleaned = "kube-" + cleaned;
        }
        return cleaned;
    }
}
