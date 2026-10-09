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
 * Management tab's AI assist panel endpoints), matching how the panel
 * behaves on a fresh install.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiSettingsControllerTest {

    private static final Path DATA_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "k8s-dashboard-ai-panel-" + UUID.randomUUID()
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
    void get_defaultConfig_isBlank() throws Exception {
        String body = mockMvc.perform(get("/api/management/ai"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode view = mapper.readTree(body);
        assertThat(view.path("providerName").asText()).isEmpty();
        assertThat(view.path("baseUrl").asText()).isEmpty();
        assertThat(view.path("hasApiKey").asBoolean()).isFalse();
    }

    @Test
    @Order(2)
    void post_update_isSavedEncryptedAndReportedBack() throws Exception {
        String payload = """
                {"providerName":"Acme","baseUrl":"https://models.example/v1","model":"acme-large",
                 "apiKey":"super-secret-value","orgId":"","clearApiKey":false}
                """;
        String body = mockMvc.perform(post("/api/management/ai")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerName").value("Acme"))
                .andExpect(jsonPath("$.hasApiKey").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("super-secret-value");

        String saved = Files.readString(DATA_DIR.resolve("ai-settings.yml"));
        assertThat(saved).doesNotContain("super-secret-value");

        String getBody = mockMvc.perform(get("/api/management/ai"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(getBody).path("baseUrl").asText()).isEqualTo("https://models.example/v1");
    }

    @Test
    @Order(3)
    void post_blankApiKey_keepsTheExistingOne() throws Exception {
        String payload = """
                {"providerName":"Acme","baseUrl":"https://models.example/v1","model":"acme-large-2",
                 "apiKey":"","orgId":"","clearApiKey":false}
                """;
        mockMvc.perform(post("/api/management/ai")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("acme-large-2"))
                .andExpect(jsonPath("$.hasApiKey").value(true));
    }
}
