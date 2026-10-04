package com.paultinius.k8s.dashboard.shell;

import com.paultinius.k8s.dashboard.cluster.DemoCluster;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DemoCommandsTest {

    private final DemoCluster cluster = new DemoCluster();

    @Test
    void execute_getPodsInShop_listsStorefrontAndSkipsLedger() {
        CommandResult result = DemoCommands.execute(cluster, List.of("kubectl", "get", "pods", "-n", "shop"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("storefront-a").contains("Running").doesNotContain("ledger-a");
    }

    @Test
    void execute_labelSelector_keepsOnlyTheMatchingPods() {
        CommandResult result = DemoCommands.execute(
                cluster, List.of("kubectl", "get", "pods", "-n", "shop", "-l", "app=storefront"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("storefront-a").doesNotContain("cart-a");
    }

    @Test
    void execute_describePod_includesTheImage() {
        CommandResult result = DemoCommands.execute(
                cluster, List.of("kubectl", "describe", "pod", "storefront-a", "-n", "shop"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("storefront-a").contains("ghcr.io/example/storefront");
    }

    @Test
    void execute_helmList_printsTheReleaseHeader() {
        CommandResult result = DemoCommands.execute(cluster, List.of("helm", "list"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("NAME").contains("CHART");
    }

    @Test
    void execute_helmInstall_tellsTheUserToUseALiveCluster() {
        CommandResult result = DemoCommands.execute(cluster, List.of("helm", "install", "shop", "chart"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("live cluster");
    }

    @Test
    void execute_delete_isRejectedOnTheDemoCluster() {
        CommandResult result = DemoCommands.execute(cluster, List.of("kubectl", "delete", "pod", "storefront-a"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("live cluster");
    }
}
