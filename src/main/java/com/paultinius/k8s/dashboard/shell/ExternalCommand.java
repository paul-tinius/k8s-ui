package com.paultinius.k8s.dashboard.shell;

import com.paultinius.k8s.dashboard.error.DashboardException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs kubectl or helm as a process. Arguments are passed directly, never through a shell.
 */
final class ExternalCommand {

    static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_OUTPUT = 200_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final List<String> KEPT_ENV = List.of(
            "PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE",
            "TMPDIR", "TMP", "TEMP", "SystemRoot", "PATHEXT"
    );

    private final BinaryLocator binaries;
    private final Duration timeout;
    private final Map<String, Handle> running = new ConcurrentHashMap<>();

    ExternalCommand() {
        this(BinaryLocator.fromPath(), TIMEOUT);
    }

    ExternalCommand(BinaryLocator binaries, Duration timeout) {
        this.binaries = binaries;
        this.timeout = timeout;
    }

    CommandResult run(String id, List<String> args, String kubeconfigYaml, boolean inCluster) {
        long started = System.nanoTime();
        String rendered = CommandPolicy.render(args);
        if (args.isEmpty()) {
            return finish(rendered, 1, "", "Enter a kubectl or helm command", false, started);
        }
        if (id != null && !id.isBlank() && !ID.matcher(id).matches()) {
            throw DashboardException.badRequest("Command id is invalid");
        }
        String tool = args.get(0);
        Optional<Path> binary = binaries.find(tool);
        if (binary.isEmpty()) {
            return finish(rendered, 127, "", tool + " was not found on PATH.", false, started);
        }
        if (!inCluster && (kubeconfigYaml == null || kubeconfigYaml.isBlank())) {
            return finish(rendered, 1, "", "This cluster has no kubeconfig to run " + tool + " against.", false, started);
        }
        Path kubeconfig = null;
        try {
            Map<String, String> environment = inCluster ? inheritedEnvironment() : scrubbedEnvironment();
            if (inCluster) {
                environment.remove("KUBECONFIG");
            } else {
                kubeconfig = writeKubeconfig(kubeconfigYaml);
                environment.put("KUBECONFIG", kubeconfig.toString());
            }
            environment.put("TERM", "dumb");
            environment.putIfAbsent("HOME", System.getProperty("user.home", ""));
            List<String> command = new ArrayList<>();
            command.add(binary.get().toString());
            command.addAll(args.subList(1, args.size()));
            return execute(id, rendered, command, environment, workingDirectory(), started);
        } catch (IOException exception) {
            return finish(rendered, 1, "", "Could not prepare " + tool + ".", false, started);
        } finally {
            delete(kubeconfig);
        }
    }

    void cancel(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw DashboardException.badRequest("Command id is invalid");
        }
        Handle handle = running.get(id);
        if (handle != null) {
            handle.cancel();
        }
    }

    private CommandResult execute(
            String id,
            String rendered,
            List<String> command,
            Map<String, String> environment,
            Path directory,
            long started
    ) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(false);
        Map<String, String> processEnv = builder.environment();
        processEnv.clear();
        processEnv.putAll(environment);
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            return finish(rendered, 127, "", "Could not start the command.", false, started);
        }
        Handle handle = new Handle(process);
        if (id != null && !id.isBlank()) {
            Handle previous = running.put(id, handle);
            if (previous != null) {
                previous.cancel();
            }
        }
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outThread = drain(process.getInputStream(), stdout);
        Thread errThread = drain(process.getErrorStream(), stderr);
        boolean timedOut = false;
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            handle.cancel();
            Thread.currentThread().interrupt();
            appendLine(stderr, "Stopped.");
            join(outThread, errThread);
            return finish(rendered, 130, stdout.toString(), stderr.toString(), false, started);
        } finally {
            if (id != null && !id.isBlank()) {
                running.remove(id, handle);
            }
        }
        join(outThread, errThread);
        if (handle.cancelled) {
            appendLine(stderr, "Stopped.");
            int code = exitCode(process);
            return finish(rendered, code == 0 ? 130 : code, stdout.toString(), stderr.toString(), false, started);
        }
        if (timedOut) {
            appendLine(stderr, "Timed out after " + timeout.toSeconds() + " seconds.");
            return finish(rendered, 124, stdout.toString(), stderr.toString(), true, started);
        }
        return finish(rendered, exitCode(process), stdout.toString(), stderr.toString(), false, started);
    }

    private static Map<String, String> scrubbedEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String key : KEPT_ENV) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                environment.put(key, value);
            }
        }
        return environment;
    }

    private static Map<String, String> inheritedEnvironment() {
        return new LinkedHashMap<>(System.getenv());
    }

    private static Path writeKubeconfig(String yaml) throws IOException {
        Path file = Files.createTempFile("k8s-dashboard-", ".kubeconfig");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows has no POSIX modes.
        }
        return file;
    }

    private static Path workingDirectory() {
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            Path path = Path.of(home);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    private static Thread drain(InputStream stream, StringBuilder into) {
        Thread thread = new Thread(() -> {
            try (stream) {
                byte[] buffer = new byte[4096];
                int read;
                boolean truncated = false;
                while ((read = stream.read(buffer)) >= 0) {
                    if (into.length() >= MAX_OUTPUT) {
                        truncated = true;
                        continue;
                    }
                    int room = MAX_OUTPUT - into.length();
                    into.append(new String(buffer, 0, Math.min(read, room), StandardCharsets.UTF_8));
                    if (read > room) {
                        truncated = true;
                    }
                }
                if (truncated) {
                    into.append("\n… output truncated\n");
                }
            } catch (IOException ignored) {
                // The process closed the stream.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread outThread, Thread errThread) {
        try {
            outThread.join(2_000);
            errThread.join(2_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static int exitCode(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException exception) {
            return 1;
        }
    }

    private static void appendLine(StringBuilder builder, String line) {
        if (builder.indexOf(line) >= 0) {
            return;
        }
        if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
        builder.append(line);
    }

    private static void delete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The next command writes a new file.
        }
    }

    private static CommandResult finish(
            String command,
            int exitCode,
            String stdout,
            String stderr,
            boolean timedOut,
            long started
    ) {
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new CommandResult(command, exitCode, stdout, stderr, timedOut, millis);
    }

    private static final class Handle {
        private final Process process;
        private volatile boolean cancelled;

        private Handle(Process process) {
            this.process = process;
        }

        private void cancel() {
            cancelled = true;
            process.destroyForcibly();
        }
    }
}

/**
 * Finds kubectl or helm on PATH. The typed command cannot choose a path.
 */
interface BinaryLocator {

    Optional<Path> find(String name);

    static BinaryLocator fromPath() {
        return name -> {
            String path = System.getenv("PATH");
            if (path == null || path.isBlank() || name == null || name.isBlank()) {
                return Optional.empty();
            }
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            for (String directory : path.split(Pattern.quote(java.io.File.pathSeparator))) {
                if (directory.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(directory).resolve(name);
                if (isExecutable(candidate)) {
                    return Optional.of(candidate.toAbsolutePath());
                }
                if (windows) {
                    Path exe = Path.of(directory).resolve(name + ".exe");
                    if (isExecutable(exe)) {
                        return Optional.of(exe.toAbsolutePath());
                    }
                }
            }
            return Optional.empty();
        };
    }

    private static boolean isExecutable(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }
}
