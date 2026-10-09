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
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

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
    // Namespace-scoped resource lookups (pods, deployments, services, ...) hit the API server
    // once per namespace. Running those requests on this pool instead of sequentially keeps an
    // overview/list call for several namespaces fast - a slow sequential overview fetch is what
    // let a live-tick refresh overlap the still-running one on the frontend and made the table
    // flash while it was still loading.
    private final ExecutorService executor = Executors.newFixedThreadPool(16, runnable -> {
        Thread thread = new Thread(runnable, "k8s-cluster-io");
        thread.setDaemon(true);
        return thread;
    });
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
    public Optional<String> kubeconfigDocument() {
        return kubeconfig == null || kubeconfig.isBlank() ? Optional.empty() : Optional.of(kubeconfig);
    }

    @Override
    public boolean inCluster() {
        return inCluster;
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
        // Each list()/metrics() call already fans out across the selected namespaces on the
        // shared io executor, but the different kinds were previously fetched one after another,
        // so a many-namespace overview took the sum of every kind's time (seconds) instead of the
        // slowest one. Kicking all of them off up front - on the JVM's common pool, not the io
        // executor those calls themselves use, so this fan-out can't starve or deadlock on it -
        // lets them run together.
        CompletableFuture<List<ResourceView>> podsFuture = CompletableFuture.supplyAsync(() -> list(ResourceKind.POD, namespaces));
        CompletableFuture<List<ResourceView>> eventsFuture = CompletableFuture.supplyAsync(() -> list(ResourceKind.EVENT, namespaces));
        CompletableFuture<Integer> deploymentsFuture = CompletableFuture.supplyAsync(() -> list(ResourceKind.DEPLOYMENT, namespaces).size());
        CompletableFuture<Integer> servicesFuture = CompletableFuture.supplyAsync(() -> list(ResourceKind.SERVICE, namespaces).size());
        CompletableFuture<Integer> configMapsFuture = CompletableFuture.supplyAsync(() -> list(ResourceKind.CONFIG_MAP, namespaces).size());
        CompletableFuture<Integer> nodesFuture = CompletableFuture.supplyAsync(() -> list(ResourceKind.NODE, Set.of()).size());
        CompletableFuture<List<Overview.MetricRow>> metricsFuture = CompletableFuture.supplyAsync(() -> metrics(namespaces));

        List<ResourceView> pods = joinOrThrow(podsFuture);
        int ready = (int) pods.stream().filter(pod -> "Running".equals(pod.status())).count();
        Map<String, Integer> phases = new LinkedHashMap<>();
        for (ResourceView pod : pods) {
            Integer count = phases.get(pod.status());
            phases.put(pod.status(), count == null ? 1 : count + 1);
        }
        List<ResourceView> attention = new ArrayList<>();
        for (ResourceView event : joinOrThrow(eventsFuture)) {
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
                joinOrThrow(deploymentsFuture),
                joinOrThrow(servicesFuture),
                joinOrThrow(configMapsFuture),
                joinOrThrow(nodesFuture),
                (int) attention.stream().filter(item -> "Event".equals(item.kind())).count(),
                phases.entrySet().stream().map(entry -> new Overview.PhaseCount(entry.getKey(), entry.getValue())).toList(),
                attention,
                joinOrThrow(metricsFuture)
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
                            Usage row = usage.get(node.getMetadata().getName());
                            return LiveViews.node(node, nodeCpuText(node, row), nodeMemoryText(node, row));
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
                yield LiveViews.detail(LiveViews.node(node, nodeCpuText(node, usage), nodeMemoryText(node, usage)), node, List.of());
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
        executor.shutdownNow();
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
        return fetchPerNamespace(namespaces, namespace -> client().pods().inNamespace(namespace).list().getItems());
    }

    private List<Deployment> deployments(Set<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return call(() -> client().apps().deployments().inAnyNamespace().list().getItems());
        }
        return fetchPerNamespace(namespaces, namespace -> client().apps().deployments().inNamespace(namespace).list().getItems());
    }

    private List<Service> services(Set<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return call(() -> client().services().inAnyNamespace().list().getItems());
        }
        return fetchPerNamespace(namespaces, namespace -> client().services().inNamespace(namespace).list().getItems());
    }

    private List<ConfigMap> configMaps(Set<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return call(() -> client().configMaps().inAnyNamespace().list().getItems());
        }
        return fetchPerNamespace(namespaces, namespace -> client().configMaps().inNamespace(namespace).list().getItems());
    }

    private List<Node> nodes() {
        return call(() -> client().nodes().list().getItems());
    }

    private List<Event> events(Set<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return call(() -> client().v1().events().inAnyNamespace().list().getItems());
        }
        return fetchPerNamespace(namespaces, namespace -> client().v1().events().inNamespace(namespace).list().getItems());
    }

    // Fetches one namespace's worth of resources at a time on the shared io executor instead of
    // sequentially, so a kind lookup across many selected namespaces takes roughly as long as the
    // slowest single namespace rather than the sum of all of them.
    private <T> List<T> fetchPerNamespace(Set<String> namespaces, Function<String, List<T>> fetch) {
        List<CompletableFuture<List<T>>> futures = namespaces.stream()
                .map(namespace -> CompletableFuture.supplyAsync(() -> call(() -> fetch.apply(namespace)), executor))
                .toList();
        List<T> combined = new ArrayList<>();
        for (CompletableFuture<List<T>> future : futures) {
            combined.addAll(joinOrThrow(future));
        }
        return combined;
    }

    private static <T> T joinOrThrow(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw exception;
        }
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
                items.addAll(fetchPerNamespace(namespaces, namespace -> client().top().pods().metrics(namespace).getItems()));
            }
            List<Overview.MetricRow> rows = new ArrayList<>();
            for (PodMetrics metrics : items) {
                BigDecimal cpuCores = BigDecimal.ZERO;
                BigDecimal memoryBytes = BigDecimal.ZERO;
                if (metrics.getContainers() != null) {
                    for (ContainerMetrics container : metrics.getContainers()) {
                        Map<String, Quantity> usage = container.getUsage();
                        if (usage == null) {
                            continue;
                        }
                        if (usage.get("cpu") != null) {
                            cpuCores = cpuCores.add(Quantity.getAmountInBytes(usage.get("cpu")));
                        }
                        if (usage.get("memory") != null) {
                            memoryBytes = memoryBytes.add(Quantity.getAmountInBytes(usage.get("memory")));
                        }
                    }
                }
                rows.add(new Overview.MetricRow(
                        metrics.getMetadata().getNamespace(),
                        metrics.getMetadata().getName(),
                        formatCpu(cpuCores),
                        formatMemory(memoryBytes)
                ));
            }
            return rows;
        } catch (RuntimeException exception) {
            log.debug("Metrics unavailable for {}: {}", id, exception.getMessage());
            return List.of();
        }
    }

    private Usage usage(Node node) {
        return nodeUsage().get(node.getMetadata().getName());
    }

    private Map<String, Usage> nodeUsage() {
        Map<String, Usage> usage = new LinkedHashMap<>();
        try {
            for (NodeMetrics metrics : client().top().nodes().metrics().getItems()) {
                usage.put(
                        metrics.getMetadata().getName(),
                        new Usage(amount(metrics.getUsage(), "cpu"), amount(metrics.getUsage(), "memory"))
                );
            }
        } catch (RuntimeException exception) {
            log.debug("Node metrics unavailable for {}: {}", id, exception.getMessage());
        }
        return usage;
    }

    // Renders a node's CPU column as "used/allocatable" (e.g. "250m/4"), falling back to
    // whichever half is available. Raw Quantity.toString() values (nanocores, binary suffixes)
    // are never shown to the user.
    private String nodeCpuText(Node node, Usage usage) {
        return capacityText(
                usage == null ? null : usage.cpu(),
                allocatable(node, "cpu"),
                LiveCluster::formatCpuAmount
        );
    }

    private String nodeMemoryText(Node node, Usage usage) {
        return capacityText(
                usage == null ? null : usage.memory(),
                allocatable(node, "memory"),
                LiveCluster::formatMemoryAmount
        );
    }

    private static String capacityText(BigDecimal used, BigDecimal total, Function<BigDecimal, String> formatter) {
        String usedText = used == null ? "" : formatter.apply(used);
        String totalText = total == null ? "" : formatter.apply(total);
        if (usedText.isEmpty()) {
            return totalText;
        }
        if (totalText.isEmpty()) {
            return usedText;
        }
        return usedText + "/" + totalText;
    }

    private static BigDecimal allocatable(Node node, String key) {
        if (node.getStatus() == null) {
            return null;
        }
        Map<String, Quantity> allocatable = node.getStatus().getAllocatable();
        Map<String, Quantity> capacity = node.getStatus().getCapacity();
        Quantity quantity = allocatable != null && allocatable.get(key) != null ? allocatable.get(key) : (capacity == null ? null : capacity.get(key));
        return quantity == null ? null : Quantity.getAmountInBytes(quantity);
    }

    private static BigDecimal amount(Map<String, Quantity> usage, String key) {
        if (usage == null || usage.get(key) == null) {
            return null;
        }
        return Quantity.getAmountInBytes(usage.get(key));
    }

    private static String formatCpuAmount(BigDecimal cores) {
        if (cores.signum() <= 0) {
            return "0";
        }
        if (cores.compareTo(BigDecimal.ONE) < 0) {
            return cores.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP) + "m";
        }
        return cores.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String formatMemoryAmount(BigDecimal bytes) {
        if (bytes.signum() <= 0) {
            return "0";
        }
        BigDecimal gibibytes = bytes.divide(BigDecimal.valueOf(1024L * 1024L * 1024L), 2, RoundingMode.HALF_UP);
        if (gibibytes.compareTo(BigDecimal.ONE) >= 0) {
            return gibibytes.stripTrailingZeros().toPlainString() + "Gi";
        }
        BigDecimal mebibytes = bytes.divide(BigDecimal.valueOf(1024L * 1024L), 0, RoundingMode.HALF_UP);
        return mebibytes + "Mi";
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

    private static String formatCpu(BigDecimal cores) {
        if (cores.signum() <= 0) {
            return "";
        }
        long millis = cores.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return millis + "m";
    }

    private static String formatMemory(BigDecimal bytes) {
        if (bytes.signum() <= 0) {
            return "";
        }
        long mebibytes = bytes.divide(BigDecimal.valueOf(1024L * 1024L), 0, RoundingMode.HALF_UP).longValueExact();
        return mebibytes + "Mi";
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

    private record Usage(BigDecimal cpu, BigDecimal memory) {
    }
}
