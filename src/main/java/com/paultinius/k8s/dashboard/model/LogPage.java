package com.paultinius.k8s.dashboard.model;

import java.util.List;

public record LogPage(List<LogLine> lines, boolean truncated) {
    public LogPage {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static LogPage empty() {
        return new LogPage(List.of(), false);
    }
}
