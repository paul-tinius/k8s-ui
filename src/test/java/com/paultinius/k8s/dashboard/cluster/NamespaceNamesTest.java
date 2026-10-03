package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.error.DashboardException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamespaceNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "shop", "billing-1", "a-b"})
    void require_dnsLabel_returnsTheTrimmedName(String name) {
        assertThat(NamespaceNames.require("  " + name + "  ")).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Shop", "billing_1", "-shop", "shop-", "shop.local", "a--"})
    void require_invalidLabel_isRejected(String name) {
        assertThatThrownBy(() -> NamespaceNames.require(name))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("Namespace");
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "kube-system", "kube-public", "kube-node-lease"})
    void requireCreatable_systemNamespace_isRejected(String name) {
        assertThatThrownBy(() -> NamespaceNames.requireCreatable(name))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("is reserved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "kube-system", "kube-public", "kube-node-lease"})
    void requireDeletable_systemNamespace_isRejected(String name) {
        assertThat(NamespaceNames.deletable(name)).isFalse();
        assertThatThrownBy(() -> NamespaceNames.requireDeletable(name))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("cannot be deleted");
    }
}
