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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * An in-memory cluster so the dashboard is usable before a kubeconfig is added.
 */
public final class DemoCluster implements ClusterClient {

    private final Object lock = new Object();
    private final List<Deployment> deployments = new ArrayList<>();
    private final List<Pod> standalonePods = new ArrayList<>();
    private final List<ServiceObj> services = new ArrayList<>();
    private final List<ConfigMapObj> configMaps = new ArrayList<>();
    private final List<NodeObj> nodes = new ArrayList<>();
    private final List<EventObj> events = new ArrayList<>();
    private final Set<String> namespaceNames = new TreeSet<>();
    private final AtomicInteger ports = new AtomicInteger(18080);
    private int sequence = 100;
    private Consumer<String> listener = id -> { };

    public DemoCluster() {
        seed();
    }

    public void onChange(Consumer<String> listener) {
        this.listener = listener == null ? id -> { } : listener;
    }

    @Override
    public ClusterInfo info() {
        return new ClusterInfo("demo", "Demo", "demo://local", "", true, false, "demo");
    }

    @Override
    public List<String> namespaces() {
        synchronized (lock) {
            return List.copyOf(namespaceNames);
        }
    }

    @Override
    public List<NamespaceView> namespaceDetails() {
        synchronized (lock) {
            return namespaceNames.stream()
                    .map(name -> new NamespaceView(name, "Active", NamespaceNames.deletable(name)))
                    .toList();
        }
    }

    @Override
    public void createNamespace(String name) {
        String namespace = NamespaceNames.requireCreatable(name);
        synchronized (lock) {
            if (!namespaceNames.add(namespace)) {
                throw DashboardException.badRequest("Namespace " + namespace + " already exists");
            }
        }
        changed();
    }

    @Override
    public void deleteNamespace(String name) {
        String namespace = NamespaceNames.requireDeletable(name);
        synchronized (lock) {
            if (!namespaceNames.remove(namespace)) {
                throw DashboardException.notFound("Namespace " + namespace + " was not found");
            }
            deployments.removeIf(item -> item.namespace.equals(namespace));
            standalonePods.removeIf(item -> item.namespace.equals(namespace));
            services.removeIf(item -> item.namespace.equals(namespace));
            configMaps.removeIf(item -> item.namespace.equals(namespace));
            events.removeIf(item -> item.namespace.equals(namespace));
        }
        changed();
    }

    @Override
    public Overview overview(Set<String> namespaces) {
        synchronized (lock) {
            List<Pod> pods = pods(namespaces);
            int ready = (int) pods.stream().filter(pod -> "Running".equals(pod.status)).count();
            Map<String, Integer> phases = new LinkedHashMap<>();
            for (Pod pod : pods) {
                Integer count = phases.get(pod.status);
                phases.put(pod.status, count == null ? 1 : count + 1);
            }
            List<Overview.PhaseCount> phaseCounts = phases.entrySet().stream()
                    .map(entry -> new Overview.PhaseCount(entry.getKey(), entry.getValue()))
                    .toList();
            List<ResourceView> attention = new ArrayList<>();
            for (EventObj event : events) {
                if ("Warning".equals(event.type) && inScope(event.namespace, namespaces)) {
                    attention.add(eventView(event));
                }
            }
            for (Pod pod : pods) {
                if (!"Running".equals(pod.status)) {
                    attention.add(podView(pod));
                }
            }
            List<Overview.MetricRow> metrics = pods.stream()
                    .limit(12)
                    .map(pod -> new Overview.MetricRow(pod.namespace, pod.name, pod.cpu, pod.memory))
                    .toList();
            int warnings = (int) events.stream()
                    .filter(event -> "Warning".equals(event.type) && inScope(event.namespace, namespaces))
                    .count();
            int namespaceCount = (int) namespaceNames.stream()
                    .filter(name -> inScope(name, namespaces))
                    .count();
            return new Overview(
                    "demo",
                    "Demo",
                    true,
                    "demo",
                    namespaceCount,
                    pods.size(),
                    ready,
                    (int) deployments.stream().filter(item -> inScope(item.namespace, namespaces)).count(),
                    (int) services.stream().filter(item -> inScope(item.namespace, namespaces)).count(),
                    (int) configMaps.stream().filter(item -> inScope(item.namespace, namespaces)).count(),
                    nodes.size(),
                    warnings,
                    phaseCounts,
                    attention.stream().limit(8).toList(),
                    metrics
            );
        }
    }

    @Override
    public List<ResourceView> list(ResourceKind kind, Set<String> namespaces) {
        synchronized (lock) {
            return switch (kind) {
                case POD -> pods(namespaces).stream().map(this::podView).toList();
                case DEPLOYMENT -> deployments.stream()
                        .filter(item -> inScope(item.namespace, namespaces))
                        .map(this::deploymentView)
                        .toList();
                case SERVICE -> services.stream()
                        .filter(item -> inScope(item.namespace, namespaces))
                        .map(this::serviceView)
                        .toList();
                case CONFIG_MAP -> configMaps.stream()
                        .filter(item -> inScope(item.namespace, namespaces))
                        .map(this::configMapView)
                        .toList();
                case NODE -> nodes.stream().map(this::nodeView).toList();
                case EVENT -> events.stream()
                        .filter(item -> inScope(item.namespace, namespaces))
                        .map(this::eventView)
                        .toList();
            };
        }
    }

    @Override
    public ResourceDetail detail(ResourceKind kind, String namespace, String name) {
        synchronized (lock) {
            return switch (kind) {
                case POD -> {
                    Pod pod = pod(namespace, name);
                    yield new ResourceDetail(podView(pod), podYaml(pod), List.of(pod.container));
                }
                case DEPLOYMENT -> {
                    Deployment deployment = deployment(namespace, name);
                    yield new ResourceDetail(deploymentView(deployment), deploymentYaml(deployment), List.of(deployment.container));
                }
                case SERVICE -> {
                    ServiceObj service = service(namespace, name);
                    yield new ResourceDetail(serviceView(service), serviceYaml(service), List.of());
                }
                case CONFIG_MAP -> {
                    ConfigMapObj configMap = configMap(namespace, name);
                    yield new ResourceDetail(configMapView(configMap), configMapYaml(configMap), List.of());
                }
                case NODE -> {
                    NodeObj node = node(name);
                    yield new ResourceDetail(nodeView(node), nodeYaml(node), List.of());
                }
                case EVENT -> {
                    EventObj event = event(namespace, name);
                    yield new ResourceDetail(eventView(event), eventYaml(event), List.of());
                }
            };
        }
    }

    @Override
    public void applyYaml(String yaml) {
        synchronized (lock) {
            boolean applied = false;
            for (String document : yaml.split("(?m)^---\\s*$")) {
                if (document.isBlank()) {
                    continue;
                }
                Object loaded = YamlMaps.load(document);
                if (loaded == null) {
                    continue;
                }
                applyOne(YamlMaps.object(loaded));
                applied = true;
            }
            if (!applied) {
                throw DashboardException.badRequest("Manifest is empty");
            }
        }
        changed();
    }

    @Override
    public void scale(String namespace, String name, int replicas) {
        synchronized (lock) {
            Deployment deployment = deployment(namespace, name);
            deployment.replicas = replicas;
            resize(deployment);
        }
        changed();
    }

    @Override
    public void rolloutRestart(String namespace, String name) {
        synchronized (lock) {
            Deployment deployment = deployment(namespace, name);
            deployment.pods.clear();
            deployment.restartedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
            resize(deployment);
        }
        changed();
    }

    @Override
    public void deletePod(String namespace, String name) {
        synchronized (lock) {
            Pod pod = pod(namespace, name);
            if (pod.owner == null) {
                standalonePods.remove(pod);
            } else {
                Deployment deployment = deployment(pod.namespace, pod.owner);
                boolean broken = !"Running".equals(pod.status);
                deployment.pods.remove(pod);
                Pod replacement = newPod(deployment, broken);
                if (broken) {
                    deployment.pods.add(0, replacement);
                } else {
                    deployment.pods.add(replacement);
                }
            }
        }
        changed();
    }

    @Override
    public void deleteResource(ResourceKind kind, String namespace, String name) {
        switch (kind) {
            case POD -> deletePod(namespace, name);
            case NODE, EVENT -> throw DashboardException.badRequest(kind.apiName() + " cannot be deleted");
            case DEPLOYMENT, SERVICE, CONFIG_MAP -> deleteStoredResource(kind, namespace, name);
        }
    }

    private void deleteStoredResource(ResourceKind kind, String namespace, String name) {
        synchronized (lock) {
            switch (kind) {
                case DEPLOYMENT -> {
                    deployment(namespace, name);
                    deployments.removeIf(item -> item.namespace.equals(namespace) && item.name.equals(name));
                }
                case SERVICE -> {
                    service(namespace, name);
                    services.removeIf(item -> item.namespace.equals(namespace) && item.name.equals(name));
                }
                case CONFIG_MAP -> {
                    configMap(namespace, name);
                    configMaps.removeIf(item -> item.namespace.equals(namespace) && item.name.equals(name));
                }
                case POD, NODE, EVENT -> throw DashboardException.badRequest(kind.apiName() + " cannot be deleted");
            }
        }
        changed();
    }

    @Override
    public LogPage logs(LogRequest request) {
        synchronized (lock) {
            List<Pod> selected = new ArrayList<>();
            for (Pod pod : pods(request.namespaces())) {
                if (!request.pod().isBlank() && !request.pod().equals(pod.name)) {
                    continue;
                }
                if (!request.deployment().isBlank() && !request.deployment().equals(pod.owner)) {
                    continue;
                }
                if (!request.container().isBlank() && !request.container().equals(pod.container)) {
                    continue;
                }
                selected.add(pod);
            }
            String query = request.query().toLowerCase(Locale.ROOT);
            List<LogLine> lines = new ArrayList<>();
            boolean truncated = false;
            int limit = request.tail();
            for (Pod pod : selected) {
                List<String> source = pod.logs;
                int from = Math.max(0, source.size() - limit);
                for (int index = from; index < source.size(); index++) {
                    String text = source.get(index);
                    if (!query.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(query)) {
                        continue;
                    }
                    if (lines.size() >= 2000) {
                        truncated = true;
                        break;
                    }
                    lines.add(new LogLine(pod.namespace, pod.name, pod.container, text));
                }
            }
            return new LogPage(lines, truncated);
        }
    }

    @Override
    public OpenForward openForward(String namespace, String targetKind, String targetName, int remotePort, int localPort) {
        Pod pod;
        synchronized (lock) {
            pod = resolvePod(namespace, targetKind, targetName);
        }
        int port = localPort > 0 ? localPort : ports.getAndIncrement();
        String id = UUID.randomUUID().toString().substring(0, 8);
        ForwardView view = new ForwardView(
                id,
                "demo",
                pod.namespace,
                targetKind.toLowerCase(Locale.ROOT),
                targetName,
                pod.name,
                remotePort,
                port,
                true,
                "http://127.0.0.1:" + port,
                "Demo cluster records the forward and does not open a socket."
        );
        return new OpenForward(view, () -> { });
    }

    @Override
    public void close() {
        // Nothing to release.
    }

    private void applyOne(Map<String, Object> document) {
        String kind = YamlMaps.text(document, "kind");
        Map<String, Object> metadata = YamlMaps.child(document, "metadata");
        String name = YamlMaps.text(metadata, "name");
        String namespace = YamlMaps.text(metadata, "namespace");
        if (name.isEmpty() || namespace.isEmpty()) {
            throw DashboardException.badRequest("metadata.name and metadata.namespace are required");
        }
        switch (kind) {
            case "ConfigMap" -> upsertConfigMap(namespace, name, YamlMaps.child(document, "data"));
            case "Deployment" -> upsertDeployment(namespace, name, document);
            case "Service" -> upsertService(namespace, name, document);
            case "Pod" -> upsertPod(namespace, name, document);
            default -> throw DashboardException.badRequest("Demo cluster can apply ConfigMap, Deployment, Service, and Pod");
        }
    }

    private void upsertConfigMap(String namespace, String name, Map<String, Object> data) {
        Map<String, String> values = new LinkedHashMap<>();
        data.forEach((key, value) -> values.put(key, String.valueOf(value)));
        configMaps.removeIf(item -> item.namespace.equals(namespace) && item.name.equals(name));
        configMaps.add(new ConfigMapObj(namespace, name, values, Instant.now()));
        rememberNamespace(namespace);
    }

    private void upsertDeployment(String namespace, String name, Map<String, Object> document) {
        Map<String, Object> spec = YamlMaps.child(document, "spec");
        Map<String, Object> container = firstContainer(spec);
        int replicas = YamlMaps.integer(spec, "replicas", 1);
        deployments.removeIf(item -> item.namespace.equals(namespace) && item.name.equals(name));
        Deployment deployment = new Deployment(
                namespace,
                name,
                textOr(container, "image", "example/app:latest"),
                textOr(container, "name", "app"),
                replicas,
                false,
                labelsOr(YamlMaps.child(document, "metadata"), name),
                Instant.now()
        );
        resize(deployment);
        deployments.add(deployment);
        rememberNamespace(namespace);
    }

    private void upsertService(String namespace, String name, Map<String, Object> document) {
        Map<String, Object> spec = YamlMaps.child(document, "spec");
        Map<String, Object> port = YamlMaps.children(spec.get("ports")).stream().findFirst().orElse(Map.of());
        int servicePort = YamlMaps.integer(port, "port", 80);
        int target = YamlMaps.integer(port, "targetPort", servicePort);
        services.removeIf(item -> item.namespace.equals(namespace) && item.name.equals(name));
        services.add(new ServiceObj(
                namespace,
                name,
                textOr(spec, "type", "ClusterIP"),
                textOr(spec, "clusterIP", "10.0.0." + (20 + services.size())),
                servicePort + ":" + target,
                stringMap(YamlMaps.child(spec, "selector")),
                Instant.now()
        ));
        rememberNamespace(namespace);
    }

    private void upsertPod(String namespace, String name, Map<String, Object> document) {
        for (Deployment deployment : deployments) {
            if (!deployment.namespace.equals(namespace)) {
                continue;
            }
            for (int index = 0; index < deployment.pods.size(); index++) {
                if (deployment.pods.get(index).name.equals(name)) {
                    deployment.pods.set(index, reappliedPod(deployment.pods.get(index), document));
                    return;
                }
            }
        }
        standalonePods.removeIf(item -> item.namespace.equals(namespace) && item.name.equals(name));
        standalonePods.add(appliedPod(namespace, name, null, document));
        rememberNamespace(namespace);
    }

    private Pod reappliedPod(Pod existing, Map<String, Object> document) {
        Map<String, Object> spec = YamlMaps.child(document, "spec");
        Map<String, Object> container = firstContainer(spec);
        return new Pod(
                existing.namespace,
                existing.name,
                existing.owner,
                textOr(spec, "nodeName", existing.node),
                textOr(container, "image", existing.image),
                textOr(container, "name", existing.container),
                existing.status,
                existing.ready,
                existing.desired,
                labelsOr(YamlMaps.child(document, "metadata"), existing.name),
                existing.created,
                existing.logs,
                existing.cpu,
                existing.memory,
                existing.restarts
        );
    }

    private Pod appliedPod(String namespace, String name, String owner, Map<String, Object> document) {
        Map<String, Object> spec = YamlMaps.child(document, "spec");
        Map<String, Object> container = firstContainer(spec);
        return new Pod(
                namespace,
                name,
                owner,
                textOr(spec, "nodeName", "node-a"),
                textOr(container, "image", "example/app:latest"),
                textOr(container, "name", "app"),
                "Running",
                1,
                1,
                labelsOr(YamlMaps.child(document, "metadata"), name),
                Instant.now(),
                List.of(Instant.now().truncatedTo(ChronoUnit.SECONDS) + " applied pod " + name + " is running"),
                "10m",
                "64Mi",
                0
        );
    }

    private Map<String, Object> firstContainer(Map<String, Object> spec) {
        Map<String, Object> template = YamlMaps.child(YamlMaps.child(spec, "template"), "spec");
        Map<String, Object> source = template.isEmpty() ? spec : template;
        return YamlMaps.children(source.get("containers")).stream().findFirst().orElse(Map.of());
    }

    private void resize(Deployment deployment) {
        while (deployment.pods.size() > deployment.replicas) {
            deployment.pods.remove(deployment.pods.size() - 1);
        }
        while (deployment.pods.size() < deployment.replicas) {
            boolean broken = deployment.broken && deployment.pods.isEmpty();
            deployment.pods.add(newPod(deployment, broken));
        }
        if (deployment.broken && !deployment.pods.isEmpty()) {
            Pod first = deployment.pods.get(0);
            first.status = "CrashLoopBackOff";
            first.ready = 0;
            first.logs = crashLogs();
            first.restarts = Math.max(first.restarts, 4);
        }
    }

    private Pod newPod(Deployment deployment, boolean broken) {
        sequence++;
        String node = sequence % 2 == 0 ? "node-b" : "node-a";
        return new Pod(
                deployment.namespace,
                deployment.name + "-" + Integer.toHexString(sequence),
                deployment.name,
                node,
                deployment.image,
                deployment.container,
                broken ? "CrashLoopBackOff" : "Running",
                broken ? 0 : 1,
                1,
                deployment.labels,
                Instant.now(),
                broken ? crashLogs() : healthyLogs(deployment.name),
                broken ? "5m" : "20m",
                broken ? "64Mi" : "128Mi",
                broken ? 4 : 0
        );
    }

    private Pod resolvePod(String namespace, String targetKind, String targetName) {
        String kind = targetKind == null ? "" : targetKind.toLowerCase(Locale.ROOT);
        if ("pod".equals(kind) || "pods".equals(kind)) {
            return pod(namespace, targetName);
        }
        if ("service".equals(kind) || "services".equals(kind) || "svc".equals(kind)) {
            ServiceObj service = service(namespace, targetName);
            return pods(Set.of(namespace)).stream()
                    .filter(pod -> "Running".equals(pod.status))
                    .filter(pod -> service.selector.entrySet().stream()
                            .allMatch(entry -> entry.getValue().equals(pod.labels.get(entry.getKey()))))
                    .findFirst()
                    .orElseThrow(() -> DashboardException.notFound("No ready pod matches service " + targetName));
        }
        throw DashboardException.badRequest("Port forward target must be a pod or a service");
    }

    private List<Pod> pods(Set<String> namespaces) {
        List<Pod> pods = new ArrayList<>(standalonePods);
        for (Deployment deployment : deployments) {
            pods.addAll(deployment.pods);
        }
        return pods.stream()
                .filter(pod -> inScope(pod.namespace, namespaces))
                .sorted(Comparator.comparing((Pod pod) -> pod.namespace).thenComparing(pod -> pod.name))
                .toList();
    }

    private Pod pod(String namespace, String name) {
        return pods(Set.of()).stream()
                .filter(item -> item.namespace.equals(namespace) && item.name.equals(name))
                .findFirst()
                .orElseThrow(() -> DashboardException.notFound("Pod " + namespace + "/" + name + " was not found"));
    }

    private Deployment deployment(String namespace, String name) {
        return deployments.stream()
                .filter(item -> item.namespace.equals(namespace) && item.name.equals(name))
                .findFirst()
                .orElseThrow(() -> DashboardException.notFound("Deployment " + namespace + "/" + name + " was not found"));
    }

    private ServiceObj service(String namespace, String name) {
        return services.stream()
                .filter(item -> item.namespace.equals(namespace) && item.name.equals(name))
                .findFirst()
                .orElseThrow(() -> DashboardException.notFound("Service " + namespace + "/" + name + " was not found"));
    }

    private ConfigMapObj configMap(String namespace, String name) {
        return configMaps.stream()
                .filter(item -> item.namespace.equals(namespace) && item.name.equals(name))
                .findFirst()
                .orElseThrow(() -> DashboardException.notFound("ConfigMap " + namespace + "/" + name + " was not found"));
    }

    private NodeObj node(String name) {
        return nodes.stream()
                .filter(item -> item.name.equals(name))
                .findFirst()
                .orElseThrow(() -> DashboardException.notFound("Node " + name + " was not found"));
    }

    private EventObj event(String namespace, String name) {
        return events.stream()
                .filter(item -> item.namespace.equals(namespace) && item.name.equals(name))
                .findFirst()
                .orElseThrow(() -> DashboardException.notFound("Event " + namespace + "/" + name + " was not found"));
    }

    private ResourceView podView(Pod pod) {
        return new ResourceView(
                "Pod",
                pod.namespace,
                pod.name,
                pod.status,
                pod.node,
                List.of(pod.image),
                pod.labels,
                pod.created.toString(),
                pod.ready,
                pod.desired,
                pod.ready + "/" + pod.desired + " on " + pod.node,
                Map.of("restarts", Integer.toString(pod.restarts), "cpu", pod.cpu, "memory", pod.memory)
        );
    }

    private ResourceView deploymentView(Deployment deployment) {
        int ready = (int) deployment.pods.stream().filter(pod -> "Running".equals(pod.status)).count();
        String status = ready == deployment.replicas ? "Available" : "Progressing";
        return new ResourceView(
                "Deployment",
                deployment.namespace,
                deployment.name,
                status,
                "",
                List.of(deployment.image),
                deployment.labels,
                deployment.created.toString(),
                ready,
                deployment.replicas,
                ready + "/" + deployment.replicas + " " + deployment.image,
                Map.of("restartedAt", deployment.restartedAt)
        );
    }

    private ResourceView serviceView(ServiceObj service) {
        return new ResourceView(
                "Service",
                service.namespace,
                service.name,
                service.type,
                "",
                List.of(),
                service.selector,
                service.created.toString(),
                0,
                0,
                service.type + " " + service.clusterIp + " " + service.ports,
                Map.of("type", service.type, "clusterIP", service.clusterIp, "ports", service.ports)
        );
    }

    private ResourceView configMapView(ConfigMapObj configMap) {
        String keys = String.join(", ", configMap.data.keySet());
        return new ResourceView(
                "ConfigMap",
                configMap.namespace,
                configMap.name,
                "Active",
                "",
                List.of(),
                Map.of(),
                configMap.created.toString(),
                configMap.data.size(),
                configMap.data.size(),
                keys,
                Map.of("keys", keys)
        );
    }

    private ResourceView nodeView(NodeObj node) {
        return new ResourceView(
                "Node",
                "",
                node.name,
                node.status,
                node.name,
                List.of(),
                Map.of("role", node.roles),
                node.created.toString(),
                node.pods,
                node.pods,
                node.roles + " cpu " + node.cpu + " memory " + node.memory,
                Map.of("roles", node.roles, "cpu", node.cpu, "memory", node.memory)
        );
    }

    private ResourceView eventView(EventObj event) {
        return new ResourceView(
                "Event",
                event.namespace,
                event.name,
                event.type,
                "",
                List.of(),
                Map.of(),
                event.created.toString(),
                0,
                0,
                event.reason + ": " + event.message,
                Map.of("type", event.type, "reason", event.reason, "message", event.message, "involved", event.involved)
        );
    }

    private String podYaml(Pod pod) {
        return """
                apiVersion: v1
                kind: Pod
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                %s
                spec:
                  nodeName: %s
                  containers:
                    - name: %s
                      image: %s
                status:
                  phase: %s
                """.formatted(pod.name, pod.namespace, labelYaml(pod.labels), pod.node, pod.container, pod.image, pod.status);
    }

    private String deploymentYaml(Deployment deployment) {
        return """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: %s
                  namespace: %s
                spec:
                  replicas: %d
                  selector:
                    matchLabels:
                      app: %s
                  template:
                    spec:
                      containers:
                        - name: %s
                          image: %s
                """.formatted(deployment.name, deployment.namespace, deployment.replicas, deployment.name, deployment.container, deployment.image);
    }

    private String serviceYaml(ServiceObj service) {
        String[] ports = service.ports.split(":", 2);
        String targetPort = ports.length > 1 ? ports[1] : ports[0];
        String selector = service.selector.isEmpty() ? "  selector: {}" : "  selector:\n" + labelYaml(service.selector);
        return """
                apiVersion: v1
                kind: Service
                metadata:
                  name: %s
                  namespace: %s
                spec:
                  type: %s
                  clusterIP: %s
                  ports:
                    - port: %s
                      targetPort: %s
                %s
                """.formatted(service.name, service.namespace, service.type, service.clusterIp, ports[0], targetPort, selector);
    }

    private String configMapYaml(ConfigMapObj configMap) {
        StringBuilder data = new StringBuilder();
        configMap.data.forEach((key, value) -> data.append("  ").append(key).append(": \"").append(value).append("\"\n"));
        return """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: %s
                  namespace: %s
                data:
                %s""".formatted(configMap.name, configMap.namespace, data);
    }

    private String nodeYaml(NodeObj node) {
        return """
                apiVersion: v1
                kind: Node
                metadata:
                  name: %s
                  labels:
                    role: %s
                status:
                  conditions:
                    - type: Ready
                      status: "%s"
                """.formatted(node.name, node.roles, "Ready".equals(node.status) ? "True" : "False");
    }

    private String eventYaml(EventObj event) {
        return """
                apiVersion: v1
                kind: Event
                metadata:
                  name: %s
                  namespace: %s
                type: %s
                reason: %s
                message: "%s"
                involvedObject: %s
                """.formatted(event.name, event.namespace, event.type, event.reason, event.message, event.involved);
    }

    private static String labelYaml(Map<String, String> labels) {
        StringBuilder builder = new StringBuilder();
        labels.forEach((key, value) -> builder.append("    ").append(key).append(": ").append(value).append('\n'));
        return builder.toString().stripTrailing();
    }

    private void changed() {
        listener.accept("demo");
    }

    private void seed() {
        Instant created = Instant.now().minus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        deployments.add(deployment("shop", "storefront", "ghcr.io/example/storefront:1.4.2", "http", 3, false, created));
        deployments.add(deployment("shop", "cart", "ghcr.io/example/cart:0.9.1", "app", 2, false, created));
        deployments.add(deployment("payments", "ledger", "ghcr.io/example/ledger:2.3.0", "ledger", 2, true, created));
        deployments.add(deployment("observability", "collector", "grafana/agent:0.40.0", "agent", 1, false, created));
        for (Deployment deployment : deployments) {
            resize(deployment);
        }
        renameSeedPods();
        services.add(new ServiceObj("shop", "storefront", "ClusterIP", "10.0.0.20", "80:8080", Map.of("app", "storefront"), created));
        services.add(new ServiceObj("shop", "cart", "ClusterIP", "10.0.0.21", "80:8080", Map.of("app", "cart"), created));
        services.add(new ServiceObj("payments", "ledger", "ClusterIP", "10.0.0.30", "8080:8080", Map.of("app", "ledger"), created));
        services.add(new ServiceObj("observability", "collector", "ClusterIP", "10.0.0.40", "4317:4317", Map.of("app", "collector"), created));
        configMaps.add(new ConfigMapObj("shop", "storefront-config", Map.of("THEME", "paper", "API_URL", "http://cart"), created));
        configMaps.add(new ConfigMapObj("payments", "ledger-config", Map.of("DB_HOST", "db.payments.svc", "DB_PORT", "5432"), created));
        configMaps.add(new ConfigMapObj("observability", "collector-config", Map.of("SCRAPE", "30s"), created));
        nodes.add(new NodeObj("node-a", "Ready", "control-plane,worker", "500m/4", "2Gi/8Gi", 5, created));
        nodes.add(new NodeObj("node-b", "Ready", "worker", "300m/4", "1Gi/8Gi", 3, created));
        events.add(new EventObj(
                "payments",
                "ledger-backoff",
                "Warning",
                "BackOff",
                "Back-off restarting failed container ledger in pod ledger-a",
                "Pod/ledger-a",
                created.plus(4, ChronoUnit.HOURS)
        ));
        events.add(new EventObj(
                "shop",
                "storefront-started",
                "Normal",
                "Started",
                "Started container http",
                "Pod/storefront-a",
                created.plus(1, ChronoUnit.MINUTES)
        ));
        rememberSeedNamespaces();
    }

    private void rememberSeedNamespaces() {
        deployments.forEach(item -> rememberNamespace(item.namespace));
        services.forEach(item -> rememberNamespace(item.namespace));
        configMaps.forEach(item -> rememberNamespace(item.namespace));
        events.forEach(item -> rememberNamespace(item.namespace));
        standalonePods.forEach(item -> rememberNamespace(item.namespace));
    }

    private void rememberNamespace(String namespace) {
        if (namespace != null && !namespace.isBlank()) {
            namespaceNames.add(namespace);
        }
    }

    private void renameSeedPods() {
        rename("shop", "storefront", List.of("storefront-a", "storefront-b", "storefront-c"), List.of("node-a", "node-b", "node-a"));
        rename("shop", "cart", List.of("cart-a", "cart-b"), List.of("node-b", "node-a"));
        rename("payments", "ledger", List.of("ledger-a", "ledger-b"), List.of("node-a", "node-b"));
        rename("observability", "collector", List.of("collector-a"), List.of("node-a"));
    }

    private void rename(String namespace, String name, List<String> names, List<String> nodes) {
        Deployment deployment = deployment(namespace, name);
        for (int index = 0; index < deployment.pods.size() && index < names.size(); index++) {
            deployment.pods.get(index).name = names.get(index);
            deployment.pods.get(index).node = nodes.get(index);
        }
    }

    private static Deployment deployment(
            String namespace,
            String name,
            String image,
            String container,
            int replicas,
            boolean broken,
            Instant created
    ) {
        return new Deployment(namespace, name, image, container, replicas, broken, Map.of("app", name), created);
    }

    private static List<String> healthyLogs(String name) {
        return List.of(
                "2026-04-12T08:01:11Z " + name + " listening",
                "2026-04-12T08:01:12Z GET /health 200",
                "2026-04-12T08:01:18Z GET /api/items 200"
        );
    }

    private static List<String> crashLogs() {
        return List.of(
                "2026-04-12T08:01:02Z ERROR ledger failed to open database",
                "java.net.ConnectException: Connection refused: db.payments.svc:5432",
                "2026-04-12T08:01:04Z retrying in 2s"
        );
    }

    private static boolean inScope(String namespace, Set<String> namespaces) {
        return namespaces == null || namespaces.isEmpty() || namespaces.contains(namespace);
    }

    private static String textOr(Map<String, Object> map, String key, String fallback) {
        String value = YamlMaps.text(map, key);
        return value.isEmpty() ? fallback : value;
    }

    private static Map<String, String> labelsOr(Map<String, Object> metadata, String name) {
        Map<String, String> labels = stringMap(YamlMaps.child(metadata, "labels"));
        if (labels.isEmpty()) {
            return Map.of("app", name);
        }
        return labels;
    }

    private static Map<String, String> stringMap(Map<String, Object> source) {
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, String.valueOf(value)));
        return Map.copyOf(copy);
    }

    private static final class Deployment {
        private final String namespace;
        private final String name;
        private final String image;
        private final String container;
        private int replicas;
        private final boolean broken;
        private final Map<String, String> labels;
        private final Instant created;
        private String restartedAt = "";
        private final List<Pod> pods = new ArrayList<>();

        private Deployment(
                String namespace,
                String name,
                String image,
                String container,
                int replicas,
                boolean broken,
                Map<String, String> labels,
                Instant created
        ) {
            this.namespace = namespace;
            this.name = name;
            this.image = image;
            this.container = container;
            this.replicas = replicas;
            this.broken = broken;
            this.labels = labels;
            this.created = created;
        }
    }

    private static final class Pod {
        private final String namespace;
        private String name;
        private final String owner;
        private String node;
        private final String image;
        private final String container;
        private String status;
        private int ready;
        private final int desired;
        private final Map<String, String> labels;
        private final Instant created;
        private List<String> logs;
        private final String cpu;
        private final String memory;
        private int restarts;

        private Pod(
                String namespace,
                String name,
                String owner,
                String node,
                String image,
                String container,
                String status,
                int ready,
                int desired,
                Map<String, String> labels,
                Instant created,
                List<String> logs,
                String cpu,
                String memory,
                int restarts
        ) {
            this.namespace = namespace;
            this.name = name;
            this.owner = owner;
            this.node = node;
            this.image = image;
            this.container = container;
            this.status = status;
            this.ready = ready;
            this.desired = desired;
            this.labels = labels;
            this.created = created;
            this.logs = logs;
            this.cpu = cpu;
            this.memory = memory;
            this.restarts = restarts;
        }
    }

    private record ServiceObj(
            String namespace,
            String name,
            String type,
            String clusterIp,
            String ports,
            Map<String, String> selector,
            Instant created
    ) {
    }

    private record ConfigMapObj(String namespace, String name, Map<String, String> data, Instant created) {
    }

    private record NodeObj(
            String name,
            String status,
            String roles,
            String cpu,
            String memory,
            int pods,
            Instant created
    ) {
    }

    private record EventObj(
            String namespace,
            String name,
            String type,
            String reason,
            String message,
            String involved,
            Instant created
    ) {
    }
}
