package com.paultinius.k8s.dashboard.model;

public record NamespaceView(String name, String status, boolean deletable) {
}
