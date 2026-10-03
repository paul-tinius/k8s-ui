package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.error.DashboardException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoClusterTest {

    private DemoCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = new DemoCluster();
    }

    @Test
    void list_twoNamespaces_returnsPodsFromBothAndOmitsTheThird() {
        var pods = cluster.list(ResourceKind.POD, Set.of("shop", "payments"));

        assertThat(pods).extracting(pod -> pod.namespace())
                .contains("shop", "payments")
                .doesNotContain("observability");
        assertThat(pods).filteredOn(pod -> "ledger-a".equals(pod.name()))
                .singleElement()
                .extracting(pod -> pod.status())
                .isEqualTo("CrashLoopBackOff");
    }

    @Test
    void logs_connectionRefusedQuery_returnsTheDatabaseLine() {
        var page = cluster.logs(new LogRequest(Set.of("payments"), "", "", "", 100, "refused"));

        assertThat(page.lines()).anyMatch(line ->
                line.pod().equals("ledger-a") && line.text().contains("db.payments.svc:5432"));
        assertThat(page.lines()).noneMatch(line -> line.namespace().equals("shop"));
    }

    @Test
    void scale_cartToOne_leavesASingleReadyPod() {
        cluster.scale("shop", "cart", 1);

        var pods = cluster.list(ResourceKind.POD, Set.of("shop")).stream()
                .filter(pod -> "cart".equals(pod.labels().get("app")))
                .toList();
        assertThat(pods).hasSize(1);
        assertThat(cluster.detail(ResourceKind.DEPLOYMENT, "shop", "cart").resource().desired()).isEqualTo(1);

        cluster.scale("shop", "cart", 2);
    }

    @Test
    void deletePod_ownedStorefrontPod_recreatesARunningPod() {
        cluster.deletePod("shop", "storefront-a");

        var pods = cluster.list(ResourceKind.POD, Set.of("shop")).stream()
                .filter(pod -> "storefront".equals(pod.labels().get("app")))
                .toList();
        assertThat(pods).hasSize(3);
        assertThat(pods).extracting(pod -> pod.name()).doesNotContain("storefront-a");
        assertThat(pods).allMatch(pod -> "Running".equals(pod.status()));
    }

    @Test
    void rolloutRestart_storefront_replacesPodNamesAndStaysAvailable() {
        var before = cluster.list(ResourceKind.POD, Set.of("shop")).stream()
                .filter(pod -> "storefront".equals(pod.labels().get("app")))
                .map(pod -> pod.name())
                .toList();

        cluster.rolloutRestart("shop", "storefront");

        var after = cluster.list(ResourceKind.POD, Set.of("shop")).stream()
                .filter(pod -> "storefront".equals(pod.labels().get("app")))
                .toList();
        assertThat(after).hasSize(before.size());
        assertThat(after).extracting(pod -> pod.name()).doesNotContainAnyElementsOf(before);
        assertThat(cluster.detail(ResourceKind.DEPLOYMENT, "shop", "storefront").resource().status()).isEqualTo("Available");
    }

    @Test
    void applyYaml_ownedStorefrontPod_updatesThatPodWithoutADuplicate() {
        String edited = cluster.detail(ResourceKind.POD, "shop", "storefront-a").yaml()
                .replace("ghcr.io/example/storefront:1.4.2", "ghcr.io/example/storefront:9.9.9");

        cluster.applyYaml(edited);

        var pods = cluster.list(ResourceKind.POD, Set.of("shop"));
        assertThat(pods).filteredOn(pod -> "storefront-a".equals(pod.name()))
                .singleElement()
                .satisfies(pod -> assertThat(pod.images()).containsExactly("ghcr.io/example/storefront:9.9.9"));
        assertThat(pods).filteredOn(pod -> "storefront".equals(pod.labels().get("app"))).hasSize(3);
    }

    @Test
    void deleteResource_savedOwnedStorefrontPod_replacesTheDeploymentPod() {
        cluster.applyYaml(cluster.detail(ResourceKind.POD, "shop", "storefront-a").yaml());

        cluster.deleteResource(ResourceKind.POD, "shop", "storefront-a");

        var pods = cluster.list(ResourceKind.POD, Set.of("shop")).stream()
                .filter(pod -> "storefront".equals(pod.labels().get("app")))
                .toList();
        assertThat(pods).hasSize(3);
        assertThat(pods).extracting(pod -> pod.name()).doesNotContain("storefront-a");
        assertThat(pods).allMatch(pod -> "Running".equals(pod.status()));
    }

    @Test
    void applyYaml_configMap_appearsInTheNamespace() {
        cluster.applyYaml("""
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: feature-flags
                  namespace: shop
                data:
                  checkout: "on"
                """);

        assertThat(cluster.list(ResourceKind.CONFIG_MAP, Set.of("shop")))
                .extracting(configMap -> configMap.name())
                .contains("feature-flags");
        assertThat(cluster.detail(ResourceKind.CONFIG_MAP, "shop", "feature-flags").yaml()).contains("checkout");
    }

    @Test
    void openForward_service_recordsASimulatedForwardToAReadyPod() {
        var forward = cluster.openForward("shop", "service", "storefront", 8080, 0).view();

        assertThat(forward.simulated()).isTrue();
        assertThat(forward.podName()).startsWith("storefront-");
        assertThat(forward.remotePort()).isEqualTo(8080);
        assertThat(forward.localPort()).isGreaterThan(0);
    }

    @Test
    void createNamespace_newName_listsItAsActive() {
        cluster.createNamespace("billing");

        assertThat(cluster.namespaces()).contains("billing");
        assertThat(cluster.namespaceDetails())
                .filteredOn(item -> "billing".equals(item.name()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("Active");
                    assertThat(item.deletable()).isTrue();
                });
        assertThat(cluster.overview(Set.of("billing")).namespaces()).isEqualTo(1);
    }

    @Test
    void createNamespace_existingName_isRejected() {
        assertThatThrownBy(() -> cluster.createNamespace("shop"))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void deleteResource_cartDeployment_removesItAndItsPods() {
        cluster.deleteResource(ResourceKind.DEPLOYMENT, "shop", "cart");

        assertThat(cluster.list(ResourceKind.DEPLOYMENT, Set.of("shop")))
                .extracting(item -> item.name())
                .doesNotContain("cart");
        assertThat(cluster.list(ResourceKind.POD, Set.of("shop")))
                .extracting(item -> item.name())
                .noneMatch(name -> name.startsWith("cart-"));
    }

    @Test
    void deleteResource_storefrontConfig_removesTheConfigMap() {
        cluster.deleteResource(ResourceKind.CONFIG_MAP, "shop", "storefront-config");

        assertThat(cluster.list(ResourceKind.CONFIG_MAP, Set.of("shop")))
                .extracting(item -> item.name())
                .doesNotContain("storefront-config");
    }

    @Test
    void deleteResource_node_isRejected() {
        assertThatThrownBy(() -> cluster.deleteResource(ResourceKind.NODE, "", "node-a"))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void deleteNamespace_shop_removesItsWorkloads() {
        cluster.deleteNamespace("shop");

        assertThat(cluster.namespaces()).contains("payments", "observability").doesNotContain("shop");
        assertThat(cluster.list(ResourceKind.POD, Set.of("shop"))).isEmpty();
        assertThat(cluster.list(ResourceKind.DEPLOYMENT, Set.of("shop"))).isEmpty();
        assertThat(cluster.list(ResourceKind.POD, Set.of("payments"))).isNotEmpty();
    }

    @Test
    void deleteNamespace_kubeSystem_isRejected() {
        assertThatThrownBy(() -> cluster.deleteNamespace("kube-system"))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void applyYaml_newNamespace_addsTheNamespace() {
        cluster.applyYaml("""
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: flags
                  namespace: sandbox
                data:
                  checkout: "on"
                """);

        assertThat(cluster.namespaces()).contains("sandbox");
    }

    @Test
    void overview_paymentsNamespace_countsTheWarningAndUnreadyPod() {
        var overview = cluster.overview(Set.of("payments"));

        assertThat(overview.warnings()).isEqualTo(1);
        assertThat(overview.readyPods()).isLessThan(overview.pods());
        assertThat(overview.attention()).anyMatch(item -> "ledger-a".equals(item.name()));
    }

    @Test
    void applyYaml_unchangedServiceManifest_keepsPortsAndSelector() {
        cluster.applyYaml(cluster.detail(ResourceKind.SERVICE, "shop", "storefront").yaml());

        var service = cluster.detail(ResourceKind.SERVICE, "shop", "storefront");
        assertThat(service.resource().attributes()).containsEntry("ports", "80:8080");
        assertThat(service.yaml()).contains("app: storefront");
        assertThat(cluster.openForward("shop", "service", "storefront", 8080, 0).view().podName()).startsWith("storefront-");
    }

    @Test
    void applyYaml_unchangedOwnedPodManifest_keepsASinglePodOwnedByTheDeployment() {
        cluster.applyYaml(cluster.detail(ResourceKind.POD, "shop", "storefront-a").yaml());

        assertThat(cluster.list(ResourceKind.POD, Set.of("shop")))
                .filteredOn(pod -> "storefront-a".equals(pod.name()))
                .hasSize(1);

        cluster.deletePod("shop", "storefront-a");

        var pods = cluster.list(ResourceKind.POD, Set.of("shop")).stream()
                .filter(pod -> "storefront".equals(pod.labels().get("app")))
                .toList();
        assertThat(pods).hasSize(3);
        assertThat(pods).extracting(pod -> pod.name()).doesNotContain("storefront-a");
    }

    @Test
    void applyYaml_unchangedCrashingPodManifest_keepsTheFailure() {
        cluster.applyYaml(cluster.detail(ResourceKind.POD, "payments", "ledger-a").yaml());

        assertThat(cluster.list(ResourceKind.POD, Set.of("payments")))
                .filteredOn(pod -> "ledger-a".equals(pod.name()))
                .singleElement()
                .extracting(pod -> pod.status())
                .isEqualTo("CrashLoopBackOff");
        assertThat(cluster.overview(Set.of("payments")).attention()).anyMatch(item -> "ledger-a".equals(item.name()));
    }

    @Test
    void applyYaml_renamedContainerOfOwnedPod_isRejected() {
        var detail = cluster.detail(ResourceKind.POD, "payments", "ledger-a");
        String yaml = detail.yaml().replace("- name: ledger\n", "- name: ledger-v2\n");

        assertThat(yaml).contains("ledger-v2");
        assertThatThrownBy(() -> cluster.applyYaml(yaml))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("cannot be changed");
    }
}
