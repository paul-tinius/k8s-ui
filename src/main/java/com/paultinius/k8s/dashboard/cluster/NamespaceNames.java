package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.error.DashboardException;

import java.util.Set;

public final class NamespaceNames {

    static final int MAX_LENGTH = 63;

    private static final Set<String> PROTECTED = Set.of(
            "default",
            "kube-system",
            "kube-public",
            "kube-node-lease"
    );

    private NamespaceNames() {
    }

    public static String require(String raw) {
        if (raw == null || raw.isBlank()) {
            throw DashboardException.badRequest("Namespace name is required");
        }
        String name = raw.trim();
        if (!isDnsLabel(name)) {
            throw DashboardException.badRequest(
                    "Namespace name must be a DNS label: lowercase letters, digits, and hyphens, at most 63 characters"
            );
        }
        return name;
    }

    public static String requireCreatable(String raw) {
        String name = require(raw);
        if (!deletable(name)) {
            throw DashboardException.badRequest("Namespace " + name + " is reserved");
        }
        return name;
    }

    public static String requireDeletable(String raw) {
        String name = require(raw);
        if (!deletable(name)) {
            throw DashboardException.badRequest("Namespace " + name + " cannot be deleted");
        }
        return name;
    }

    public static boolean deletable(String name) {
        return name != null && !PROTECTED.contains(name);
    }

    private static boolean isDnsLabel(String name) {
        if (name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean letter = character >= 'a' && character <= 'z';
            boolean digit = character >= '0' && character <= '9';
            boolean hyphen = character == '-';
            if (!letter && !digit && !hyphen) {
                return false;
            }
            if (hyphen && (index == 0 || index == name.length() - 1)) {
                return false;
            }
        }
        return true;
    }
}
