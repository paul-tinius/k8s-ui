package com.paultinius.k8s.dashboard.model;

public record LogLine(String namespace, String pod, String container, String text) {
}
