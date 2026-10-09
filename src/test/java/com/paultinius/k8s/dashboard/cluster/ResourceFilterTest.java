package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.model.ResourceView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceFilterTest {

    @Test
    void apply_labelImageAndNode_keepsOnlyTheMatchingPod() {
        var pods = List.of(
                pod("shop", "storefront-a", "node-a", "ghcr.io/example/storefront:1.4.2", "storefront"),
                pod("observability", "collector-a", "node-a", "grafana/agent:0.40.0", "collector"),
                pod("shop", "cart-b", "node-b", "ghcr.io/example/cart:0.9.1", "cart")
        );

        var matched = ResourceFilter.apply(pods, "", List.of("app=collector"), "grafana", "node-a", "", "");

        assertThat(matched).extracting(view -> view.name()).containsExactly("collector-a");
    }

    @Test
    void apply_textQuery_matchesStatusAndImage() {
        var pods = List.of(
                pod("payments", "ledger-a", "node-a", "ghcr.io/example/ledger:2.3.0", "ledger")
        );

        var matched = ResourceFilter.apply(pods, "ledger:2.3", List.of(), "", "", "", "");

        assertThat(matched).hasSize(1);
    }

    @Test
    void apply_status_keepsOnlyPodsWithMatchingStatus() {
        var pods = List.of(
                pod("shop", "storefront-a", "node-a", "ghcr.io/example/storefront:1.4.2", "storefront", "Running"),
                pod("shop", "cart-b", "node-b", "ghcr.io/example/cart:0.9.1", "cart", "CrashLoopBackOff")
        );

        var matched = ResourceFilter.apply(pods, "", List.of(), "", "", "crash", "");

        assertThat(matched).extracting(view -> view.name()).containsExactly("cart-b");
    }

    @Test
    void apply_name_keepsOnlyPodsWithMatchingName() {
        var pods = List.of(
                pod("shop", "storefront-a", "node-a", "ghcr.io/example/storefront:1.4.2", "storefront", "Running"),
                pod("shop", "cart-b", "node-b", "ghcr.io/example/cart:0.9.1", "cart", "Running")
        );

        var matched = ResourceFilter.apply(pods, "", List.of(), "", "", "", "cart");

        assertThat(matched).extracting(view -> view.name()).containsExactly("cart-b");
    }

    @Test
    void apply_name_matchesAnyResourceKindByName() {
        var resources = List.of(
                new ResourceView("Service", "shop", "cart-service", "", "", List.of(), Map.of(), "", 0, 0, "", Map.of()),
                new ResourceView("ConfigMap", "shop", "storefront-config", "", "", List.of(), Map.of(), "", 0, 0, "", Map.of())
        );

        var matched = ResourceFilter.apply(resources, "", List.of(), "", "", "", "cart");

        assertThat(matched).extracting(view -> view.name()).containsExactly("cart-service");
    }

    private static ResourceView pod(String namespace, String name, String node, String image, String app) {
        return pod(namespace, name, node, image, app, "Running");
    }

    private static ResourceView pod(
            String namespace,
            String name,
            String node,
            String image,
            String app,
            String status
    ) {
        return new ResourceView(
                "Pod",
                namespace,
                name,
                status,
                node,
                List.of(image),
                Map.of("app", app),
                "",
                1,
                1,
                "",
                Map.of()
        );
    }
}
