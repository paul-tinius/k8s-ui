package com.paultinius.k8s.dashboard.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paultinius.k8s.dashboard.live.LiveHub;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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

    @Autowired
    private LiveHub liveHub;

    @Test
    void index_dashboardPage_containsTheTitle() throws Exception {
        String html = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html).contains("Kubernetes Dashboard")
                .contains("data-tab=\"management\"")
                .contains("id=\"detail-collapse\"")
                .contains("id=\"namespace-collapse\"")
                .contains("id=\"theme\"");
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
    }

    @Test
    void ask_providerMissingBaseUrl_isRejected() throws Exception {
        mockMvc.perform(post("/api/ai/ask")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("""
                                {"cluster":"demo","question":"why is it failing?","provider":"Acme","apiKey":"user-key"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ask_devinWithoutOrganization_isRejected() throws Exception {
        mockMvc.perform(post("/api/ai/ask")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("""
                                {"cluster":"demo","question":"why is it failing?","provider":"Devin","baseUrl":"https://api.devin.ai/v3","apiKey":"cog_user_key"}
                                """))
                .andExpect(status().isBadRequest());
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
    void resources_configMap_editThenDelete_updatesThenRemovesIt() throws Exception {
        String name = "flag" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        boolean created = false;
        try {
            mockMvc.perform(post("/api/apply")
                            .param("cluster", "demo")
                            .contentType(MediaType.APPLICATION_JSON_VALUE)
                            .content(configMapBody(name, "on")))
                    .andExpect(status().isOk());
            created = true;

            mockMvc.perform(post("/api/apply")
                            .param("cluster", "demo")
                            .contentType(MediaType.APPLICATION_JSON_VALUE)
                            .content(configMapBody(name, "off")))
                    .andExpect(status().isOk());

            String edited = mockMvc.perform(get("/api/resources/ConfigMap/shop/" + name).param("cluster", "demo"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(mapper.readTree(edited).path("yaml").asText()).contains("checkout: \"off\"");

            mockMvc.perform(delete("/api/resources/ConfigMap/shop/" + name).param("cluster", "demo"))
                    .andExpect(status().isOk());
            created = false;

            mockMvc.perform(get("/api/resources/ConfigMap/shop/" + name).param("cluster", "demo"))
                    .andExpect(status().isNotFound());
        } finally {
            if (created) {
                mockMvc.perform(delete("/api/resources/ConfigMap/shop/" + name).param("cluster", "demo"));
            }
        }
    }

    @Test
    void resources_node_isRejected() throws Exception {
        mockMvc.perform(delete("/api/resources/Node/_/node-a").param("cluster", "demo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Node cannot be deleted"));
    }

    @NonNull
    private String configMapBody(String name, String checkout) throws Exception {
        String yaml = """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: %s
                  namespace: shop
                data:
                  checkout: "%s"
                """.formatted(name, checkout);
        String body = mapper.writeValueAsString(mapper.createObjectNode().put("yaml", yaml));
        if (body == null) {
            throw new IllegalStateException("Could not encode the config map request");
        }
        return body;
    }

    @Test
    void namespaces_newName_appearsThenDisappears() throws Exception {
        String name = "bill" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        boolean created = false;
        try {
            String body = mockMvc.perform(post("/api/namespaces")
                            .param("cluster", "demo")
                            .contentType(MediaType.APPLICATION_JSON_VALUE)
                            .content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            created = true;
            assertThat(mapper.readTree(body)).anyMatch(item ->
                    name.equals(item.path("name").asText())
                            && item.path("deletable").asBoolean()
                            && "Active".equals(item.path("status").asText()));

            String listed = mockMvc.perform(get("/api/namespaces").param("cluster", "demo"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(mapper.readTree(listed)).anyMatch(item -> name.equals(item.asText()));
        } finally {
            if (created) {
                mockMvc.perform(delete("/api/namespaces/" + name).param("cluster", "demo"))
                        .andExpect(status().isOk());
            }
        }

        String remaining = mockMvc.perform(get("/api/namespaces").param("cluster", "demo"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(mapper.readTree(remaining)).noneMatch(item -> name.equals(item.asText()));
    }

    @Test
    void namespaces_kubeSystem_isRejected() throws Exception {
        mockMvc.perform(delete("/api/namespaces/kube-system").param("cluster", "demo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Namespace kube-system cannot be deleted"));
    }

    @Test
    void namespaces_reservedName_isRejected() throws Exception {
        mockMvc.perform(post("/api/namespaces")
                        .param("cluster", "demo")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"name\":\"kube-system\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Namespace kube-system is reserved"));
    }

    @Test
    void namespaces_uppercaseName_isRejected() throws Exception {
        mockMvc.perform(post("/api/namespaces")
                        .param("cluster", "demo")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"name\":\"Shop\"}"))
                .andExpect(status().isBadRequest());
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
    void resources_unknownCluster_returnsTheErrorJson() throws Exception {
        mockMvc.perform(get("/api/resources").param("cluster", "missing").param("kind", "Pod"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.error").value("No cluster missing"));
    }

    @Test
    void logsStream_unknownCluster_doesNotWriteJsonOntoTheEventStream() throws Exception {
        mockMvc.perform(get("/api/logs/stream")
                        .param("cluster", "missing")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void live_streamError_doesNotWriteJsonOntoTheEventStream() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/live").accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();
        liveHub.publish("demo", "changed");
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);

        if (!(result.getRequest().getAsyncContext() instanceof MockAsyncContext asyncContext)) {
            throw new AssertionError("Expected a MockAsyncContext");
        }
        IllegalStateException failure = new IllegalStateException("stream failed");
        for (AsyncListener listener : asyncContext.getListeners()) {
            listener.onError(new AsyncEvent(
                    asyncContext, result.getRequest(), result.getResponse(), failure));
        }

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).contains("changed").doesNotContain("The request failed");
    }

    @Test
    void health_actuator_isUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
