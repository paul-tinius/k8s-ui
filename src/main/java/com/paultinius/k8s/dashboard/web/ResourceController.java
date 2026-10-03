package com.paultinius.k8s.dashboard.web;

import com.paultinius.k8s.dashboard.cluster.ClusterRegistry;
import com.paultinius.k8s.dashboard.cluster.QuerySupport;
import com.paultinius.k8s.dashboard.cluster.ResourceFilter;
import com.paultinius.k8s.dashboard.cluster.ResourceKind;
import com.paultinius.k8s.dashboard.model.Overview;
import com.paultinius.k8s.dashboard.model.ResourceDetail;
import com.paultinius.k8s.dashboard.model.ResourceView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ResourceController {

    private final ClusterRegistry clusters;

    public ResourceController(ClusterRegistry clusters) {
        this.clusters = clusters;
    }

    @GetMapping("/overview")
    public Overview overview(
            @RequestParam(required = false) String cluster,
            @RequestParam(required = false) List<String> namespaces
    ) {
        return clusters.require(cluster).overview(QuerySupport.namespaces(namespaces));
    }

    @GetMapping("/resources")
    public List<ResourceView> resources(
            @RequestParam(required = false) String cluster,
            @RequestParam String kind,
            @RequestParam(required = false) List<String> namespaces,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> label,
            @RequestParam(required = false) String image,
            @RequestParam(required = false) String node
    ) {
        var client = clusters.require(cluster);
        return ResourceFilter.apply(
                client.list(ResourceKind.from(kind), QuerySupport.namespaces(namespaces)),
                q,
                label,
                image,
                node
        );
    }

    @GetMapping("/resources/{kind}/{namespace}/{name}")
    public ResourceDetail detail(
            @RequestParam(required = false) String cluster,
            @PathVariable String kind,
            @PathVariable String namespace,
            @PathVariable String name
    ) {
        return clusters.require(cluster).detail(ResourceKind.from(kind), QuerySupport.scope(namespace), name);
    }

    @PostMapping("/apply")
    public Map<String, String> apply(
            @RequestParam(required = false) String cluster,
            @Valid @RequestBody ApplyRequest request
    ) {
        clusters.require(cluster).applyYaml(request.yaml());
        return Map.of("status", "applied");
    }

    @PostMapping("/deployments/{namespace}/{name}/scale")
    public Map<String, String> scale(
            @RequestParam(required = false) String cluster,
            @PathVariable String namespace,
            @PathVariable String name,
            @Valid @RequestBody ScaleRequest request
    ) {
        clusters.require(cluster).scale(namespace, name, request.replicas());
        return Map.of("status", "scaled");
    }

    @PostMapping("/deployments/{namespace}/{name}/restart")
    public Map<String, String> restart(
            @RequestParam(required = false) String cluster,
            @PathVariable String namespace,
            @PathVariable String name
    ) {
        clusters.require(cluster).rolloutRestart(namespace, name);
        return Map.of("status", "restarted");
    }

    @DeleteMapping("/resources/{kind}/{namespace}/{name}")
    public Map<String, String> deleteResource(
            @RequestParam(required = false) String cluster,
            @PathVariable String kind,
            @PathVariable String namespace,
            @PathVariable String name
    ) {
        clusters.require(cluster).deleteResource(ResourceKind.from(kind), QuerySupport.scope(namespace), name);
        return Map.of("status", "deleted");
    }

    @DeleteMapping("/pods/{namespace}/{name}")
    public Map<String, String> deletePod(
            @RequestParam(required = false) String cluster,
            @PathVariable String namespace,
            @PathVariable String name
    ) {
        clusters.require(cluster).deletePod(namespace, name);
        return Map.of("status", "deleted");
    }

    public record ApplyRequest(@NotBlank @Size(max = 1_000_000) String yaml) {
    }

    public record ScaleRequest(@Min(0) @Max(100) int replicas) {
    }
}
