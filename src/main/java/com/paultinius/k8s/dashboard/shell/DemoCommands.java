package com.paultinius.k8s.dashboard.shell;

import com.paultinius.k8s.dashboard.cluster.DemoCluster;
import com.paultinius.k8s.dashboard.cluster.ResourceKind;
import com.paultinius.k8s.dashboard.error.DashboardException;
import com.paultinius.k8s.dashboard.model.ResourceView;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only kubectl and helm answers for the in-memory demo cluster.
 */
final class DemoCommands {

    private static final String LIVE = "Connect a live cluster to run other kubectl and helm commands. "
            + "The demo cluster answers kubectl get, describe, api-resources, api-versions, version, "
            + "cluster-info, and config current-context, plus helm version, list, and repo list.";

    private DemoCommands() {
    }

    static CommandResult execute(DemoCluster cluster, List<String> args) {
        long started = System.nanoTime();
        String rendered = CommandPolicy.render(args);
        try {
            Outcome outcome = run(cluster, args);
            return result(rendered, outcome, started);
        } catch (DashboardException exception) {
            return result(rendered, Outcome.error(exception.getMessage()), started);
        }
    }

    private static Outcome run(DemoCluster cluster, List<String> args) {
        Options options = Options.parse(args);
        if (options.help) {
            CommandCatalog.Walk walk = CommandCatalog.inspect(args);
            CommandCatalog.Spec spec = walk.spec();
            if (spec == null) {
                return Outcome.ok("kubectl <command> or helm <command>");
            }
            return Outcome.ok(spec.usage() + "\n" + spec.summary());
        }
        if ("helm".equals(args.get(0))) {
            return helm(options);
        }
        return kubectl(cluster, options);
    }

    private static Outcome kubectl(DemoCluster cluster, Options options) {
        String verb = options.verb();
        if (verb.isEmpty()) {
            return Outcome.ok("kubectl <command> [flags]\nControl the selected Kubernetes cluster");
        }
        return switch (verb) {
            case "get" -> get(cluster, options);
            case "describe" -> describe(cluster, options);
            case "api-resources" -> apiResources();
            case "api-versions" -> Outcome.ok("v1\napps/v1");
            case "version" -> Outcome.ok("""
                    Client Version: v1.0.0-demo
                    Kustomize Version: v0.0.0-demo
                    Server Version: v1.0.0-demo""");
            case "cluster-info" -> clusterInfo(options);
            case "config" -> config(options);
            default -> Outcome.error(LIVE);
        };
    }

    private static Outcome helm(Options options) {
        String verb = options.verb();
        return switch (verb) {
            case "" -> Outcome.ok("helm <command> [flags]\nInstall and inspect Helm charts on the selected cluster");
            case "version" -> Outcome.ok(
                    "version.BuildInfo{Version:\"v0.0.0-demo\", GitCommit:\"demo\", GitTreeState:\"clean\", GoVersion:\"go1.17\"}");
            case "list", "ls" -> Outcome.ok("NAME\tNAMESPACE\tREVISION\tUPDATED\tSTATUS\tCHART\tAPP VERSION");
            case "repo" -> "list".equals(options.argument(1))
                    ? Outcome.error("Error: no repositories to show")
                    : Outcome.error(LIVE);
            case "status" -> Outcome.error("Error: release: not found");
            default -> Outcome.error(LIVE);
        };
    }

    private static Outcome clusterInfo(Options options) {
        if ("dump".equals(options.argument(1))) {
            return Outcome.error(LIVE);
        }
        return Outcome.ok("Kubernetes control plane is running at demo://local");
    }

    private static Outcome config(Options options) {
        String action = options.argument(1);
        return switch (action) {
            case "" -> Outcome.ok("kubectl config current-context\nShow the current context name");
            case "current-context" -> Outcome.ok("demo");
            case "get-contexts" -> Outcome.ok(table(
                    List.of("CURRENT", "NAME", "CLUSTER", "AUTHINFO", "NAMESPACE"),
                    List.of(List.of("*", "demo", "demo", "demo", ""))));
            default -> Outcome.error(LIVE);
        };
    }

    private static Outcome apiResources() {
        return Outcome.ok(table(
                List.of("NAME", "SHORTNAMES", "NAMESPACED", "KIND"),
                List.of(
                        List.of("pods", "po", "true", "Pod"),
                        List.of("deployments", "deploy", "true", "Deployment"),
                        List.of("services", "svc", "true", "Service"),
                        List.of("configmaps", "cm", "true", "ConfigMap"),
                        List.of("nodes", "no", "false", "Node"),
                        List.of("events", "ev", "true", "Event"),
                        List.of("namespaces", "ns", "false", "Namespace"))));
    }

    private static Outcome get(DemoCluster cluster, Options options) {
        Query query = Query.from(options, "get");
        boolean showNamespace = options.namespace.isEmpty();
        List<String> sections = new ArrayList<>();
        for (String type : query.types) {
            List<ResourceView> views = select(cluster, type, options, query.names);
            if ("name".equals(options.output)) {
                for (ResourceView view : views) {
                    sections.add(view.kind().toLowerCase(Locale.ROOT) + "/" + view.name());
                }
                continue;
            }
            if ("yaml".equals(options.output)) {
                sections.add(yaml(cluster, views));
                continue;
            }
            sections.add(renderTable(views, showNamespace, "wide".equals(options.output)));
        }
        return Outcome.ok(String.join("\n", sections).stripTrailing());
    }

    private static Outcome describe(DemoCluster cluster, Options options) {
        Query query = Query.from(options, "describe");
        StringBuilder builder = new StringBuilder();
        for (String type : query.types) {
            for (ResourceView view : select(cluster, type, options, query.names)) {
                if (!builder.isEmpty()) {
                    builder.append("\n\n");
                }
                builder.append(describe(view));
            }
        }
        return Outcome.ok(builder.toString());
    }

    private static List<ResourceView> select(DemoCluster cluster, String type, Options options, List<String> names) {
        Set<String> scope = options.namespace.isEmpty() ? Set.of() : Set.of(options.namespace);
        List<ResourceView> views = new ArrayList<>(load(cluster, type, scope));
        if (!options.selector.isEmpty()) {
            views.removeIf(view -> !matches(view, options.selector));
        }
        if (!names.isEmpty()) {
            for (String name : names) {
                boolean found = views.stream().anyMatch(view -> view.name().equals(name));
                if (!found) {
                    throw DashboardException.badRequest(
                            "Error from server (NotFound): " + type + " \"" + name + "\" not found");
                }
            }
            views.removeIf(view -> !names.contains(view.name()));
        }
        views.sort(Comparator.comparing(ResourceView::namespace).thenComparing(ResourceView::name));
        return views;
    }

    private static List<ResourceView> load(DemoCluster cluster, String type, Set<String> scope) {
        if ("all".equalsIgnoreCase(type)) {
            List<ResourceView> all = new ArrayList<>();
            all.addAll(load(cluster, "pods", scope));
            all.addAll(load(cluster, "services", scope));
            all.addAll(load(cluster, "deployments", scope));
            all.addAll(load(cluster, "configmaps", scope));
            return all;
        }
        if (isNamespace(type)) {
            return cluster.namespaces().stream()
                    .filter(name -> scope.isEmpty() || scope.contains(name))
                    .sorted()
                    .map(name -> new ResourceView(
                            "Namespace", "", name, "Active", "", List.of(), Map.of(), "", 0, 0, "Active", Map.of()))
                    .toList();
        }
        try {
            ResourceKind.from(type);
        } catch (DashboardException exception) {
            throw DashboardException.badRequest("The demo cluster has no " + type
                    + " resources. It knows pods, deployments, services, configmaps, nodes, events, and namespaces");
        }
        return cluster.list(ResourceKind.from(type), scope);
    }

    private static boolean isNamespace(String type) {
        String key = type.toLowerCase(Locale.ROOT);
        return "namespace".equals(key) || "namespaces".equals(key) || "ns".equals(key);
    }

    private static boolean matches(ResourceView view, String selector) {
        for (String part : selector.split(",")) {
            String[] pieces = part.split("=", 2);
            if (pieces.length != 2 || pieces[0].isBlank()) {
                throw DashboardException.badRequest("The demo cluster supports label selectors like app=storefront");
            }
            String actual = view.labels().get(pieces[0].trim());
            if (actual == null || !actual.equals(pieces[1].trim())) {
                return false;
            }
        }
        return true;
    }

    private static String renderTable(List<ResourceView> views, boolean showNamespace, boolean wide) {
        if (views.isEmpty()) {
            return "No resources found";
        }
        String kind = views.get(0).kind();
        List<String> headers = new ArrayList<>();
        if (showNamespace && views.stream().anyMatch(view -> !view.namespace().isEmpty())) {
            headers.add("NAMESPACE");
        }
        headers.addAll(headersFor(kind, wide));
        List<List<String>> rows = new ArrayList<>();
        for (ResourceView view : views) {
            List<String> row = new ArrayList<>();
            if (headers.get(0).equals("NAMESPACE")) {
                row.add(view.namespace());
            }
            row.addAll(cells(view, wide));
            rows.add(row);
        }
        return table(headers, rows);
    }

    private static List<String> headersFor(String kind, boolean wide) {
        return switch (kind) {
            case "Pod" -> wide
                    ? List.of("NAME", "READY", "STATUS", "RESTARTS", "AGE", "NODE", "IMAGE")
                    : List.of("NAME", "READY", "STATUS", "RESTARTS", "AGE");
            case "Deployment" -> wide
                    ? List.of("NAME", "READY", "STATUS", "AGE", "IMAGE")
                    : List.of("NAME", "READY", "STATUS", "AGE");
            case "Service" -> List.of("NAME", "TYPE", "CLUSTER-IP", "PORTS", "AGE");
            case "ConfigMap" -> List.of("NAME", "DATA", "AGE");
            case "Node" -> wide
                    ? List.of("NAME", "STATUS", "ROLES", "AGE", "CPU", "MEMORY")
                    : List.of("NAME", "STATUS", "ROLES", "AGE");
            case "Event" -> List.of("NAME", "TYPE", "REASON", "OBJECT", "MESSAGE");
            case "Namespace" -> List.of("NAME", "STATUS", "AGE");
            default -> List.of("NAME", "STATUS", "AGE");
        };
    }

    private static List<String> cells(ResourceView view, boolean wide) {
        String ready = view.ready() + "/" + view.desired();
        String age = age(view.created());
        return switch (view.kind()) {
            case "Pod" -> {
                String restarts = view.attributes().getOrDefault("restarts", "0");
                if (wide) {
                    yield List.of(view.name(), ready, view.status(), restarts, age, view.node(), image(view));
                }
                yield List.of(view.name(), ready, view.status(), restarts, age);
            }
            case "Deployment" -> wide
                    ? List.of(view.name(), ready, view.status(), age, image(view))
                    : List.of(view.name(), ready, view.status(), age);
            case "Service" -> List.of(
                    view.name(),
                    view.attributes().getOrDefault("type", view.status()),
                    view.attributes().getOrDefault("clusterIP", ""),
                    view.attributes().getOrDefault("ports", ""),
                    age);
            case "ConfigMap" -> List.of(view.name(), view.summary(), age);
            case "Node" -> wide
                    ? List.of(
                            view.name(),
                            view.status(),
                            view.attributes().getOrDefault("roles", ""),
                            age,
                            view.attributes().getOrDefault("cpu", ""),
                            view.attributes().getOrDefault("memory", ""))
                    : List.of(view.name(), view.status(), view.attributes().getOrDefault("roles", ""), age);
            case "Event" -> List.of(
                    view.name(),
                    view.attributes().getOrDefault("type", view.status()),
                    view.attributes().getOrDefault("reason", ""),
                    view.attributes().getOrDefault("involved", ""),
                    view.attributes().getOrDefault("message", view.summary()));
            case "Namespace" -> List.of(view.name(), view.status(), age);
            default -> List.of(view.name(), view.status(), age);
        };
    }

    private static String yaml(DemoCluster cluster, List<ResourceView> views) {
        StringBuilder builder = new StringBuilder();
        for (ResourceView view : views) {
            if (!builder.isEmpty()) {
                builder.append("---\n");
            }
            if ("Namespace".equals(view.kind())) {
                builder.append("apiVersion: v1\nkind: Namespace\nmetadata:\n  name: ").append(view.name()).append('\n');
                continue;
            }
            try {
                builder.append(cluster.detail(ResourceKind.from(view.kind()), view.namespace(), view.name()).yaml());
            } catch (DashboardException exception) {
                builder.append("# ").append(exception.getMessage());
            }
            if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != '\n') {
                builder.append('\n');
            }
        }
        return builder.toString().stripTrailing();
    }

    private static String describe(ResourceView view) {
        StringBuilder builder = new StringBuilder();
        field(builder, "Name", view.name());
        if (!view.namespace().isEmpty()) {
            field(builder, "Namespace", view.namespace());
        }
        field(builder, "Kind", view.kind());
        field(builder, "Status", view.status());
        if ("Pod".equals(view.kind()) || "Deployment".equals(view.kind())) {
            field(builder, "Ready", view.ready() + "/" + view.desired());
        }
        if ("Pod".equals(view.kind()) && !view.node().isEmpty()) {
            field(builder, "Node", view.node());
        }
        if (!view.images().isEmpty()) {
            field(builder, "Image", image(view));
        }
        if (!view.labels().isEmpty()) {
            field(builder, "Labels", labels(view.labels()));
        }
        view.attributes().forEach((key, value) -> field(builder, key, value));
        if (!view.summary().isEmpty()) {
            field(builder, "Summary", view.summary());
        }
        field(builder, "Age", age(view.created()));
        return builder.toString().stripTrailing();
    }

    private static void field(StringBuilder builder, String label, String value) {
        builder.append(String.format(Locale.ROOT, "%-14s%s%n", label + ":", value == null ? "" : value));
    }

    private static String labels(Map<String, String> labels) {
        StringBuilder builder = new StringBuilder();
        labels.forEach((key, value) -> {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(key).append('=').append(value);
        });
        return builder.toString();
    }

    private static String image(ResourceView view) {
        return String.join(",", view.images());
    }

    static String age(String created) {
        if (created == null || created.isBlank()) {
            return "";
        }
        try {
            long minutes = Math.max(0, Duration.between(Instant.parse(created), Instant.now()).toMinutes());
            if (minutes < 60) {
                return minutes + "m";
            }
            long hours = minutes / 60;
            if (hours < 48) {
                return hours + "h";
            }
            return (hours / 24) + "d";
        } catch (RuntimeException exception) {
            return "";
        }
    }

    static String table(List<String> headers, List<List<String>> rows) {
        int[] widths = new int[headers.size()];
        for (int index = 0; index < headers.size(); index++) {
            widths[index] = headers.get(index).length();
        }
        for (List<String> row : rows) {
            for (int index = 0; index < row.size() && index < widths.length; index++) {
                widths[index] = Math.max(widths[index], row.get(index).length());
            }
        }
        StringBuilder builder = new StringBuilder();
        appendRow(builder, headers, widths);
        for (List<String> row : rows) {
            builder.append('\n');
            appendRow(builder, row, widths);
        }
        return builder.toString();
    }

    private static void appendRow(StringBuilder builder, List<String> cells, int[] widths) {
        for (int index = 0; index < cells.size() && index < widths.length; index++) {
            if (index > 0) {
                builder.append("  ");
            }
            String cell = cells.get(index);
            builder.append(cell);
            if (index + 1 < cells.size()) {
                builder.append(" ".repeat(Math.max(0, widths[index] - cell.length())));
            }
        }
    }

    private static CommandResult result(String command, Outcome outcome, long started) {
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new CommandResult(command, outcome.code, outcome.stdout, outcome.stderr, false, millis);
    }

    private record Outcome(int code, String stdout, String stderr) {
        private static Outcome ok(String stdout) {
            return new Outcome(0, stdout, "");
        }

        private static Outcome error(String stderr) {
            return new Outcome(1, "", stderr);
        }
    }

    private record Query(List<String> types, List<String> names) {
        private static Query from(Options options, String verb) {
            if (options.positionals.size() < 2) {
                throw DashboardException.badRequest("You must specify the type of resource to " + verb);
            }
            String typeToken = options.positionals.get(1);
            List<String> names = new ArrayList<>();
            List<String> types = new ArrayList<>();
            if (typeToken.contains("/") && options.positionals.size() == 2) {
                String[] parts = typeToken.split("/", 2);
                types.add(parts[0]);
                if (!parts[1].isBlank()) {
                    names.add(parts[1]);
                }
            } else {
                for (String part : typeToken.split(",")) {
                    if (!part.isBlank()) {
                        types.add(part.trim());
                    }
                }
                names.addAll(options.positionals.subList(2, options.positionals.size()));
            }
            if (types.isEmpty()) {
                throw DashboardException.badRequest("You must specify the type of resource to " + verb);
            }
            if (!options.output.isEmpty() && !"wide".equals(options.output)
                    && !"name".equals(options.output) && !"yaml".equals(options.output)) {
                throw DashboardException.badRequest(
                        "The demo cluster prints tables, name, and yaml. " + options.output + " needs a live cluster");
            }
            return new Query(types, names);
        }
    }

    private record Options(String namespace, String output, String selector, boolean help, List<String> positionals) {
        private static Options parse(List<String> args) {
            String namespace = "";
            String output = "";
            String selector = "";
            boolean help = false;
            List<String> positionals = new ArrayList<>();
            for (int index = 1; index < args.size(); index++) {
                String token = args.get(index);
                if ("--".equals(token)) {
                    positionals.addAll(args.subList(index + 1, args.size()));
                    break;
                }
                if (token.startsWith("-") && token.length() > 1) {
                    String name = token;
                    String attached = null;
                    int equals = token.indexOf('=');
                    if (equals >= 0) {
                        name = token.substring(0, equals);
                        attached = token.substring(equals + 1);
                    }
                    switch (name) {
                        case "-n", "--namespace" -> namespace = attached != null ? attached : value(args, ++index);
                        case "-o", "--output" -> output = attached != null ? attached : value(args, ++index);
                        case "-l", "--selector" -> selector = attached != null ? attached : value(args, ++index);
                        case "-h", "--help" -> help = true;
                        case "-A", "--all-namespaces", "-w", "--watch" -> {
                            // All-namespaces is the demo default. A watch prints one table.
                        }
                        default -> {
                            if (attached == null && CommandCatalog.takesValue(name)
                                    && index + 1 < args.size() && !args.get(index + 1).startsWith("-")) {
                                index++;
                            }
                        }
                    }
                    continue;
                }
                positionals.add(token);
            }
            return new Options(namespace, output, selector, help, List.copyOf(positionals));
        }

        private String verb() {
            return positionals.isEmpty() ? "" : positionals.get(0);
        }

        private String argument(int index) {
            return index < positionals.size() ? positionals.get(index) : "";
        }

        private static String value(List<String> args, int index) {
            if (index >= args.size() || args.get(index).startsWith("-")) {
                throw DashboardException.badRequest("A flag is missing its value");
            }
            return args.get(index);
        }
    }
}
