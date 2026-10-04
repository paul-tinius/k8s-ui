package com.paultinius.k8s.dashboard.shell;

import java.util.List;

public record Completion(
        String hint,
        String usage,
        List<Suggestion> suggestions,
        int replaceFrom,
        int replaceTo
) {
    public Completion {
        hint = hint == null ? "" : hint;
        usage = usage == null ? "" : usage;
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public record Suggestion(String value, String detail, String kind) {
        public Suggestion {
            value = value == null ? "" : value;
            detail = detail == null ? "" : detail;
            kind = kind == null ? "" : kind;
        }
    }
}
