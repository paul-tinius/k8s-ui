package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.error.DashboardException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class YamlMaps {

    private YamlMaps() {
    }

    public static Object load(String yaml) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(1_000_000);
            return new Yaml(options).load(yaml);
        } catch (RuntimeException exception) {
            throw DashboardException.badRequest("YAML could not be parsed");
        }
    }

    public static String dump(Map<String, Object> document) {
        return new Yaml().dump(document);
    }

    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }

    public static Map<String, Object> child(Map<String, Object> map, String key) {
        return object(map.get(key));
    }

    public static List<Map<String, Object>> children(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Object item : raw) {
            copy.add(object(item));
        }
        return copy;
    }

    public static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
