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
}
