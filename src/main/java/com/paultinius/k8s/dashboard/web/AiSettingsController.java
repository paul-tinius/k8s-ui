package com.paultinius.k8s.dashboard.web;

import com.paultinius.k8s.dashboard.config.AiSettings;
import com.paultinius.k8s.dashboard.config.AiSettingsStore;
import com.paultinius.k8s.dashboard.model.AiSettingsView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backs the Management tab's AI assist panel. Reading and saving this
 * requires being authenticated whenever LDAP login is enabled - the same
 * {@code anyRequest().authenticated()} rule {@link com.paultinius.k8s.dashboard.config.SecurityConfig}
 * applies to the rest of the API.
 */
@RestController
@RequestMapping("/api/management/ai")
public class AiSettingsController {

    private final AiSettingsStore settingsStore;

    public AiSettingsController(AiSettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    @GetMapping
    public AiSettingsView get() {
        return view(settingsStore.current());
    }

    @PostMapping
    public AiSettingsView update(@Valid @RequestBody UpdateAiSettingsRequest request) {
        AiSettings saved = settingsStore.update(new AiSettingsStore.AiSettingsUpdate(
                request.providerName(),
                request.baseUrl(),
                request.model(),
                request.apiKey(),
                request.orgId(),
                request.clearApiKey()
        ));
        return view(saved);
    }

    private AiSettingsView view(AiSettings settings) {
        return new AiSettingsView(
                settings.providerName(),
                settings.baseUrl(),
                settings.model(),
                settings.orgId(),
                settings.hasApiKey()
        );
    }

    public record UpdateAiSettingsRequest(
            @Size(max = 80) String providerName,
            @Size(max = 500) String baseUrl,
            @Size(max = 120) String model,
            @Size(max = 512) String apiKey,
            @Size(max = 80) String orgId,
            boolean clearApiKey
    ) {
    }
}
