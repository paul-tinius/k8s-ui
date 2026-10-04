package com.paultinius.k8s.dashboard.shell;

import com.paultinius.k8s.dashboard.error.DashboardException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Suggests the next kubectl or helm token and the hint for what the user has typed.
 */
final class CommandCompleter {

    private static final int LIMIT = 24;
    private static final String INTRO = "Run kubectl or helm on the selected cluster. Tab completes. Enter runs.";

    private CommandCompleter() {
    }

    static Completion complete(String line, int cursor, String namespace, CommandNames names) {
        String source = line == null ? "" : line;
        int at = Math.max(0, Math.min(cursor, source.length()));
        String left = source.substring(0, at);
        List<CommandLineParser.Token> tokens;
        try {
            tokens = CommandLineParser.parse(left);
        } catch (DashboardException exception) {
            return new Completion(exception.getMessage(), "", List.of(), at, at);
        }
        boolean fresh = left.isEmpty() || Character.isWhitespace(left.charAt(left.length() - 1));
        String prefix;
        int replaceFrom;
        int replaceTo;
        List<String> prior;
        if (fresh) {
            prefix = "";
            replaceFrom = at;
            replaceTo = at;
            prior = texts(tokens);
        } else {
            CommandLineParser.Token current = tokens.get(tokens.size() - 1);
            prefix = current.text();
            replaceFrom = current.start();
            replaceTo = tokenEnd(source, current.start(), at);
            prior = texts(tokens.subList(0, tokens.size() - 1));
        }
        return suggest(prior, prefix, replaceFrom, replaceTo, namespace == null ? "" : namespace, names);
    }

    private static Completion suggest(
            List<String> prior,
            String prefix,
            int replaceFrom,
            int replaceTo,
            String namespace,
            CommandNames names
    ) {
        if (!prefix.isEmpty() && prefix.startsWith("-") && prefix.contains("=")) {
            int equals = prefix.indexOf('=');
            String flag = prefix.substring(0, equals);
            String valuePrefix = prefix.substring(equals + 1);
            int valueFrom = replaceFrom + equals + 1;
            return values(flag, valuePrefix, valueFrom, replaceTo, prior, namespace, names, parentHint(prior), parentUsage(prior));
        }
        if (!prior.isEmpty()) {
            String previous = prior.get(prior.size() - 1);
            if (CommandCatalog.takesValue(previous) && !previous.contains("=") && !prefix.startsWith("-")) {
                List<String> earlier = prior.subList(0, prior.size() - 1);
                return values(previous, prefix, replaceFrom, replaceTo, earlier, namespace, names, parentHint(earlier), parentUsage(earlier));
            }
        }
        if (prior.isEmpty()) {
            List<Completion.Suggestion> tools = new ArrayList<>();
            for (CommandCatalog.Spec tool : CommandCatalog.tools()) {
                if (startsWith(tool.name(), prefix)) {
                    tools.add(new Completion.Suggestion(tool.name(), tool.summary(), "command"));
                }
            }
            CommandCatalog.Spec only = tools.size() == 1 ? CommandCatalog.tool(tools.get(0).value()) : null;
            String hint = only == null ? (prefix.isEmpty() ? INTRO : "Commands start with kubectl or helm.") : only.summary();
            String usage = only == null ? "kubectl <command>  or  helm <command>" : only.usage();
            return new Completion(hint, usage, limit(tools), replaceFrom, replaceTo);
        }
        String toolName = CommandCatalog.canonicalTool(prior.get(0));
        if (toolName.isEmpty()) {
            return new Completion(
                    "Commands start with kubectl or helm.",
                    "kubectl <command>  or  helm <command>",
                    List.of(),
                    replaceFrom,
                    replaceTo);
        }
        if (prefix.startsWith("-")) {
            return flags(prior, prefix, replaceFrom, replaceTo);
        }
        CommandCatalog.Walk walk = CommandCatalog.inspect(prior);
        CommandCatalog.Spec spec = walk.spec() == null ? CommandCatalog.tool(toolName) : walk.spec();
        List<Completion.Suggestion> suggestions = new ArrayList<>();
        if (!walk.resource().isEmpty() && spec.names()) {
            suggestions.addAll(nameSuggestions(spec, walk.resource(), prefix, namespaceScope(prior, namespace), names));
        } else if (walk.resource().isEmpty() && !spec.listAs().isEmpty() && spec.names() && spec.child(prefix) == null) {
            suggestions.addAll(nameSuggestions(spec, spec.listAs(), prefix, namespaceScope(prior, namespace), names));
        }
        if (walk.resource().isEmpty()) {
            for (CommandCatalog.Spec child : spec.children()) {
                if (startsWith(child.name(), prefix) || aliasStartsWith(child, prefix)) {
                    suggestions.add(new Completion.Suggestion(child.name(), child.summary(), "command"));
                }
            }
            if (spec.resources()) {
                for (CommandCatalog.Resource resource : CommandCatalog.resources()) {
                    if (startsWith(resource.name(), prefix) || aliasStartsWith(resource, prefix)) {
                        suggestions.add(new Completion.Suggestion(resource.name(), resource.detail(), "resource"));
                    }
                }
            }
        }
        if (suggestions.size() == 1 && "command".equals(suggestions.get(0).kind())) {
            CommandCatalog.Spec match = spec.child(suggestions.get(0).value());
            if (match == null) {
                match = CommandCatalog.tool(suggestions.get(0).value());
            }
            if (match != null) {
                return new Completion(match.summary(), match.usage(), suggestions, replaceFrom, replaceTo);
            }
        }
        String hint = spec.summary();
        if (suggestions.isEmpty() && !prefix.isEmpty()) {
            hint = "No " + toolName + " completion matches \"" + prefix + "\".";
        }
        return new Completion(hint, spec.usage(), limit(suggestions), replaceFrom, replaceTo);
    }

    private static Completion flags(List<String> prior, String prefix, int replaceFrom, int replaceTo) {
        CommandCatalog.Walk walk = CommandCatalog.inspect(prior);
        CommandCatalog.Spec spec = walk.spec();
        String toolName = CommandCatalog.canonicalTool(prior.get(0));
        List<Completion.Suggestion> suggestions = new ArrayList<>();
        addFlags(suggestions, spec == null ? List.of() : spec.flags(), prefix);
        addFlags(suggestions, "helm".equals(toolName) ? CommandCatalog.HELM_FLAGS : CommandCatalog.KUBECTL_FLAGS, prefix);
        String hint = spec == null ? INTRO : spec.summary();
        String usage = spec == null ? "" : spec.usage();
        if (suggestions.size() == 1) {
            hint = suggestions.get(0).detail();
        }
        return new Completion(hint, usage, limit(suggestions), replaceFrom, replaceTo);
    }

    private static Completion values(
            String flag,
            String prefix,
            int replaceFrom,
            int replaceTo,
            List<String> prior,
            String namespace,
            CommandNames names,
            String fallbackHint,
            String usage
    ) {
        String name = flag;
        int equals = name.indexOf('=');
        if (equals >= 0) {
            name = name.substring(0, equals);
        }
        List<Completion.Suggestion> suggestions = new ArrayList<>();
        String hint = fallbackHint;
        if ("-n".equals(name) || "--namespace".equals(name)) {
            hint = "Namespace to use";
            for (String candidate : safe(names.namespaces())) {
                if (startsWith(candidate, prefix)) {
                    suggestions.add(new Completion.Suggestion(candidate, "Namespace", "value"));
                }
            }
        } else if ("-o".equals(name) || "--output".equals(name)) {
            hint = "Output format";
            for (String candidate : CommandCatalog.outputValues()) {
                if (startsWith(candidate, prefix)) {
                    suggestions.add(new Completion.Suggestion(candidate, "Output format", "value"));
                }
            }
        } else if ("--dry-run".equals(name)) {
            hint = "Dry run mode";
            for (String candidate : CommandCatalog.dryRunValues()) {
                if (startsWith(candidate, prefix)) {
                    suggestions.add(new Completion.Suggestion(candidate, "Dry run mode", "value"));
                }
            }
        } else if ("-l".equals(name) || "--selector".equals(name)) {
            hint = "Label selector, for example app=storefront";
        } else if ("-c".equals(name) || "--container".equals(name)) {
            hint = "Container name";
        } else if ("--replicas".equals(name)) {
            hint = "Replica count";
        } else if ("--tail".equals(name)) {
            hint = "Number of log lines, for example 100";
        }
        return new Completion(hint, usage, limit(suggestions), replaceFrom, replaceTo);
    }

    private static String parentUsage(List<String> prior) {
        if (prior.isEmpty()) {
            return "kubectl <command>  or  helm <command>";
        }
        CommandCatalog.Walk walk = CommandCatalog.inspect(prior);
        if (walk.spec() != null) {
            return walk.spec().usage();
        }
        CommandCatalog.Spec tool = CommandCatalog.tool(prior.get(0));
        return tool == null ? "" : tool.usage();
    }

    private static List<Completion.Suggestion> nameSuggestions(
            CommandCatalog.Spec spec,
            String resourceToken,
            String prefix,
            String namespace,
            CommandNames names
    ) {
        String listAs = spec.listAs();
        if (listAs == null || listAs.isEmpty()) {
            CommandCatalog.Resource resource = CommandCatalog.resource(resourceToken);
            listAs = resource == null ? "" : resource.listAs();
        }
        if (listAs == null || listAs.isEmpty()) {
            return List.of();
        }
        String kind = spec.listAs() != null && !spec.listAs().isEmpty()
                ? spec.name()
                : resourceToken;
        List<Completion.Suggestion> suggestions = new ArrayList<>();
        for (String candidate : safe(names.names(listAs, namespace))) {
            if (startsWith(candidate, prefix)) {
                suggestions.add(new Completion.Suggestion(candidate, "Name for " + kind, "name"));
            }
        }
        return suggestions;
    }

    private static String namespaceScope(List<String> args, String fallback) {
        for (int index = 0; index < args.size(); index++) {
            String arg = args.get(index);
            if ("-A".equals(arg) || "--all-namespaces".equals(arg)) {
                return "";
            }
            if ("--namespace".equals(arg) || "-n".equals(arg)) {
                if (index + 1 < args.size()) {
                    return args.get(index + 1);
                }
                return fallback;
            }
            if (arg.startsWith("--namespace=")) {
                return arg.substring("--namespace=".length());
            }
            if (arg.startsWith("-n=")) {
                return arg.substring("-n=".length());
            }
        }
        return fallback == null ? "" : fallback;
    }

    private static String parentHint(List<String> prior) {
        if (prior.isEmpty()) {
            return INTRO;
        }
        CommandCatalog.Walk walk = CommandCatalog.inspect(prior);
        if (walk.spec() != null) {
            return walk.spec().summary();
        }
        CommandCatalog.Spec tool = CommandCatalog.tool(prior.get(0));
        return tool == null ? INTRO : tool.summary();
    }

    private static void addFlags(List<Completion.Suggestion> suggestions, List<CommandCatalog.Flag> flags, String prefix) {
        boolean longs = prefix.startsWith("--");
        for (CommandCatalog.Flag flag : flags) {
            if (longs && !flag.name().startsWith("--")) {
                continue;
            }
            if (!startsWith(flag.name(), prefix)) {
                continue;
            }
            boolean seen = false;
            for (Completion.Suggestion suggestion : suggestions) {
                if (suggestion.value().equals(flag.name())) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                suggestions.add(new Completion.Suggestion(flag.name(), flag.detail(), "flag"));
            }
        }
    }

    private static int tokenEnd(String line, int start, int cursor) {
        try {
            for (CommandLineParser.Token token : CommandLineParser.parse(line)) {
                if (token.start() == start) {
                    return token.end();
                }
            }
        } catch (DashboardException ignored) {
            return cursor;
        }
        return cursor;
    }

    private static List<String> texts(List<CommandLineParser.Token> tokens) {
        List<String> texts = new ArrayList<>();
        for (CommandLineParser.Token token : tokens) {
            texts.add(token.text());
        }
        return texts;
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static boolean startsWith(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    private static boolean aliasStartsWith(CommandCatalog.Spec spec, String prefix) {
        for (String alias : spec.aliases()) {
            if (startsWith(alias, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean aliasStartsWith(CommandCatalog.Resource resource, String prefix) {
        for (String alias : resource.aliases()) {
            if (startsWith(alias, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<Completion.Suggestion> limit(List<Completion.Suggestion> suggestions) {
        if (suggestions.size() <= LIMIT) {
            return List.copyOf(suggestions);
        }
        return List.copyOf(suggestions.subList(0, LIMIT));
    }
}
