package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.model.ResourceDetail;
import com.paultinius.k8s.dashboard.model.ResourceView;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.utils.Serialization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LiveViews {

    private LiveViews() {
    }

    static ResourceDetail detail(ResourceView view, HasMetadata resource, List<String> containers) {
        return new ResourceDetail(view, Serialization.asYaml(resource), containers);
    }

    static ResourceView pod(Pod pod) {
        ObjectMeta meta = meta(pod);
        String status = podStatus(pod);
        int desired = pod.getSpec() == null || pod.getSpec().getContainers() == null
                ? 0
                : pod.getSpec().getContainers().size();
        int ready = readyContainers(pod);
        return new ResourceView(
                "Pod",
                namespace(meta),
                name(meta),
                status,
                pod.getSpec() == null || pod.getSpec().getNodeName() == null ? "" : pod.getSpec().getNodeName(),
                images(pod.getSpec()),
                labels(meta),
                created(meta),
                ready,
                desired,
                ready + "/" + desired,
                Map.of("restarts", Integer.toString(restarts(pod)))
        );
    }

    static ResourceView deployment(Deployment deployment) {
        ObjectMeta meta = meta(deployment);
        int desired = deployment.getSpec() == null || deployment.getSpec().getReplicas() == null
                ? 0
                : deployment.getSpec().getReplicas();
        int ready = deployment.getStatus() == null || deployment.getStatus().getReadyReplicas() == null
                ? 0
                : deployment.getStatus().getReadyReplicas();
        String restarted = "";
        if (deployment.getSpec() != null
                && deployment.getSpec().getTemplate() != null
                && deployment.getSpec().getTemplate().getMetadata() != null
                && deployment.getSpec().getTemplate().getMetadata().getAnnotations() != null) {
            restarted = deployment.getSpec().getTemplate().getMetadata().getAnnotations()
                    .getOrDefault("kubectl.kubernetes.io/restartedAt", "");
        }
        List<String> images = images(deployment.getSpec() == null || deployment.getSpec().getTemplate() == null
                ? null
                : deployment.getSpec().getTemplate().getSpec());
        return new ResourceView(
                "Deployment",
                namespace(meta),
                name(meta),
                ready >= desired && desired > 0 ? "Available" : "Progressing",
                "",
                images,
                labels(meta),
                created(meta),
                ready,
                desired,
                ready + "/" + desired,
                Map.of("restartedAt", restarted)
        );
    }

    static ResourceView service(Service service) {
        ObjectMeta meta = meta(service);
        String type = service.getSpec() == null || service.getSpec().getType() == null ? "" : service.getSpec().getType();
        String clusterIp = service.getSpec() == null || service.getSpec().getClusterIP() == null
                ? ""
                : service.getSpec().getClusterIP();
        String ports = ports(service);
        Map<String, String> selector = service.getSpec() == null || service.getSpec().getSelector() == null
                ? Map.of()
                : service.getSpec().getSelector();
        return new ResourceView(
                "Service",
                namespace(meta),
                name(meta),
                type,
                "",
                List.of(),
                selector,
                created(meta),
                0,
                0,
                type + " " + clusterIp + " " + ports,
                Map.of("type", type, "clusterIP", clusterIp, "ports", ports)
        );
    }

    static ResourceView configMap(ConfigMap configMap) {
        ObjectMeta meta = meta(configMap);
        String keys = configMap.getData() == null ? "" : String.join(", ", configMap.getData().keySet());
        int count = configMap.getData() == null ? 0 : configMap.getData().size();
        return new ResourceView(
                "ConfigMap",
                namespace(meta),
                name(meta),
                "Active",
                "",
                List.of(),
                labels(meta),
                created(meta),
                count,
                count,
                keys,
                Map.of("keys", keys)
        );
    }

    static ResourceView node(Node node, String cpu, String memory) {
        ObjectMeta meta = meta(node);
        String status = nodeStatus(node);
        String roles = roles(meta.getLabels());
        return new ResourceView(
                "Node",
                "",
                name(meta),
                status,
                name(meta),
                List.of(),
                labels(meta),
                created(meta),
                0,
                0,
                roles,
                Map.of("roles", roles, "cpu", cpu == null ? "" : cpu, "memory", memory == null ? "" : memory)
        );
    }

    static ResourceView event(Event event) {
        ObjectMeta meta = meta(event);
        String type = event.getType() == null ? "" : event.getType();
        String reason = event.getReason() == null ? "" : event.getReason();
        String message = event.getMessage() == null ? "" : event.getMessage();
        String involved = event.getInvolvedObject() == null
                ? ""
                : event.getInvolvedObject().getKind() + "/" + event.getInvolvedObject().getName();
        return new ResourceView(
                "Event",
                namespace(meta),
                name(meta),
                type,
                "",
                List.of(),
                Map.of(),
                created(meta),
                0,
                0,
                reason + ": " + message,
                Map.of("type", type, "reason", reason, "message", message, "involved", involved)
        );
    }

    static List<String> containerNames(PodSpec spec) {
        if (spec == null || spec.getContainers() == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Container container : spec.getContainers()) {
            if (container.getName() != null) {
                names.add(container.getName());
            }
        }
        return List.copyOf(names);
    }

    static String podStatus(Pod pod) {
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                if (status.getState() != null && status.getState().getWaiting() != null
                        && status.getState().getWaiting().getReason() != null
                        && !status.getState().getWaiting().getReason().isBlank()) {
                    return status.getState().getWaiting().getReason();
                }
            }
        }
        if (pod.getStatus() == null || pod.getStatus().getPhase() == null) {
            return "Unknown";
        }
        return pod.getStatus().getPhase();
    }

    private static int readyContainers(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return 0;
        }
        int ready = 0;
        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            if (Boolean.TRUE.equals(status.getReady())) {
                ready++;
            }
        }
        return ready;
    }

    private static int restarts(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return 0;
        }
        int restarts = 0;
        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            if (status.getRestartCount() != null) {
                restarts += status.getRestartCount();
            }
        }
        return restarts;
    }

    private static List<String> images(PodSpec spec) {
        if (spec == null || spec.getContainers() == null) {
            return List.of();
        }
        List<String> images = new ArrayList<>();
        for (Container container : spec.getContainers()) {
            if (container.getImage() != null) {
                images.add(container.getImage());
            }
        }
        return List.copyOf(images);
    }

    private static String ports(Service service) {
        if (service.getSpec() == null || service.getSpec().getPorts() == null) {
            return "";
        }
        List<String> ports = new ArrayList<>();
        for (ServicePort port : service.getSpec().getPorts()) {
            String target = port.getTargetPort() == null ? "" : port.getTargetPort().toString();
            ports.add(port.getPort() + (target.isBlank() ? "" : ":" + target));
        }
        return String.join(", ", ports);
    }

    private static String nodeStatus(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return "Unknown";
        }
        for (NodeCondition condition : node.getStatus().getConditions()) {
            if ("Ready".equals(condition.getType())) {
                return "True".equals(condition.getStatus()) ? "Ready" : "NotReady";
            }
        }
        return "Unknown";
    }

    private static String roles(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        List<String> roles = new ArrayList<>();
        labels.forEach((key, value) -> {
            String prefix = "node-role.kubernetes.io/";
            if (key.startsWith(prefix)) {
                roles.add(key.substring(prefix.length()));
            }
        });
        if (roles.isEmpty() && labels.get("kubernetes.io/role") != null) {
            roles.add(labels.get("kubernetes.io/role"));
        }
        return String.join(",", roles);
    }

    private static ObjectMeta meta(HasMetadata resource) {
        return resource.getMetadata() == null ? new ObjectMeta() : resource.getMetadata();
    }

    private static String name(ObjectMeta meta) {
        return meta.getName() == null ? "" : meta.getName();
    }

    private static String namespace(ObjectMeta meta) {
        return meta.getNamespace() == null ? "" : meta.getNamespace();
    }

    private static String created(ObjectMeta meta) {
        return meta.getCreationTimestamp() == null ? "" : meta.getCreationTimestamp();
    }

    private static Map<String, String> labels(ObjectMeta meta) {
        if (meta.getLabels() == null || meta.getLabels().isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        meta.getLabels().forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }
}
