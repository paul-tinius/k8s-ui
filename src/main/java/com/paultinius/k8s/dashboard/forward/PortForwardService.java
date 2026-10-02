package com.paultinius.k8s.dashboard.forward;

import com.paultinius.k8s.dashboard.cluster.ClusterRegistry;
import com.paultinius.k8s.dashboard.error.DashboardException;
import com.paultinius.k8s.dashboard.model.ForwardView;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PortForwardService {

    private final ClusterRegistry clusters;
    private final Map<String, OpenForward> open = new ConcurrentHashMap<>();

    public PortForwardService(ClusterRegistry clusters) {
        this.clusters = clusters;
    }

    public List<ForwardView> list(String clusterId) {
        return open.values().stream()
                .map(forward -> forward.view())
                .filter(view -> clusterId == null || clusterId.isBlank() || clusterId.equals(view.clusterId()))
                .sorted(Comparator.comparing((ForwardView view) -> view.namespace()).thenComparing(view -> view.targetName()))
                .toList();
    }

    public ForwardView open(String clusterId, String namespace, String targetKind, String targetName, int remotePort, int localPort) {
        if (remotePort < 1 || remotePort > 65535) {
            throw DashboardException.badRequest("Remote port is out of range");
        }
        if (localPort < 0 || localPort > 65535) {
            throw DashboardException.badRequest("Local port is out of range");
        }
        if (localPort > 0 && open.values().stream().anyMatch(item -> item.view().localPort() == localPort)) {
            throw new DashboardException(HttpStatus.CONFLICT, "Local port " + localPort + " is already forwarded");
        }
        OpenForward forward = clusters.require(clusterId).openForward(namespace, targetKind, targetName, remotePort, localPort);
        open.put(forward.view().id(), forward);
        return forward.view();
    }

    public void close(String id) {
        OpenForward forward = open.remove(id);
        if (forward == null) {
            throw DashboardException.notFound("Port forward " + id + " is not open");
        }
        forward.close();
    }

    @PreDestroy
    public void closeAll() {
        open.values().forEach(forward -> forward.close());
        open.clear();
    }
}
