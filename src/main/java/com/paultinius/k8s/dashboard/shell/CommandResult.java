package com.paultinius.k8s.dashboard.shell;

public record CommandResult(
        String command,
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        long durationMillis
) {
}
