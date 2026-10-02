package com.paultinius.k8s.dashboard.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DashboardApiTest {

    private static final String DATA_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "k8s-dashboard-" + UUID.randomUUID()
    ).toString();

    private static final String KUBECONFIG = """
            apiVersion: v1
            kind: Config
            current-context: lab
            clusters:
              - name: lab
                cluster:
                  server: https://127.0.0.1:6443
                  insecure-skip-tls-verify: true
            contexts:
              - name: lab
                context:
                  cluster: lab
                  user: lab
                  namespace: shop
            users:
              - name: lab
                user:
                  token: test-token
            """;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("dashboard.cluster.data-dir", () -> DATA_DIR);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void index_dashboardPage_containsTheTitle() throws Exception {
        String html = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html).contains("Kubernetes Dashboard");
    }

    @Test
    void clusters_defaultConfig_includesTheDemoCluster() throws Exception {
        String body = mockMvc.perform(get("/api/clusters"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(mapper.readTree(body))
                .anyMatch(cluster -> cluster.path("id").asText().equals("demo") && cluster.path("demo").asBoolean());
    }

    @Test
    void resources_twoNamespaces_omitsTheUnselectedNamespace() throws Exception {
        String body = mockMvc.perform(get("/api/resources")
                        .param("cluster", "demo")
                        .param("kind", "Pod")
                        .param("namespaces", "shop,payments"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode pods = mapper.readTree(body);

        assertThat(pods).isNotEmpty();
        assertThat(pods.findValuesAsText("namespace"))
                .contains("shop", "payments")
                .doesNotContain("observability");
    }

    @Test
    void logs_refusedQuery_returnsTheLedgerLine() throws Exception {
        String body = mockMvc.perform(get("/api/logs")
                        .param("cluster", "demo")
                        .param("namespaces", "payments")
                        .param("q", "refused"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(mapper.readTree(body).path("lines").findValuesAsText("text"))
                .anyMatch(line -> line.contains("db.payments.svc:5432"));
    }

    @Test
    void ask_withoutApiKey_returnsTheLocalCheck() throws Exception {
        String answer = mockMvc.perform(post("/api/ai/ask")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("""
                                {"cluster":"demo","namespace":"payments","kind":"Pod","name":"ledger-a","question":"why is it failing?","includeLogs":true}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode body = mapper.readTree(answer);
        assertThat(body.path("modelUsed").asBoolean()).isFalse();
        assertThat(body.path("answer").asText()).contains("db.payments.svc:5432");
    }

    @Test
    void aiStatus_defaultConfig_reportsGrokUnconfigured() throws Exception {
        mockMvc.perform(get("/api/ai/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.model").value("grok-4.7"))
                .andExpect(jsonPath("$.baseUrlHost").value("api.x.ai"));
        mockMvc.perform(get("/api/ai/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.[?(@.id == 'xai')].compatible").value(true))
                .andExpect(jsonPath("$.[?(@.id == 'ollama')].baseUrl").value("http://127.0.0.1:11434/v1"))
                .andExpect(jsonPath("$.[?(@.id == 'devin')]").doesNotExist());
    }

    @Test
    void scale_cartToOne_thenRestoresTwo() throws Exception {
        mockMvc.perform(post("/api/deployments/shop/cart/scale")
                        .param("cluster", "demo")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"replicas\":1}"))
                .andExpect(status().isOk());
        String scaled = mockMvc.perform(get("/api/resources/Deployment/shop/cart").param("cluster", "demo"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(scaled).path("resource").path("desired").asInt()).isEqualTo(1);

        mockMvc.perform(post("/api/deployments/shop/cart/scale")
                        .param("cluster", "demo")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"replicas\":2}"))
                .andExpect(status().isOk());
    }

    @Test
    void portForward_demoService_canBeOpenedAndClosed() throws Exception {
        String created = mockMvc.perform(post("/api/port-forwards")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("""
                                {"cluster":"demo","namespace":"shop","targetKind":"service","targetName":"storefront","remotePort":8080,"localPort":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).path("id").asText();

        mockMvc.perform(delete("/api/port-forwards/" + id)).andExpect(status().isOk());
    }

    @Test
    void apply_configMap_isListedAfterwards() throws Exception {
        mockMvc.perform(post("/api/apply")
                        .param("cluster", "demo")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("""
                                {"yaml":"apiVersion: v1\\nkind: ConfigMap\\nmetadata:\\n  name: feature-flags\\n  namespace: shop\\ndata:\\n  checkout: on\\n"}
                                """))
                .andExpect(status().isOk());

        String listed = mockMvc.perform(get("/api/resources/ConfigMap/shop/feature-flags").param("cluster", "demo"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(mapper.readTree(listed).path("yaml").asText()).contains("feature-flags");
    }

    @Test
    void clusters_uploadedKubeconfig_isListedAndRemoved() throws Exception {
        String payload = mapper.writeValueAsString(mapper.createObjectNode()
                .put("name", "Lab")
                .put("kubeconfig", KUBECONFIG));
        if (payload == null) {
            throw new IllegalStateException("Could not encode the kubeconfig request");
        }
        mockMvc.perform(post("/api/clusters")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("lab"));

        mockMvc.perform(get("/api/clusters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'lab')].server").value("https://127.0.0.1:6443"));

        mockMvc.perform(delete("/api/clusters/lab")).andExpect(status().isOk());
        String listed = mockMvc.perform(get("/api/clusters"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(listed).findValuesAsText("id")).doesNotContain("lab");
    }

    @Test
    void health_actuator_isUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
