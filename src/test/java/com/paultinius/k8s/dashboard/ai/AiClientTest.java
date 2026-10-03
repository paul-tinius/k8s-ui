package com.paultinius.k8s.dashboard.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paultinius.k8s.dashboard.config.DashboardProperties;
import com.paultinius.k8s.dashboard.error.DashboardException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiClientTest {

    @Test
    void complete_chatCompletions_sendsTheModelAndReturnsTheAnswer() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"message":{"role":"assistant","content":"Check the database Service."}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DashboardProperties properties = new DashboardProperties();
            properties.getAi().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            properties.getAi().setModel("grok-4.7");
            properties.getAi().setApiKey("test-key");
            properties.getAi().setTimeout(Duration.ofSeconds(5));
            AiClient client = new AiClient(properties, new ObjectMapper(), java.net.http.HttpClient.newHttpClient());

            String answer = client.complete("system", "why is ledger failing");

            assertThat(answer).isEqualTo("Check the database Service.");
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            assertThat(body.get()).contains("grok-4.7").contains("why is ledger failing");
            assertThat(body.get()).doesNotContain("test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void complete_stringErrorBody_reportsTheProviderMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = """
                    {"code":"permission-denied","error":"team has no credits"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(403, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DashboardProperties properties = new DashboardProperties();
            properties.getAi().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            properties.getAi().setModel("grok-4.7");
            properties.getAi().setApiKey("test-key");
            properties.getAi().setTimeout(Duration.ofSeconds(5));
            AiClient client = new AiClient(properties, new ObjectMapper(), java.net.http.HttpClient.newHttpClient());

            assertThatThrownBy(() -> client.complete("system", "why is ledger failing"))
                    .isInstanceOf(DashboardException.class)
                    .hasMessage("team has no credits");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void complete_userKey_sendsThatKeyInsteadOfTheServerKey() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {"choices":[{"message":{"content":"Check the database Service."}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DashboardProperties properties = new DashboardProperties();
            properties.getAi().setApiKey("server-key");
            properties.getAi().setTimeout(Duration.ofSeconds(5));
            AiClient client = new AiClient(properties, new ObjectMapper(), java.net.http.HttpClient.newHttpClient());
            ModelRequest request = new ModelRequest(
                    "OpenAI",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "gpt-4.1",
                    "user-key",
                    "",
                    Duration.ofSeconds(5)
            );

            String answer = client.complete(request, "system", "why is ledger failing");

            assertThat(answer).isEqualTo("Check the database Service.");
            assertThat(authorization.get()).isEqualTo("Bearer user-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void complete_problemDetail_reportsTheDetail() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = """
                    {"title":"Forbidden","status":403,"detail":"organization id is unknown"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DashboardProperties properties = new DashboardProperties();
            properties.getAi().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            properties.getAi().setApiKey("cog_secret_key");
            properties.getAi().setTimeout(Duration.ofSeconds(5));
            AiClient client = new AiClient(properties, new ObjectMapper(), java.net.http.HttpClient.newHttpClient());

            assertThatThrownBy(() -> client.complete("system", "why"))
                    .isInstanceOf(DashboardException.class)
                    .hasMessage("organization id is unknown");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void complete_devinSession_returnsDevinsReply() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> createBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/organizations/org-lab/sessions/devin-1/messages", exchange -> {
            byte[] response = """
                    {"items":[{"source":"user","message":"prompt"},{"source":"devin","message":"Check the database Service."}],"has_next_page":false}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v3/organizations/org-lab/sessions/devin-1", exchange -> {
            byte[] response = """
                    {"session_id":"devin-1","status":"running","status_detail":"finished"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v3/organizations/org-lab/sessions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"session_id":"devin-1","url":"https://app.devin.ai/sessions/devin-1","status":"new"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DashboardProperties properties = new DashboardProperties();
            properties.getAi().setApiKey("server-key");
            AiClient client = new AiClient(properties, new ObjectMapper(), java.net.http.HttpClient.newHttpClient(), Duration.ZERO);
            ModelRequest request = new ModelRequest(
                    "Devin",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v3",
                    "lite",
                    "cog_user_key",
                    "org-lab",
                    Duration.ofSeconds(5)
            );

            String answer = client.complete(request, "system", "why is ledger failing");

            assertThat(answer).contains("Check the database Service.");
            assertThat(answer).contains("https://app.devin.ai/sessions/devin-1");
            assertThat(authorization.get()).isEqualTo("Bearer cog_user_key");
            assertThat(createBody.get()).contains("devin_mode").contains("lite").contains("why is ledger failing");
            assertThat(createBody.get()).doesNotContain("cog_user_key");
        } finally {
            server.stop(0);
        }
    }
}
