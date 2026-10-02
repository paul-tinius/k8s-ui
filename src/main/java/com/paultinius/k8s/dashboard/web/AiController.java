package com.paultinius.k8s.dashboard.web;

import com.paultinius.k8s.dashboard.ai.AiClient;
import com.paultinius.k8s.dashboard.ai.AiPresets;
import com.paultinius.k8s.dashboard.ai.TroubleshootService;
import com.paultinius.k8s.dashboard.config.DashboardProperties;
import com.paultinius.k8s.dashboard.model.AiPreset;
import com.paultinius.k8s.dashboard.model.AiStatus;
import com.paultinius.k8s.dashboard.model.AskResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiClient client;
    private final TroubleshootService troubleshooting;
    private final DashboardProperties properties;

    public AiController(AiClient client, TroubleshootService troubleshooting, DashboardProperties properties) {
        this.client = client;
        this.troubleshooting = troubleshooting;
        this.properties = properties;
    }

    @GetMapping("/status")
    public AiStatus status() {
        String baseUrl = properties.getAi().getBaseUrl();
        String host = "";
        try {
            host = URI.create(baseUrl).getHost();
        } catch (RuntimeException ignored) {
            host = "";
        }
        if (host == null) {
            host = "";
        }
        String env = "xAI".equalsIgnoreCase(properties.getAi().getProvider()) ? "XAI_API_KEY" : "DASHBOARD_AI_API_KEY";
        return new AiStatus(client.configured(), properties.getAi().getProvider(), properties.getAi().getModel(), host, env);
    }

    @GetMapping("/presets")
    public List<AiPreset> presets() {
        return AiPresets.all();
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        return troubleshooting.ask(
                request.cluster(),
                request.namespace(),
                request.kind(),
                request.name(),
                request.question(),
                request.includeLogs()
        );
    }

    public record AskRequest(
            String cluster,
            String namespace,
            String kind,
            String name,
            @NotBlank @Size(max = 2_000) String question,
            boolean includeLogs
    ) {
    }
}
