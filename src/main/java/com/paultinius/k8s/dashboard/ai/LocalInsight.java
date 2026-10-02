package com.paultinius.k8s.dashboard.ai;

import com.paultinius.k8s.dashboard.model.LogPage;
import com.paultinius.k8s.dashboard.model.ResourceDetail;

import java.util.Locale;

final class LocalInsight {

    String assess(String provider, String model, String question, ResourceDetail detail, LogPage logs) {
        StringBuilder text = new StringBuilder();
        text.append("No API key is configured, so this is a local check. ");
        text.append("Ask ").append(provider).append(" (").append(model).append(") by setting the server API key.\n\n");
        if (detail == null) {
            text.append("Select a pod, deployment, or other resource, then ask again.");
        } else {
            String status = detail.resource().status();
            String where = detail.resource().namespace().isBlank()
                    ? detail.resource().name()
                    : detail.resource().namespace() + "/" + detail.resource().name();
            text.append(detail.resource().kind()).append(' ').append(where).append(" is ").append(status).append(". ");
            String joined = logs.lines().stream().map(line -> line.text().toLowerCase(Locale.ROOT)).reduce("", (left, right) -> left + "\n" + right);
            if (joined.contains("connection refused")) {
                String line = logs.lines().stream()
                        .filter(item -> item.text().toLowerCase(Locale.ROOT).contains("connection refused"))
                        .map(item -> item.text())
                        .findFirst()
                        .orElse("connection refused");
                text.append("A log line reports ").append(line).append(". ");
                text.append("The process is restarting because that address is not accepting connections. ");
                text.append("A rollout restart will not help until the address answers.");
            } else if (status.toLowerCase(Locale.ROOT).contains("imagepull") || status.toLowerCase(Locale.ROOT).contains("errimage")) {
                text.append("The node could not pull the image. Check the image name and registry credentials.");
            } else if (status.toLowerCase(Locale.ROOT).contains("oom") || joined.contains("oomkilled")) {
                text.append("The container was killed for memory. Raise the memory limit or lower the workload.");
            } else if (status.toLowerCase(Locale.ROOT).contains("crash") || status.toLowerCase(Locale.ROOT).contains("error")) {
                text.append("Read the newest log lines for the container that is waiting.");
            } else if (detail.resource().desired() > detail.resource().ready()) {
                text.append("Ready count is ")
                        .append(detail.resource().ready())
                        .append("/")
                        .append(detail.resource().desired())
                        .append(". Open the pods that are not Running.");
            } else {
                text.append("Nothing in the status or the fetched logs shows an obvious failure.");
            }
        }
        if (question != null && !question.isBlank()) {
            text.append("\n\nYou asked: ").append(question.trim());
        }
        return text.toString();
    }
}
