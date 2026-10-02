package com.paultinius.k8s.dashboard.model;

public record AskResponse(boolean modelUsed, String provider, String model, String answer) {
}
