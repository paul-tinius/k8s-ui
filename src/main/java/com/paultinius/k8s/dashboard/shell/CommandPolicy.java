package com.paultinius.k8s.dashboard.shell;

import com.paultinius.k8s.dashboard.error.DashboardException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a typed line into a kubectl or helm argument list for the selected cluster.
 */
final class CommandPolicy {

    private static final Set<String> CLUSTER_FLAGS = Set.of(
            "--kubeconfig",
            "--context",
            "--kube-context"
    );

    private CommandPolicy() {
    }

    static List<String> prepare(String line, String namespace) {
        List<CommandLineParser.Token> tokens = CommandLineParser.parse(line);
        if (tokens.isEmpty()) {
            throw DashboardException.badRequest("Enter a kubectl or helm command");
        }
        List<String> args = new ArrayList<>();
        for (CommandLineParser.Token token : tokens) {
            args.add(token.text());
        }
        String tool = CommandCatalog.canonicalTool(args.get(0));
        if (tool.isEmpty()) {
            throw DashboardException.badRequest("Run kubectl or helm by name. Paths and other programs are not accepted");
        }
        args.set(0, tool);
        rejectClusterFlags(args);
        if (shouldInjectNamespace(args, namespace)) {
            String chosen = namespace.trim();
            if (chosen.startsWith("-") || chosen.indexOf(' ') >= 0 || chosen.indexOf('\t') >= 0) {
                throw DashboardException.badRequest("Namespace is invalid");
            }
            args.add("-n");
            args.add(chosen);
        }
        return List.copyOf(args);
    }

    static String render(List<String> args) {
        StringBuilder builder = new StringBuilder();
        for (String arg : args) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(CommandLineParser.quote(arg));
        }
        return builder.toString();
    }

    private static void rejectClusterFlags(List<String> args) {
        for (String arg : args) {
            if ("--".equals(arg)) {
                return;
            }
            String name = arg;
            int equals = arg.indexOf('=');
            if (equals >= 0) {
                name = arg.substring(0, equals);
            }
            if (CLUSTER_FLAGS.contains(name.toLowerCase(Locale.ROOT))) {
                throw DashboardException.badRequest(
                        "The dashboard runs this command on the selected cluster. Omit --kubeconfig and --context");
            }
        }
    }

    private static boolean shouldInjectNamespace(List<String> args, String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return false;
        }
        if (hasHelp(args) || hasAllNamespaces(args) || hasNamespace(args)) {
            return false;
        }
        CommandCatalog.Walk walk = CommandCatalog.inspect(args);
        return walk.spec() != null && walk.spec().namespaced() && !walk.clusterScoped();
    }

    private static boolean hasHelp(List<String> args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAllNamespaces(List<String> args) {
        for (String arg : args) {
            if ("-A".equals(arg) || "--all-namespaces".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNamespace(List<String> args) {
        for (int index = 0; index < args.size(); index++) {
            String arg = args.get(index);
            if ("-n".equals(arg) || "--namespace".equals(arg)) {
                return true;
            }
            if (arg.startsWith("--namespace=") || arg.startsWith("-n=")) {
                return true;
            }
        }
        return false;
    }
}
