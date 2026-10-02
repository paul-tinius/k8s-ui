package com.paultinius.k8s.dashboard.cluster;

import com.paultinius.k8s.dashboard.error.DashboardException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceKindTest {

    @Test
    void from_configMaps_mapsToConfigMap() {
        assertThat(ResourceKind.from("config-maps")).isEqualTo(ResourceKind.CONFIG_MAP);
    }

    @Test
    void from_secret_rejectsTheKind() {
        assertThatThrownBy(() -> ResourceKind.from("Secret"))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("Unsupported kind");
    }
}
