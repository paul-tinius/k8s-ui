package com.paultinius.k8s.dashboard.shell;

import com.paultinius.k8s.dashboard.error.DashboardException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits one command line into arguments. It does not start a shell.
 */
final class CommandLineParser {

    static final int MAX_LENGTH = 4_000;
    static final int MAX_TOKENS = 64;

    private static final Set<Character> META = Set.of('|', '&', ';', '<', '>', '`', '$', '(', ')');

    private CommandLineParser() {
    }

    record Token(String text, int start, int end) {
    }

    static List<Token> parse(String line) {
        String source = line == null ? "" : line;
        if (source.length() > MAX_LENGTH) {
            throw DashboardException.badRequest("The command is too long");
        }
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\n' || current == '\r' || current == '\0') {
                throw shell();
            }
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            int start = index;
            StringBuilder text = new StringBuilder();
            boolean single = false;
            boolean doubled = false;
            while (index < source.length()) {
                char symbol = source.charAt(index);
                if (symbol == '\n' || symbol == '\r' || symbol == '\0') {
                    throw shell();
                }
                if (single) {
                    if (symbol == '\'') {
                        single = false;
                    } else {
                        text.append(symbol);
                    }
                    index++;
                    continue;
                }
                if (symbol == '\\' && !doubled) {
                    if (index + 1 >= source.length()) {
                        throw DashboardException.badRequest("A trailing backslash is not closed");
                    }
                    text.append(source.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (symbol == '\\' && doubled) {
                    if (index + 1 < source.length()) {
                        char next = source.charAt(index + 1);
                        if (next == '"' || next == '\\' || next == '$' || next == '`') {
                            text.append(next);
                            index += 2;
                            continue;
                        }
                    }
                    text.append(symbol);
                    index++;
                    continue;
                }
                if (symbol == '"' && !single) {
                    doubled = !doubled;
                    index++;
                    continue;
                }
                if (symbol == '\'' && !doubled) {
                    single = true;
                    index++;
                    continue;
                }
                if (!doubled && Character.isWhitespace(symbol)) {
                    break;
                }
                if (!doubled && META.contains(symbol)) {
                    throw shell();
                }
                text.append(symbol);
                index++;
            }
            if (single || doubled) {
                throw DashboardException.badRequest("A quote is not closed");
            }
            if (text.length() > 2_000) {
                throw DashboardException.badRequest("An argument is too long");
            }
            tokens.add(new Token(text.toString(), start, index));
            if (tokens.size() > MAX_TOKENS) {
                throw DashboardException.badRequest("The command has too many arguments");
            }
        }
        return List.copyOf(tokens);
    }

    static String quote(String token) {
        if (token == null || token.isEmpty()) {
            return "''";
        }
        for (int index = 0; index < token.length(); index++) {
            char symbol = token.charAt(index);
            boolean safe = symbol >= 'a' && symbol <= 'z'
                    || symbol >= 'A' && symbol <= 'Z'
                    || symbol >= '0' && symbol <= '9'
                    || symbol == '_' || symbol == '.' || symbol == ',' || symbol == ':'
                    || symbol == '/' || symbol == '=' || symbol == '@' || symbol == '+'
                    || symbol == '-';
            if (!safe) {
                return "'" + token.replace("'", "'\\''") + "'";
            }
        }
        return token;
    }

    private static DashboardException shell() {
        return DashboardException.badRequest(
                "Pipes, redirects, and shell operators are not supported. Run one kubectl or helm command");
    }
}
