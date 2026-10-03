package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.error.DashboardException;
import com.paultinius.k8s.dashboard.forward.OpenForward;
import com.paultinius.k8s.dashboard.model.ClusterInfo;
import com.paultinius.k8s.dashboard.model.ForwardView;
import com.paultinius.k8s.dashboard.model.LogLine;
import com.paultinius.k8s.dashboard.model.LogPage;
import com.paultinius.k8s.dashboard.model.NamespaceView;
import com.paultinius.k8s.dashboard.model.Overview;
import com.paultinius.k8s.dashboard.model.ResourceDetail;
import com.paultinius.k8s.dashboard.model.ResourceView;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class LiveCluster implements ClusterClient {

    private static final Logger log = LoggerFactory.getLogger(LiveCluster.class);

    private final String id;
    private final String name;
    private final String server;
    private final String namespace;
    private final String kubeconfig;
    private final String source;
    private final boolean inCluster;
    private final Consumer<String> onChange;
    private final Object lock = new Object();
    private final List<Watch> watches = new ArrayList<>();
    private KubernetesClient client;
    private boolean watchesStarted;
    private String version = "";
    private long versionReadAt;

    private LiveCluster(
            String id,
            String name,
            String server,
            String namespace,
            String kubeconfig,
            String source,
            boolean inCluster,
            Consumer<String> onChange
    ) {
        this.id = id;
        this.name = name;
        this.server = server;
        this.namespace = namespace == null ? "" : namespace;
        this.kubeconfig = kubeconfig;
        this.source = source;
        this.inCluster = inCluster;
        this.onChange = onChange == null ? ignored -> { } : onChange;
    }

    static LiveCluster fromKubeconfig(
            String id,
            String name,
            String server,
            String namespace,
            String kubeconfig,
            String source,
            Consumer<String> onChange
    ) {
        return new LiveCluster(id, name, server, namespace, kubeconfig, source, false, onChange);
    }

    static LiveCluster inCluster(Consumer<String> onChange) {
        return new LiveCluster(
                "in-cluster",
                "In-cluster",
                "https://kubernetes.default.svc",
                "",
                null,
                "in-cluster",
                true,
                onChange
        );
    }

    @Override
    public ClusterInfo info() {
        return new ClusterInfo(id, name, server, namespace, false, false, source);
    }

    @Override
    public List<String> namespaces() {
        return namespaceDetails().stream().map(view -> view.name()).toList();
    }

    @Override
    public List<NamespaceView> namespaceDetails() {
        return call(() -> client().namespaces().list().getItems().stream()
                .map(LiveCluster::namespaceView)
                .sorted(Comparator.comparing(view -> view.name()))
                .toList());
    }

    @Override
    public void createNamespace(String name) {
        String namespace = NamespaceNames.requireCreatable(name);
        call(() -> {
            if (client().namespaces().withName(namespace).get() != null) {
                throw DashboardException.badRequest("Namespace " + namespace + " already exists");
            }
            client().namespaces().resource(new NamespaceBuilder()
                    .withNewMetadata()
                    .withName(namespace)
                    .endMetadata()
                    .build()).create();
            return null;
        });
        changed();
    }

    @Override
    public void deleteNamespace(String name) {
        String namespace = NamespaceNames.requireDeletable(name);
        call(() -> {
            if (client().namespaces().withName(namespace).get() == null) {
                throw DashboardException.notFound("Namespace " + namespace + " was not found");
            }
            client().namespaces().withName(namespace).delete();
            return null;
        });
        changed();
    }

    @Override
    public Overview overview(Set<String> namespaces) {
        List<ResourceView> pods = list(ResourceKind.POD, namespaces);
        int ready = (int) pods.stream().filter(pod -> "Running".equals(pod.status())).count();
        Map<String, Integer> phases = new LinkedHashMap<>();
        for (ResourceView pod : pods) {
            Integer count = phases.get(pod.status());
            phases.put(pod.status(), count == null ? 1 : count + 1);
        }
        List<ResourceView> attention = new ArrayList<>();
        for (ResourceView event : list(ResourceKind.EVENT, namespaces)) {
            if ("Warning".equals(event.status())) {
                attention.add(event);
            }
        }
        for (ResourceView pod : pods) {
            if (!"Running".equals(pod.status()) && !"Succeeded".equals(pod.status())) {
                attention.add(pod);
            }
        }
        return new Overview(
                id,
                name,
                false,
                version(),
                namespaces == null || namespaces.isEmpty() ? namespaces().size() : namespaces.size(),
                pods.size(),
                ready,
                list(ResourceKind.DEPLOYMENT, namespaces).size(),
                list(ResourceKind.SERVICE, namespaces).size(),
                list(ResourceKind.CONFIG_MAP, namespaces).size(),
                list(ResourceKind.NODE, Set.of()).size(),
                (int) attention.stream().filter(item -> "Event".equals(item.kind())).count(),
                phases.entrySet().stream().map(entry -> new Overview.PhaseCount(entry.getKey(), entry.getValue())).toList(),
                attention.stream().limit(8).toList(),
                metrics(namespaces)
        );
    }

    @Override
    public List<ResourceView> list(ResourceKind kind, Set<String> namespaces) {
        return switch (kind) {
            case POD -> pods(namespaces).stream().map(LiveViews::pod).sorted(byName()).toList();
            case DEPLOYMENT -> deployments(namespaces).stream().map(LiveViews::deployment).sorted(byName()).toList();
            case SERVICE -> services(namespaces).stream().map(LiveViews::service).sorted(byName()).toList();
            case CONFIG_MAP -> configMaps(namespaces).stream().map(LiveViews::configMap).sorted(byName()).toList();
            case NODE -> {
                Map<String, Usage> usage = nodeUsage();
                yield nodes().stream()
                        .map(node -> {
                            Usage row = usage.getOrDefault(node.getMetadata().getName(), new Usage("", ""));
                            return LiveViews.node(node, row.cpu(), row.memory());
                        })
                        .sorted(byName())
                        .toList();
            }
            case EVENT -> events(namespaces).stream().map(LiveViews::event).sorted(byName()).toList();
        };
    }

    @Override
    public ResourceDetail detail(ResourceKind kind, String namespace, String name) {
        requireNamespace(kind, namespace);
        return switch (kind) {
            case POD -> {
                Pod pod = require(client().pods().inNamespace(namespace).withName(name).get(), "Pod", namespace, name);
                yield LiveViews.detail(LiveViews.pod(pod), pod, LiveViews.containerNames(pod.getSpec()));
            }
            case DEPLOYMENT -> {
                Deployment deployment = require(
                        client().apps().deployments().inNamespace(namespace).withName(name).get(),
                        "Deployment",
                        namespace,
                        name
                );
                var spec = deployment.getSpec() == null || deployment.getSpec().getTemplate() == null
                        ? null
                        : deployment.getSpec().getTemplate().getSpec();
                yield LiveViews.detail(LiveViews.deployment(deployment), deployment, LiveViews.containerNames(spec));
            }
            case SERVICE -> {
                Service service = require(client().services().inNamespace(namespace).withName(name).get(), "Service", namespace, name);
                yield LiveViews.detail(LiveViews.service(service), service, List.of());
            }
            case CONFIG_MAP -> {
                ConfigMap configMap = require(client().configMaps().inNamespace(namespace).withName(name).get(), "ConfigMap", namespace, name);
                yield LiveViews.detail(LiveViews.configMap(configMap), configMap, List.of());
            }
            case NODE -> {
                Node node = require(client().nodes().withName(name).get(), "Node", "", name);
                Usage usage = usage(node);
                yield LiveViews.detail(LiveViews.node(node, usage.cpu(), usage.memory()), node, List.of());
            }
            case EVENT -> {
                Event event = require(client().v1().events().inNamespace(namespace).withName(name).get(), "Event", namespace, name);
                yield LiveViews.detail(LiveViews.event(event), event, List.of());
            }
        };
    }

    @Override
    public void applyYaml(String yaml) {
        call(() -> {
            client().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                    .resources()
                    .forEach(resource -> resource.createOr(existing -> existing.update()));
            return null;
        });
        changed();
    }

    @Override
    public void scale(String namespace, String name, int replicas) {
        require(client().apps().deployments().inNamespace(namespace).withName(name).get(), "Deployment", namespace, name);
        call(() -> client().apps().deployments().inNamespace(namespace).withName(name).scale(replicas));
        changed();
    }

    @Override
    public void rolloutRestart(String namespace, String name) {
        require(client().apps().deployments().inNamespace(namespace).withName(name).get(), "Deployment", namespace, name);
        String stamp = Instant.now().toString();
        call(() -> client().apps().deployments().inNamespace(namespace).withName(name).edit(deployment ->
                new DeploymentBuilder(deployment)
                        .editSpec()
                        .editTemplate()
                        .editMetadata()
                        .addToAnnotations("kubectl.kubernetes.io/restartedAt", stamp)
                        .endMetadata()
                        .endTemplate()
                        .endSpec()
                        .build()));
        changed();
    }

    @Override
    public void deletePod(String namespace, String name) {
        require(client().pods().inNamespace(namespace).withName(name).get(), "Pod", namespace, name);
        call(() -> client().pods().inNamespace(namespace).withName(name).delete());
        changed();
    }

    @Override
    public void deleteResource(ResourceKind kind, String namespace, String name) {
        switch (kind) {
            case POD -> deletePod(namespace, name);
            case DEPLOYMENT -> {
                require(client().apps().deployments().inNamespace(namespace).withName(name).get(), "Deployment", namespace, name);
                call(() -> {
                    client().apps().deployments().inNamespace(namespace).withName(name).delete();
                    return null;
                });
                changed();
            }
            case SERVICE -> {
                require(client().services().inNamespace(namespace).withName(name).get(), "Service", namespace, name);
                call(() -> {
                    client().services().inNamespace(namespace).withName(name).delete();
                    return null;
                });
                changed();
            }
            case CONFIG_MAP -> {
                require(client().configMaps().inNamespace(namespace).withName(name).get(), "ConfigMap", namespace, name);
                call(() -> {
                    client().configMaps().inNamespace(namespace).withName(name).delete();
                    return null;
                });
                changed();
            }
            case NODE, EVENT -> throw DashboardException.badRequest(kind.apiName() + " cannot be deleted");
        }
    }

    @Override
    public LogPage logs(LogRequest request) {
        List<Pod> selected = new ArrayList<>();
        if (!request.pod().isBlank()) {
            for (String namespace : namespacesOrAll(request.namespaces())) {
                Pod pod = client().pods().inNamespace(namespace).withName(request.pod()).get();
                if (pod != null) {
                    selected.add(pod);
                }
            }
        } else {
            for (Pod pod : pods(request.namespaces())) {
                if (request.deployment().isBlank() || ownedBy(pod, request.deployment())) {
                    selected.add(pod);
                }
                if (selected.size() >= 20) {
                    break;
                }
            }
        }
        String query = request.query().toLowerCase(Locale.ROOT);
        List<LogLine> lines = new ArrayList<>();
        boolean truncated = false;
        for (Pod pod : selected) {
            String namespace = pod.getMetadata().getNamespace();
            String name = pod.getMetadata().getName();
            List<String> containers = request.container().isBlank()
                    ? LiveViews.containerNames(pod.getSpec())
                    : List.of(request.container());
            if (containers.isEmpty()) {
                containers = List.of("");
            }
            for (String container : containers) {
                String text = logText(namespace, name, container, request.tail());
                for (String line : text.split("\\R")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (!query.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(query)) {
                        continue;
                    }
                    if (lines.size() >= 2000) {
                        truncated = true;
                        break;
                    }
                    lines.add(new LogLine(namespace, name, container, line));
                }
            }
        }
        return new LogPage(lines, truncated);
    }

    @Override
    public OpenForward openForward(String namespace, String targetKind, String targetName, int remotePort, int localPort) {
        Pod pod = resolvePod(namespace, targetKind, targetName);
        String podName = pod.getMetadata().getName();
        int port = localPort > 0 ? localPort : freePort();
        LocalPortForward forward;
        try {
            forward = client().pods().inNamespace(namespace).withName(podName).portForward(remotePort, port);
        } catch (KubernetesClientException exception) {
            throw wrap(exception);
        } catch (RuntimeException exception) {
            if (causedByBind(exception)) {
                throw new DashboardException(HttpStatus.CONFLICT, "Local port " + port + " is in use");
            }
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Port forward failed");
        }
        String forwardId = UUID.randomUUID().toString().substring(0, 8);
        ForwardView view = new ForwardView(
                forwardId,
                id,
                namespace,
                targetKind.toLowerCase(Locale.ROOT),
                targetName,
                podName,
                remotePort,
                port,
                false,
                "http://127.0.0.1:" + port,
                "Listening on this machine. In a cluster, the port opens on the dashboard pod."
        );
        return new OpenForward(view, () -> {
            try {
                forward.close();
            } catch (IOException exception) {
                throw new IllegalStateException("Port forward did not close", exception);
            }
        });
    }

    @Override
    public void close() {
        synchronized (lock) {
            for (Watch watch : watches) {
                try {
                    watch.close();
                } catch (RuntimeException ignored) {
                    // Closing a finished watch is safe to ignore.
                }
            }
            watches.clear();
            if (client != null) {
                client.close();
                client = null;
            }
        }
    }

    private KubernetesClient client() {
        synchronized (lock) {
            if (client == null) {
                client = openClient();
            }
            if (!watchesStarted) {
                startWatches(client);
                watchesStarted = true;
            }
            return client;
        }
    }

    private KubernetesClient openClient() {
        try {
            if (inCluster) {
                return new KubernetesClientBuilder().build();
            }
            Config config = Config.fromKubeconfig(kubeconfig);
            config.setConnectionTimeout(4_000);
            config.setRequestTimeout(20_000);
            return new KubernetesClientBuilder().withConfig(config).build();
        } catch (RuntimeException exception) {
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Could not open a client for " + name);
        }
    }

    private void startWatches(KubernetesClient current) {
        try {
            watches.add(current.pods().inAnyNamespace().watch(watcher()));
            watches.add(current.apps().deployments().inAnyNamespace().watch(watcher()));
            watches.add(current.services().inAnyNamespace().watch(watcher()));
        } catch (RuntimeException exception) {
            log.warn("Live watch for {} did not start: {}", id, exception.getMessage());
        }
    }

    private <T> Watcher<T> watcher() {
        return new Watcher<>() {
            @Override
            public void eventReceived(Action action, T resource) {
                onChange.accept(id);
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) {
                    log.debug("Watch closed for {}: {}", id, cause.getMessage());
                }
            }
        };
    }

    private List<Pod> pods(Set<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return call(() -> client().pods().inAnyNamespace().list().getItems());
        }
        List<Pod> pods = new ArrayList<>();
        for (String namespace : namespaces) {
            pods.addAll(call(() -> client().pods().inNamespace(namespace).list().getItems()));
        }
        return pods;
    }

    private List<Deployment> deployments(Set<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return call(() -> client().apps().deployments().inAnyNamespace().list().getItems());
        }
        List<Deployment> deployments = new ArrayList<>();
        for (String namespace : namespaces) {
            deployments.addAll(call(() -> client().apps().deployments().inNamespace(namespace).list().getItems()));
        }
        return deployments;
    }

    private List<Service> services(Set<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return call(() -> client().services().inAnyNamespace().list().getItems());
        }
        List<Service> services = new ArrayList<>();
        for (String namespace : namespaces) {
            services.addAll(call(() -> client().services().inNamespace(namespace).list().getItems()));
        }
        return services;
    }

    private List<ConfigMap> configMaps(Set<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return call(() -> client().configMaps().inAnyNamespace().list().getItems());
        }
        List<ConfigMap> configMaps = new ArrayList<>();
        for (String namespace : namespaces) {
            configMaps.addAll(call(() -> client().configMaps().inNamespace(namespace).list().getItems()));
        }
        return configMaps;
    }

    private List<Node> nodes() {
        return call(() -> client().nodes().list().getItems());
    }

    private List<Event> events(Set<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return call(() -> client().v1().events().inAnyNamespace().list().getItems());
        }
        List<Event> events = new ArrayList<>();
        for (String namespace : namespaces) {
            events.addAll(call(() -> client().v1().events().inNamespace(namespace).list().getItems()));
        }
        return events;
    }

    private Set<String> namespacesOrAll(Set<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return Set.copyOf(this.namespaces());
        }
        return namespaces;
    }

    private String logText(String namespace, String pod, String container, int tail) {
        return call(() -> {
            var named = client().pods().inNamespace(namespace).withName(pod);
            String text = container == null || container.isBlank()
                    ? named.tailingLines(tail).getLog()
                    : named.inContainer(container).tailingLines(tail).getLog();
            return text == null ? "" : text;
        });
    }

    private Pod resolvePod(String namespace, String targetKind, String targetName) {
        String kind = targetKind == null ? "" : targetKind.toLowerCase(Locale.ROOT);
        if ("pod".equals(kind) || "pods".equals(kind)) {
            return require(client().pods().inNamespace(namespace).withName(targetName).get(), "Pod", namespace, targetName);
        }
        if ("service".equals(kind) || "services".equals(kind) || "svc".equals(kind)) {
            Service service = require(client().services().inNamespace(namespace).withName(targetName).get(), "Service", namespace, targetName);
            Map<String, String> selector = service.getSpec() == null ? Map.of() : service.getSpec().getSelector();
            if (selector == null || selector.isEmpty()) {
                throw DashboardException.badRequest("Service " + targetName + " has no selector");
            }
            List<Pod> pods = call(() -> client().pods().inNamespace(namespace).withLabels(selector).list().getItems());
            return pods.stream()
                    .filter(pod -> "Running".equals(LiveViews.podStatus(pod)))
                    .findFirst()
                    .or(() -> pods.stream().findFirst())
                    .orElseThrow(() -> DashboardException.notFound("No pod matches service " + targetName));
        }
        throw DashboardException.badRequest("Port forward target must be a pod or a service");
    }

    private static boolean ownedBy(Pod pod, String deployment) {
        if (pod.getMetadata().getOwnerReferences() != null) {
            for (OwnerReference reference : pod.getMetadata().getOwnerReferences()) {
                if ("ReplicaSet".equals(reference.getKind())
                        && reference.getName() != null
                        && reference.getName().startsWith(deployment + "-")) {
                    return true;
                }
            }
        }
        Map<String, String> labels = pod.getMetadata().getLabels();
        return labels != null && deployment.equals(labels.get("app"));
    }

    private List<Overview.MetricRow> metrics(Set<String> namespaces) {
        try {
            List<PodMetrics> items = new ArrayList<>();
            if (namespaces == null || namespaces.isEmpty()) {
                items.addAll(client().top().pods().metrics().getItems());
            } else {
                for (String namespace : namespaces) {
                    items.addAll(client().top().pods().metrics(namespace).getItems());
                }
            }
            List<Overview.MetricRow> rows = new ArrayList<>();
            for (PodMetrics metrics : items) {
                String cpu = "";
                String memory = "";
                if (metrics.getContainers() != null && !metrics.getContainers().isEmpty()) {
                    ContainerMetrics container = metrics.getContainers().get(0);
                    cpu = quantity(container.getUsage(), "cpu");
                    memory = quantity(container.getUsage(), "memory");
                }
                rows.add(new Overview.MetricRow(
                        metrics.getMetadata().getNamespace(),
                        metrics.getMetadata().getName(),
                        cpu,
                        memory
                ));
                if (rows.size() >= 12) {
                    break;
                }
            }
            return rows;
        } catch (RuntimeException exception) {
            log.debug("Metrics unavailable for {}: {}", id, exception.getMessage());
            return List.of();
        }
    }

    private Usage usage(Node node) {
        return nodeUsage().getOrDefault(node.getMetadata().getName(), new Usage("", ""));
    }

    private Map<String, Usage> nodeUsage() {
        Map<String, Usage> usage = new LinkedHashMap<>();
        try {
            for (NodeMetrics metrics : client().top().nodes().metrics().getItems()) {
                usage.put(
                        metrics.getMetadata().getName(),
                        new Usage(quantity(metrics.getUsage(), "cpu"), quantity(metrics.getUsage(), "memory"))
                );
            }
        } catch (RuntimeException exception) {
            log.debug("Node metrics unavailable for {}: {}", id, exception.getMessage());
        }
        return usage;
    }

    private String version() {
        long now = System.currentTimeMillis();
        if (!version.isEmpty() && now - versionReadAt < 60_000) {
            return version;
        }
        try {
            version = client().getKubernetesVersion().getGitVersion();
        } catch (RuntimeException exception) {
            version = "unknown";
        }
        versionReadAt = now;
        return version;
    }

    private void changed() {
        onChange.accept(id);
    }

    private static void requireNamespace(ResourceKind kind, String namespace) {
        if (kind.namespaced() && (namespace == null || namespace.isBlank())) {
            throw DashboardException.badRequest(kind.apiName() + " requires a namespace");
        }
    }

    private static NamespaceView namespaceView(Namespace namespace) {
        String name = "";
        if (namespace.getMetadata() != null && namespace.getMetadata().getName() != null) {
            name = namespace.getMetadata().getName();
        }
        String status = "Active";
        if (namespace.getStatus() != null && namespace.getStatus().getPhase() != null) {
            status = namespace.getStatus().getPhase();
        }
        return new NamespaceView(name, status, NamespaceNames.deletable(name));
    }

    private static <T> T require(T resource, String kind, String namespace, String name) {
        if (resource == null) {
            String where = namespace == null || namespace.isBlank() ? name : namespace + "/" + name;
            throw DashboardException.notFound(kind + " " + where + " was not found");
        }
        return resource;
    }

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (KubernetesClientException exception) {
            throw wrap(exception);
        }
    }

    private static DashboardException wrap(KubernetesClientException exception) {
        int code = exception.getCode();
        HttpStatus status = switch (code) {
            case 400, 404, 409, 422 -> HttpStatus.valueOf(code);
            default -> HttpStatus.BAD_GATEWAY;
        };
        String message = exception.getMessage() == null ? "Cluster request failed" : exception.getMessage();
        message = message.replaceAll("(?i)bearer\\s+\\S+", "bearer [redacted]");
        if (message.length() > 400) {
            message = message.substring(0, 400);
        }
        return new DashboardException(status, message);
    }

    private static String quantity(Map<String, Quantity> usage, String key) {
        if (usage == null || usage.get(key) == null) {
            return "";
        }
        return usage.get(key).toString();
    }

    private static boolean causedByBind(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof BindException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "No local port is available");
        }
    }

    private static Comparator<ResourceView> byName() {
        return Comparator.comparing((ResourceView view) -> view.namespace()).thenComparing(view -> view.name());
    }

    private record Usage(String cpu, String memory) {
    }
}
