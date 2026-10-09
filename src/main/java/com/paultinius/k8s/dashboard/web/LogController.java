package com.paultinius.k8s.dashboard.web;

import com.paultinius.k8s.dashboard.cluster.ClusterRegistry;
import com.paultinius.k8s.dashboard.cluster.LogRequest;
import com.paultinius.k8s.dashboard.cluster.QuerySupport;
import com.paultinius.k8s.dashboard.cluster.ResourceKind;
import com.paultinius.k8s.dashboard.live.LogStreams;
import com.paultinius.k8s.dashboard.model.LogPage;
import com.paultinius.k8s.dashboard.model.ResourceDetail;
import com.paultinius.k8s.dashboard.model.ResourceView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class LogController {

    private final ClusterRegistry clusters;
    private final LogStreams streams;

    public LogController(ClusterRegistry clusters, LogStreams streams) {
        this.clusters = clusters;
        this.streams = streams;
    }

    @GetMapping("/logs")
    public LogPage logs(
            @RequestParam(required = false) String cluster,
            @RequestParam(required = false) List<String> namespaces,
            @RequestParam(required = false) String deployment,
            @RequestParam(required = false) String pod,
            @RequestParam(required = false) String container,
            @RequestParam(defaultValue = "100") int tail,
            @RequestParam(required = false) String q
    ) {
        return clusters.require(cluster).logs(request(namespaces, deployment, pod, container, tail, q));
    }

    @GetMapping(path = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) String cluster,
            @RequestParam(required = false) List<String> namespaces,
            @RequestParam(required = false) String deployment,
            @RequestParam(required = false) String pod,
            @RequestParam(required = false) String container,
            @RequestParam(defaultValue = "100") int tail,
            @RequestParam(required = false) String q
    ) {
        String clusterId = clusters.require(cluster).info().id();
        return streams.open(clusterId, request(namespaces, deployment, pod, container, tail, q));
    }

    /**
     * Container names for pods matching the given scope, used to drive the
     * container field's prediction list on the Logs tab.
     */
    @GetMapping("/containers")
    public List<String> containers(
            @RequestParam(required = false) String cluster,
            @RequestParam(required = false) List<String> namespaces,
            @RequestParam(required = false) String deployment,
            @RequestParam(required = false) String pod
    ) {
        var client = clusters.require(cluster);
        Set<String> scope = QuerySupport.namespaces(namespaces);
        String podFilter = pod == null ? "" : pod.trim().toLowerCase(Locale.ROOT);
        String deploymentFilter = deployment == null ? "" : deployment.trim().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        int inspected = 0;
        for (ResourceView view : client.list(ResourceKind.POD, scope)) {
            if (inspected >= 8) {
                break;
            }
            String name = view.name().toLowerCase(Locale.ROOT);
            if (!podFilter.isBlank() && !name.contains(podFilter)) {
                continue;
            }
            if (!deploymentFilter.isBlank() && !name.startsWith(deploymentFilter)) {
                continue;
            }
            inspected++;
            ResourceDetail detail = client.detail(ResourceKind.POD, view.namespace(), view.name());
            names.addAll(detail.containers());
        }
        return names.stream().sorted().toList();
    }

    private static LogRequest request(
            List<String> namespaces,
            String deployment,
            String pod,
            String container,
            int tail,
            String query
    ) {
        return new LogRequest(QuerySupport.namespaces(namespaces), deployment, pod, container, tail, query);
    }
}
