package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.config.DashboardProperties;
import com.paultinius.k8s.dashboard.error.DashboardException;
import com.paultinius.k8s.dashboard.live.LiveHub;
import com.paultinius.k8s.dashboard.model.ClusterInfo;
import io.fabric8.kubernetes.client.Config;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Component
public class ClusterRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClusterRegistry.class);

    private final Map<String, ClusterClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Path> files = new ConcurrentHashMap<>();
    private final AtomicReference<String> active = new AtomicReference<>();
    private final Path dataDir;
    private final LiveHub hub;

    public ClusterRegistry(DashboardProperties properties, LiveHub hub) {
        this.hub = hub;
        String configured = properties.getCluster().getDataDir();
        this.dataDir = Path.of(configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".k8s-dashboard").toString()
                : configured);
        if (properties.getCluster().isDemoEnabled()) {
            DemoCluster demo = new DemoCluster();
            demo.onChange(id -> hub.publish(id, "changed"));
            clients.put(demo.info().id(), demo);
        }
        if (properties.getCluster().isInCluster()) {
            put(LiveCluster.inCluster(id -> hub.publish(id, "changed")), null);
        }
        if (properties.getCluster().isLoadDefaultKubeconfig()) {
            loadFile(Path.of(System.getProperty("user.home"), ".kube", "config"), "kubeconfig", false);
        }
        for (String extra : properties.getCluster().getKubeconfigs()) {
            if (extra != null && !extra.isBlank()) {
                loadFile(Path.of(extra), "kubeconfig", false);
            }
        }
        loadSaved();
        chooseDefault();
        log.info("Dashboard ready with {} cluster{}", clients.size(), clients.size() == 1 ? "" : "s");
    }

    public List<ClusterInfo> list() {
        return clients.values().stream()
                .map(client -> describe(client.info()))
                .sorted(Comparator.comparing((ClusterInfo info) -> info.demo()).thenComparing(info -> info.name()))
                .toList();
    }

    public ClusterInfo activate(String id) {
        require(id);
        active.set(id);
        return describe(require(id).info());
    }

    public ClusterClient require(String id) {
        if (clients.isEmpty()) {
            throw DashboardException.notFound("No clusters are configured");
        }
        String key = id == null || id.isBlank() ? active.get() : id;
        if (key == null) {
            throw DashboardException.notFound("No cluster is selected");
        }
        ClusterClient client = clients.get(key);
        if (client == null) {
            throw DashboardException.notFound("No cluster " + key);
        }
        return client;
    }

    public List<ClusterInfo> addKubeconfig(String yaml, String preferredName) {
        List<KubeconfigCatalog.ContextRef> contexts = KubeconfigCatalog.read(yaml);
        List<ClusterInfo> created = new ArrayList<>();
        for (KubeconfigCatalog.ContextRef context : contexts) {
            String isolated = KubeconfigCatalog.isolate(yaml, context.name());
            validate(isolated);
            String id = KubeconfigCatalog.idFor(context.name());
            ClusterClient existing = clients.get(id);
            if (existing != null && !context.server().equals(existing.info().server()) && !context.name().equals(existing.info().name())) {
                id = uniqueId(id);
            }
            String display = contexts.size() == 1 && preferredName != null && !preferredName.isBlank()
                    ? preferredName.trim()
                    : context.name();
            Path path = dataDir.resolve("clusters").resolve(id + ".kubeconfig");
            writePrivate(path, isolated);
            LiveCluster live = LiveCluster.fromKubeconfig(
                    id,
                    display,
                    context.server(),
                    context.namespace(),
                    isolated,
                    "uploaded",
                    clusterId -> hub.publish(clusterId, "changed")
            );
            put(live, path);
            created.add(describe(live.info()));
        }
        if (!created.isEmpty()) {
            active.set(created.get(0).id());
        }
        return created.stream().map(info -> describe(require(info.id()).info())).toList();
    }

    public void delete(String id) {
        ClusterClient client = require(id);
        if (client.info().demo()) {
            throw DashboardException.badRequest("The demo cluster stays available");
        }
        clients.remove(id);
        client.close();
        Path path = files.remove(id);
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                log.warn("Could not delete saved kubeconfig {}", path);
            }
        }
        if (id.equals(active.get())) {
            active.set(null);
            chooseDefault();
        }
    }

    @PreDestroy
    public void close() {
        clients.values().forEach(client -> client.close());
    }

    private void loadSaved() {
        Path directory = dataDir.resolve("clusters");
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".kubeconfig"))
                    .forEach(path -> loadFile(path, "uploaded", true));
        } catch (IOException exception) {
            log.warn("Could not read saved clusters from {}", directory);
        }
    }

    private void loadFile(Path path, String source, boolean rememberFile) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String yaml = Files.readString(path);
            for (KubeconfigCatalog.ContextRef context : KubeconfigCatalog.read(yaml)) {
                String id = KubeconfigCatalog.idFor(context.name());
                if (clients.containsKey(id)) {
                    continue;
                }
                String isolated = "uploaded".equals(source) ? yaml : KubeconfigCatalog.isolate(yaml, context.name());
                put(LiveCluster.fromKubeconfig(
                        id,
                        context.name(),
                        context.server(),
                        context.namespace(),
                        isolated,
                        source,
                        clusterId -> hub.publish(clusterId, "changed")
                ), rememberFile ? path : null);
            }
        } catch (RuntimeException | IOException exception) {
            log.warn("Skipping kubeconfig {}: {}", path, exception.getMessage());
        }
    }

    private void put(ClusterClient client, Path path) {
        ClusterClient previous = clients.put(client.info().id(), client);
        if (previous != null && previous != client) {
            previous.close();
        }
        if (path == null) {
            files.remove(client.info().id());
        } else {
            files.put(client.info().id(), path);
        }
    }

    private void chooseDefault() {
        if (active.get() != null && clients.containsKey(active.get())) {
            return;
        }
        String demo = null;
        String other = null;
        for (ClusterClient client : clients.values()) {
            if (client.info().demo()) {
                demo = client.info().id();
            } else if (other == null) {
                other = client.info().id();
            }
        }
        active.set(other != null ? other : demo);
    }

    private ClusterInfo describe(ClusterInfo info) {
        return new ClusterInfo(
                info.id(),
                info.name(),
                info.server(),
                info.namespace(),
                info.demo(),
                info.id().equals(active.get()),
                info.source()
        );
    }

    private String uniqueId(String base) {
        String id = base;
        int suffix = 2;
        while (clients.containsKey(id)) {
            id = base + "-" + suffix;
            suffix++;
        }
        return id;
    }

    private static void validate(String kubeconfig) {
        try {
            Config config = Config.fromKubeconfig(kubeconfig);
            if (config.getMasterUrl() == null || config.getMasterUrl().isBlank()) {
                throw DashboardException.badRequest("Kubeconfig has no server");
            }
        } catch (DashboardException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? "Kubeconfig was rejected" : exception.getMessage();
            message = message.replaceAll("(?i)(token|bearer|password)\\s*[:=]\\s*\\S+", "$1 [redacted]");
            if (message.length() > 300) {
                message = message.substring(0, 300);
            }
            throw DashboardException.badRequest(message);
        }
    }

    private static void writePrivate(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text);
            try {
                Files.setPosixFilePermissions(path, java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                ));
            } catch (UnsupportedOperationException ignored) {
                // Windows has no POSIX modes.
            }
        } catch (IOException exception) {
            throw new DashboardException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the kubeconfig locally");
        }
    }
}
