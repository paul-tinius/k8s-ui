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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

@Component
public class AiClient {

    private static final Set<String> DEVIN_MODES = Set.of(
            "normal", "fast", "fusion", "lite", "ultra", "swe-2-medium", "swe-2-high", "swe-2-max");

    private final DashboardProperties.Ai properties;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration pollInterval;

    @Autowired
    public AiClient(DashboardProperties properties, ObjectMapper mapper) {
        this(properties, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Duration.ofSeconds(2));
    }

    AiClient(DashboardProperties properties, ObjectMapper mapper, HttpClient http) {
        this(properties, mapper, http, Duration.ofSeconds(2));
    }

    AiClient(DashboardProperties properties, ObjectMapper mapper, HttpClient http, Duration pollInterval) {
        this.properties = properties.getAi();
        this.mapper = mapper;
        this.http = http;
        this.pollInterval = pollInterval == null ? Duration.ofSeconds(2) : pollInterval;
    }

    public boolean configured() {
        return hasText(properties.getApiKey());
    }

    public String complete(String system, String user) {
        return complete(new ModelRequest(
                properties.getProvider(),
                properties.getBaseUrl(),
                properties.getModel(),
                properties.getApiKey(),
                "",
                properties.getTimeout()
        ), system, user);
    }

    public String complete(ModelRequest request, String system, String user) {
        if (isDevin(request)) {
            return completeDevin(request, system, user);
        }
        return completeChat(request, system, user);
    }

    private String completeChat(ModelRequest request, String system, String user) {
        requireKeyUnlessLoopback(request);
        String base = normalize(request.baseUrl());
        requireHttp(base, false);
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model() == null ? "" : request.model());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        JsonNode tree = exchange(request, "POST", base + "/chat/completions", body.toString(), budget(request));
        String content = tree.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Model returned an empty answer");
        }
        return content;
    }

    private String completeDevin(ModelRequest request, String system, String user) {
        requireKeyUnlessLoopback(request);
        if (request.orgId() == null || !request.orgId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) {
            throw DashboardException.badRequest("Devin organization id is required");
        }
        String base = normalize(request.baseUrl());
        requireHttp(base, true);
        ObjectNode body = mapper.createObjectNode();
        body.put("prompt", system + "\n\n" + user);
        body.put("title", "Kubernetes dashboard");
        body.put("resumable", false);
        if (request.model() != null && DEVIN_MODES.contains(request.model())) {
            body.put("devin_mode", request.model());
        }
        String org = request.orgId();
        JsonNode created = exchange(request, "POST", base + "/organizations/" + org + "/sessions", body.toString(), Duration.ofSeconds(20));
        String sessionId = created.path("session_id").asText("");
        if (!sessionId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,120}")) {
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Devin did not return a session");
        }
        String url = sessionUrl(created.path("url").asText(""));
        long deadline = System.nanoTime() + budget(request).toNanos();
        String sessionPath = base + "/organizations/" + org + "/sessions/" + sessionId;
        String latest = "";
        while (true) {
            JsonNode session = exchange(request, "GET", sessionPath, null, Duration.ofSeconds(20));
            latest = lastDevinMessage(request, sessionPath + "/messages");
            String status = session.path("status").asText("");
            String detail = session.path("status_detail").asText("");
            if (terminal(status, detail)) {
                if (("error".equals(status) || "error".equals(detail)) && latest.isBlank()) {
                    throw new DashboardException(HttpStatus.BAD_GATEWAY, trim("Devin session failed" + note(url), request.apiKey()));
                }
                if (latest.isBlank()) {
                    return "Devin finished without a written reply." + note(url);
                }
                return latest + note(url);
            }
            if (System.nanoTime() >= deadline) {
                if (!latest.isBlank()) {
                    return latest + note(url);
                }
                return "Devin is still working." + note(url);
            }
            pause();
        }
    }

    private String lastDevinMessage(ModelRequest request, String messagesUrl) {
        String latest = "";
        String page = messagesUrl + "?first=200";
        for (int attempt = 0; attempt < 5; attempt++) {
            JsonNode tree = exchange(request, "GET", page, null, Duration.ofSeconds(20));
            for (JsonNode item : tree.path("items")) {
                if ("devin".equals(item.path("source").asText()) && hasText(item.path("message").asText(""))) {
                    latest = item.path("message").asText();
                }
            }
            String cursor = tree.path("end_cursor").asText("");
            if (!tree.path("has_next_page").asBoolean(false) || cursor.isBlank() || cursor.length() > 500) {
                return latest;
            }
            page = messagesUrl + "?first=200&after=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        }
        return latest;
    }

    private JsonNode exchange(ModelRequest request, String method, String url, String body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout == null ? Duration.ofSeconds(20) : timeout)
                .header("Accept", "application/json");
        if (hasText(request.apiKey())) {
            builder.header("Authorization", "Bearer " + request.apiKey());
        }
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            builder.GET();
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Model request was interrupted");
        } catch (Exception exception) {
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Model request failed");
        }
        JsonNode tree;
        try {
            String payload = response.body() == null || response.body().isBlank() ? "{}" : response.body();
            tree = mapper.readTree(payload);
        } catch (Exception exception) {
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Model returned an unreadable response");
        }
        if (response.statusCode() >= 300) {
            throw new DashboardException(HttpStatus.BAD_GATEWAY, trim(failureMessage(tree, response.statusCode()), request.apiKey()));
        }
        return tree;
    }

    private void requireKeyUnlessLoopback(ModelRequest request) {
        if (hasText(request.apiKey())) {
            return;
        }
        String host = host(request.baseUrl());
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
            return;
        }
        throw new DashboardException(HttpStatus.CONFLICT, "No model API key is configured");
    }

    private static void requireHttp(String base, boolean devin) {
        URI uri;
        try {
            uri = URI.create(base);
        } catch (RuntimeException exception) {
            throw DashboardException.badRequest("Model base URL is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw DashboardException.badRequest("Model base URL must be http or https");
        }
        if (devin && "http".equalsIgnoreCase(scheme)) {
            String host = uri.getHost();
            if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                throw DashboardException.badRequest("Devin base URL must be https");
            }
        }
    }

    private static boolean isDevin(ModelRequest request) {
        if (request.provider() != null && "devin".equalsIgnoreCase(request.provider())) {
            return true;
        }
        return "api.devin.ai".equalsIgnoreCase(host(request.baseUrl()));
    }

    private static boolean terminal(String status, String detail) {
        if ("exit".equals(status) || "error".equals(status) || "suspended".equals(status)) {
            return true;
        }
        return "running".equals(status) && ("finished".equals(detail) || "waiting_for_user".equals(detail) || "waiting_for_approval".equals(detail));
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
            message = tree.path("detail").asText("");
        }
        if (message.isBlank()) {
            message = tree.path("message").asText("");
        }
        if (message.isBlank()) {
            message = tree.path("title").asText("");
        }
        if (message.isBlank()) {
            message = "Model returned HTTP " + status;
        }
        return message;
    }

    private static String trim(String message, String secret) {
        String cleaned = message.replaceAll("(?i)bearer\\s+\\S+", "bearer [redacted]");
        if (secret != null && secret.length() >= 6) {
            cleaned = cleaned.replace(secret, "[redacted]");
        }
        return cleaned.length() > 400 ? cleaned.substring(0, 400) : cleaned;
    }

    private static String normalize(String base) {
        String value = base == null ? "" : base.replaceAll("/+$", "");
        if (value.isBlank()) {
            throw DashboardException.badRequest("Model base URL is empty");
        }
        return value;
    }

    private static Duration budget(ModelRequest request) {
        return request.timeout() == null ? Duration.ofSeconds(90) : request.timeout();
    }

    private static String host(String base) {
        try {
            String host = URI.create(base == null ? "" : base).getHost();
            return host == null ? "" : host;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String sessionUrl(String url) {
        return url != null && url.startsWith("https://") ? url : "";
    }

    private static String note(String url) {
        return url.isBlank() ? "" : "\n\nSession: " + url;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void pause() {
        try {
            Thread.sleep(Math.max(0, pollInterval.toMillis()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DashboardException(HttpStatus.BAD_GATEWAY, "Model request was interrupted");
        }
    }
}
