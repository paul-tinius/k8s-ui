package com.paultinius.k8s.dashboard.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccessFilterTest {

    @Test
    void queryTokenAllowed_eventStreamsOnly_rejectsOrdinaryApiPaths() {
        assertThat(AccessFilter.queryTokenAllowed("/api/live")).isTrue();
        assertThat(AccessFilter.queryTokenAllowed("/api/logs/stream")).isTrue();
        assertThat(AccessFilter.queryTokenAllowed("/api/clusters")).isFalse();
    }
}
