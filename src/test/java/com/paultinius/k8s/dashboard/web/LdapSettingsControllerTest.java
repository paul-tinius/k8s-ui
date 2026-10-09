package com.paultinius.k8s.dashboard.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LDAP stays disabled for this test (so no login is required to reach the
 * Management tab's LDAP panel endpoints), matching how the panel behaves on
 * a fresh install.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LdapSettingsControllerTest {

    private static final Path DATA_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "k8s-dashboard-ldap-panel-" + UUID.randomUUID()
    );

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("dashboard.cluster.data-dir", DATA_DIR::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    @Order(1)
    void get_defaultConfig_reportsDisabledAndNoBindPassword() throws Exception {
        String body = mockMvc.perform(get("/api/management/ldap"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode view = mapper.readTree(body);
        assertThat(view.path("enabled").asBoolean()).isFalse();
        assertThat(view.path("hasBindPassword").asBoolean()).isFalse();
        assertThat(view.path("restartRequired").asBoolean()).isFalse();
    }

    @Test
    @Order(2)
    void post_update_isSavedEncryptedAndReportedBack() throws Exception {
        String payload = """
                {"enabled":true,"host":"dc1.us.lmco.com","port":636,"defaultDomain":"us",
                 "searchBaseDn":"dc=us,dc=lmco,dc=com","group":"K8sDashboardUsers",
                 "bindUsername":"svc-dashboard","bindPassword":"super-secret-value","clearBindPassword":false}
                """;
        String body = mockMvc.perform(post("/api/management/ldap")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("dc1.us.lmco.com"))
                .andExpect(jsonPath("$.hasBindPassword").value(true))
                .andExpect(jsonPath("$.restartRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("super-secret-value");

        String saved = Files.readString(DATA_DIR.resolve("ldap-settings.yml"));
        assertThat(saved).doesNotContain("super-secret-value");

        String getBody = mockMvc.perform(get("/api/management/ldap"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(getBody).path("host").asText()).isEqualTo("dc1.us.lmco.com");
    }

    @Test
    @Order(3)
    void post_enabledWithoutHost_isRejected() throws Exception {
        String payload = """
                {"enabled":true,"host":"","port":636,"defaultDomain":"us",
                 "searchBaseDn":"dc=us,dc=lmco,dc=com","group":"K8sDashboardUsers",
                 "bindUsername":"svc-dashboard","bindPassword":"x","clearBindPassword":false}
                """;
        mockMvc.perform(post("/api/management/ldap")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }
}
