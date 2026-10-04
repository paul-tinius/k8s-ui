package com.paultinius.k8s.dashboard.shell;

import com.paultinius.k8s.dashboard.error.DashboardException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalCommandTest {

    private static final Set<String> PASSED = Set.of(
            "PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE",
            "TMPDIR", "TMP", "TEMP", "SystemRoot", "PATHEXT",
            "KUBECONFIG", "TERM", "PWD", "SHLVL", "_"
    );

    @TempDir
    Path tempDir;

    @Test
    void run_kubectlArguments_areNotInterpretedByAShell() throws Exception {
        Path script = script("""
                #!/bin/sh
                printf 'COUNT:%s\\n' "$#"
                printf 'ONE:%s\\n' "$1"
                printf 'TWO:%s\\n' "$2"
                if [ -n "$KUBECONFIG" ] && grep -q "super-secret" "$KUBECONFIG"; then
                  printf 'SECRET=yes\\n'
                fi
                printf 'KUBECONFIG=%s\\n' "$KUBECONFIG"
                printenv | awk -F= '{print "ENV:" $1}'
                """);

        CommandResult result = command(script).run(
                null,
                List.of("kubectl", "get", "pods;rm"),
                "apiVersion: v1\nsuper-secret: yes\n",
                false);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("COUNT:2").contains("ONE:get").contains("TWO:pods;rm").contains("SECRET=yes");
        assertThat(envNames(result.stdout())).allMatch(PASSED::contains);
        String kubeconfig = result.stdout().lines()
                .filter(line -> line.startsWith("KUBECONFIG="))
                .map(line -> line.substring("KUBECONFIG=".length()))
                .findFirst()
                .orElse("");
        assertThat(kubeconfig).isNotBlank();
        assertThat(Files.exists(Path.of(kubeconfig))).isFalse();
    }

    @Test
    void run_inCluster_doesNotSetKubeconfig() throws Exception {
        Path script = script("""
                #!/bin/sh
                if [ -z "${KUBECONFIG+x}" ]; then printf 'KUBECONFIG=unset\\n'; else printf 'KUBECONFIG=set\\n'; fi
                printf 'SHELL=%s\\n' "${SHELL-unset}"
                """);
        CommandResult result = command(script).run(null, List.of("kubectl", "version"), "", true);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("KUBECONFIG=unset");
        String shell = System.getenv("SHELL");
        if (shell != null) {
            assertThat(result.stdout()).contains("SHELL=" + shell);
        }
    }

    @Test
    void run_missingHelm_returnsNotFound() throws Exception {
        Path script = script("#!/bin/sh\nexit 0\n");
        CommandResult result = command(script).run(null, List.of("helm", "version"), "apiVersion: v1\n", false);

        assertThat(result.exitCode()).isEqualTo(127);
        assertThat(result.stderr()).contains("helm was not found");
    }

    @Test
    void run_cancel_stopsTheProcess() throws Exception {
        Path script = script("#!/bin/sh\nsleep 30\n");
        ExternalCommand commands = new ExternalCommand(
                name -> "kubectl".equals(name) ? Optional.of(script) : Optional.empty(),
                Duration.ofSeconds(3));
        AtomicReference<CommandResult> result = new AtomicReference<>();
        Thread thread = new Thread(() -> result.set(commands.run(
                "job-1",
                List.of("kubectl", "get", "pods"),
                "apiVersion: v1\n",
                false)));
        thread.start();
        Thread.sleep(250);
        commands.cancel("job-1");
        thread.join(5_000);

        assertThat(thread.isAlive()).isFalse();
        assertThat(result.get()).isNotNull();
        assertThat(result.get().timedOut()).isFalse();
        assertThat(result.get().stderr()).contains("Stopped");
    }

    @Test
    void run_invalidId_isRejected() {
        ExternalCommand commands = new ExternalCommand(name -> Optional.empty(), Duration.ofSeconds(1));
        assertThatThrownBy(() -> commands.run("bad id", List.of("kubectl", "version"), "apiVersion: v1\n", false))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("id");
    }

    private ExternalCommand command(Path script) {
        return new ExternalCommand(
                name -> "kubectl".equals(name) ? Optional.of(script) : Optional.empty(),
                Duration.ofSeconds(5));
    }

    private Path script(String body) throws IOException {
        Path script = tempDir.resolve("tool.sh");
        Files.writeString(script, body);
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private static List<String> envNames(String stdout) {
        return stdout.lines()
                .filter(line -> line.startsWith("ENV:"))
                .map(line -> line.substring("ENV:".length()))
                .toList();
    }
}
