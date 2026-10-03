package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.forward.OpenForward;
import com.paultinius.k8s.dashboard.model.ClusterInfo;
import com.paultinius.k8s.dashboard.model.LogPage;
import com.paultinius.k8s.dashboard.model.NamespaceView;
import com.paultinius.k8s.dashboard.model.Overview;
import com.paultinius.k8s.dashboard.model.ResourceDetail;
import com.paultinius.k8s.dashboard.model.ResourceView;

import java.util.List;
import java.util.Set;

public interface ClusterClient extends AutoCloseable {

    ClusterInfo info();

    List<String> namespaces();

    List<NamespaceView> namespaceDetails();

    void createNamespace(String name);

    void deleteNamespace(String name);

    Overview overview(Set<String> namespaces);

    List<ResourceView> list(ResourceKind kind, Set<String> namespaces);

    ResourceDetail detail(ResourceKind kind, String namespace, String name);

    void applyYaml(String yaml);

    void scale(String namespace, String name, int replicas);

    void rolloutRestart(String namespace, String name);

    void deletePod(String namespace, String name);

    void deleteResource(ResourceKind kind, String namespace, String name);

    LogPage logs(LogRequest request);

    OpenForward openForward(String namespace, String targetKind, String targetName, int remotePort, int localPort);

    @Override
    void close();
}
