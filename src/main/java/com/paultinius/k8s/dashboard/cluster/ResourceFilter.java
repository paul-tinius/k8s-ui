package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.model.ResourceView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ResourceFilter {

    private ResourceFilter() {
    }

    public static List<ResourceView> apply(
            List<ResourceView> items,
            String query,
            List<String> labels,
            String image,
            String node
    ) {
        String text = normalize(query);
        String imageQuery = normalize(image);
        String nodeQuery = normalize(node);
        List<LabelMatch> wanted = parseLabels(labels);
        if (text.isEmpty() && imageQuery.isEmpty() && nodeQuery.isEmpty() && wanted.isEmpty()) {
            return items;
        }
        List<ResourceView> matched = new ArrayList<>();
        for (ResourceView item : items) {
            if (matches(item, text, wanted, imageQuery, nodeQuery)) {
                matched.add(item);
            }
        }
        return List.copyOf(matched);
    }

    private static boolean matches(
            ResourceView item,
            String text,
            List<LabelMatch> labels,
            String image,
            String node
    ) {
        if (!node.isEmpty() && !normalize(item.node()).contains(node)) {
            return false;
        }
        if (!image.isEmpty() && item.images().stream().noneMatch(value -> normalize(value).contains(image))) {
            return false;
        }
        for (LabelMatch label : labels) {
            if (!label.matches(item.labels())) {
                return false;
            }
        }
        if (text.isEmpty()) {
            return true;
        }
        return haystack(item).contains(text);
    }

    private static String haystack(ResourceView item) {
        StringBuilder text = new StringBuilder();
        text.append(item.name()).append(' ')
                .append(item.namespace()).append(' ')
                .append(item.status()).append(' ')
                .append(item.summary()).append(' ')
                .append(item.node()).append(' ');
        item.images().forEach(image -> text.append(image).append(' '));
        item.labels().forEach((key, value) -> text.append(key).append('=').append(value).append(' '));
        item.attributes().values().forEach(value -> text.append(value).append(' '));
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static List<LabelMatch> parseLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return List.of();
        }
        List<LabelMatch> parsed = new ArrayList<>();
        for (String label : labels) {
            if (label == null || label.isBlank()) {
                continue;
            }
            int split = label.indexOf('=');
            if (split < 0) {
                parsed.add(new LabelMatch(label.trim(), null));
            } else {
                parsed.add(new LabelMatch(label.substring(0, split).trim(), label.substring(split + 1).trim()));
            }
        }
        return parsed;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record LabelMatch(String key, String value) {
        private boolean matches(Map<String, String> labels) {
            if (key.isEmpty()) {
                return labels.values().stream().anyMatch(candidate -> candidate.equals(value));
            }
            if (!labels.containsKey(key)) {
                return false;
            }
            return value == null || value.equals(labels.get(key));
        }
    }
}
