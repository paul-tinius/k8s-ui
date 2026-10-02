package com.paultinius.k8s.dashboard.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paultinius.k8s.dashboard.config.DashboardProperties;
import com.paultinius.k8s.dashboard.error.DashboardException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class AiClient {

    private final DashboardProperties.Ai properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    public AiClient(DashboardProperties properties, ObjectMapper mapper) {
        this(properties, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    AiClient(DashboardProperties properties, ObjectMapper mapper, HttpClient http) {
        this.properties = properties.getAi();
        this.mapper = mapper;
        this.http = http;
    }

    public boolean configured() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    public String complete(String system, String user) {
        if (!configured()) {
            throw new DashboardException(HttpStatus.CONFLICT, "No model API key is configured");
        }
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().replaceAll("/+$", "");
        if (base.isBlank()) {
            throw DashboardException.badRequest("Model base URL is empty");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", properties.getModel());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(properties.getTimeout() == null ? Duration.ofSeconds(90) : properties.getTimeout())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Model request was interrupted");
        } catch (Exception exception) {
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Model request failed");
        }
        return read(response);
    }

    private String read(HttpResponse<String> response) {
        JsonNode tree;
        try {
            tree = mapper.readTree(response.body() == null ? "" : response.body());
        } catch (Exception exception) {
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Model returned an unreadable response");
        }
        if (response.statusCode() >= 300) {
            throw new DashboardException(HttpStatus.BAD_GATEWAY, trim(failureMessage(tree, response.statusCode())));
        }
        String content = tree.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Model returned an empty answer");
        }
        return content;
    }

    private static String failureMessage(JsonNode tree, int status) {
        JsonNode error = tree.path("error");
        String message = "";
        if (error.isTextual()) {
            message = error.asText("");
        } else if (error.isObject()) {
            message = error.path("message").asText("");
        }
        if (message.isBlank()) {
            message = tree.path("message").asText("");
        }
        if (message.isBlank()) {
            message = "Model returned HTTP " + status;
        }
        return message;
    }

    private static String trim(String message) {
        String cleaned = message.replaceAll("(?i)bearer\\s+\\S+", "bearer [redacted]");
        return cleaned.length() > 400 ? cleaned.substring(0, 400) : cleaned;
    }
}
