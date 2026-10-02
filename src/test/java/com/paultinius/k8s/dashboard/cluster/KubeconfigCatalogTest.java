package com.paultinius.k8s.dashboard.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KubeconfigCatalogTest {

    private static final String KUBECONFIG = """
            apiVersion: v1
            kind: Config
            current-context: lab
            clusters:
              - name: lab
                cluster:
                  server: https://lab.example:6443
              - name: edge
                cluster:
                  server: https://edge.example:6443
            contexts:
              - name: lab
                context:
                  cluster: lab
                  user: lab
                  namespace: shop
              - name: edge
                context:
                  cluster: edge
                  user: edge
            users:
              - name: lab
                user:
                  token: lab-token
              - name: edge
                user:
                  token: edge-token
            """;

    @Test
    void read_twoContexts_returnsBothServers() {
        var contexts = KubeconfigCatalog.read(KUBECONFIG);

        assertThat(contexts).extracting(context -> context.name()).containsExactly("lab", "edge");
        assertThat(contexts.get(0).server()).isEqualTo("https://lab.example:6443");
        assertThat(contexts.get(0).namespace()).isEqualTo("shop");
    }

    @Test
    void isolate_namedContext_dropsTheOtherContext() {
        var isolated = KubeconfigCatalog.read(KubeconfigCatalog.isolate(KUBECONFIG, "edge"));

        assertThat(isolated).singleElement().satisfies(context -> {
            assertThat(context.name()).isEqualTo("edge");
            assertThat(context.server()).isEqualTo("https://edge.example:6443");
        });
    }

    @Test
    void idFor_reservedNames_arePrefixed() {
        assertThat(KubeconfigCatalog.idFor("Demo")).isEqualTo("kube-demo");
        assertThat(KubeconfigCatalog.idFor("Lab Cluster")).isEqualTo("lab-cluster");
    }
}
