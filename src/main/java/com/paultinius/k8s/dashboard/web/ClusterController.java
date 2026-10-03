package com.paultinius.k8s.dashboard.web;

import com.paultinius.k8s.dashboard.cluster.ClusterRegistry;
import com.paultinius.k8s.dashboard.model.ClusterInfo;
import com.paultinius.k8s.dashboard.model.NamespaceView;
import jakarta.validation.Valid;
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
public class ClusterController {

    private final ClusterRegistry clusters;

    public ClusterController(ClusterRegistry clusters) {
        this.clusters = clusters;
    }

    @GetMapping("/clusters")
    public List<ClusterInfo> list() {
        return clusters.list();
    }

    @PostMapping("/clusters")
    public List<ClusterInfo> add(@Valid @RequestBody CreateClusterRequest request) {
        return clusters.addKubeconfig(request.kubeconfig(), request.name());
    }

    @PostMapping("/clusters/{id}/activate")
    public ClusterInfo activate(@PathVariable String id) {
        return clusters.activate(id);
    }

    @DeleteMapping("/clusters/{id}")
    public void delete(@PathVariable String id) {
        clusters.delete(id);
    }

    @GetMapping("/namespaces")
    public List<String> namespaces(@RequestParam(required = false) String cluster) {
        return clusters.require(cluster).namespaces();
    }

    @GetMapping("/namespace-details")
    public List<NamespaceView> namespaceDetails(@RequestParam(required = false) String cluster) {
        return clusters.require(cluster).namespaceDetails();
    }

    @PostMapping("/namespaces")
    public List<NamespaceView> createNamespace(
            @RequestParam(required = false) String cluster,
            @Valid @RequestBody CreateNamespaceRequest request
    ) {
        var client = clusters.require(cluster);
        client.createNamespace(request.name());
        return client.namespaceDetails();
    }

    @DeleteMapping("/namespaces/{name}")
    public Map<String, String> deleteNamespace(
            @RequestParam(required = false) String cluster,
            @PathVariable String name
    ) {
        clusters.require(cluster).deleteNamespace(name);
        return Map.of("status", "deleted");
    }

    public record CreateNamespaceRequest(@NotBlank @Size(max = 63) String name) {
    }

    public record CreateClusterRequest(
            String name,
            @NotBlank @Size(max = 1_000_000) String kubeconfig
    ) {
    }
}
