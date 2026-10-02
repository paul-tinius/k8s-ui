package com.paultinius.k8s.dashboard.web;

import com.paultinius.k8s.dashboard.cluster.ClusterRegistry;
import com.paultinius.k8s.dashboard.model.ClusterInfo;
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

    public record CreateClusterRequest(
            String name,
            @NotBlank @Size(max = 1_000_000) String kubeconfig
    ) {
    }
}
