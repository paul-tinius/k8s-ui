package com.paultinius.k8s.dashboard.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "dashboard.auth.token=test-token")
@AutoConfigureMockMvc
class TokenGateTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("dashboard.cluster.data-dir", () -> Path.of(
                System.getProperty("java.io.tmpdir"),
                "k8s-dashboard-auth-" + UUID.randomUUID()
        ).toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void clusters_missingToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/clusters")).andExpect(status().isUnauthorized());
    }

    @Test
    void clusters_queryToken_isRejected() throws Exception {
        mockMvc.perform(get("/api/clusters").param("access_token", "test-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clusters_bearerToken_returnsTheDemoCluster() throws Exception {
        mockMvc.perform(get("/api/clusters").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());
    }

    @Test
    void index_withoutToken_staysPublic() throws Exception {
        mockMvc.perform(get("/index.html")).andExpect(status().isOk());
    }
}
