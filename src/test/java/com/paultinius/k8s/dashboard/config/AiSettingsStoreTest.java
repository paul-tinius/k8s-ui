package com.paultinius.k8s.dashboard.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiSettingsStoreTest {

    @TempDir
    Path tempDir;

    private DashboardProperties properties() {
        DashboardProperties properties = new DashboardProperties();
        properties.getCluster().setDataDir(tempDir.toString());
        properties.getAi().setProvider("xAI");
        properties.getAi().setBaseUrl("https://api.x.ai/v1");
        properties.getAi().setModel("grok-4.7");
        properties.getAi().setApiKey("server-key");
        return properties;
    }

    private static AiSettingsStore.AiSettingsUpdate update(
            String providerName, String baseUrl, String model, String apiKey) {
        return new AiSettingsStore.AiSettingsUpdate(providerName, baseUrl, model, apiKey, "", false);
    }

    @Test
    void current_withNoSavedOverride_isBlankEvenWhenApplicationYmlHasItsOwnDefaults() {
        // Deliberate: application.yml / env vars (DashboardProperties.Ai) are only ever
        // used as a last-resort model/timeout fallback in TroubleshootService, never as
        // an implicit provider, base URL, or API key - see AiSettings.
        DashboardProperties properties = properties();
        AiSettingsStore store = new AiSettingsStore(properties, new SecretCipher(properties));

        AiSettings current = store.current();

        assertThat(current.providerName()).isEmpty();
        assertThat(current.baseUrl()).isEmpty();
        assertThat(current.model()).isEmpty();
        assertThat(current.apiKey()).isEmpty();
    }

    @Test
    void update_isPersistedAndPickedUpByANewStoreInstance() {
        DashboardProperties properties = properties();
        SecretCipher cipher = new SecretCipher(properties);
        AiSettingsStore store = new AiSettingsStore(properties, cipher);

        store.update(update("Acme", "https://models.example/v1", "acme-large", "new-secret"));

        AiSettingsStore reloaded = new AiSettingsStore(properties, cipher);
        AiSettings current = reloaded.current();
        assertThat(current.providerName()).isEqualTo("Acme");
        assertThat(current.baseUrl()).isEqualTo("https://models.example/v1");
        assertThat(current.model()).isEqualTo("acme-large");
        assertThat(current.apiKey()).isEqualTo("new-secret");
    }

    @Test
    void update_blankApiKey_keepsTheExistingOne() {
        DashboardProperties properties = properties();
        AiSettingsStore store = new AiSettingsStore(properties, new SecretCipher(properties));
        store.update(update("Acme", "https://models.example/v1", "acme-large", "first-secret"));

        store.update(update("Acme", "https://models.example/v1", "acme-large", ""));

        assertThat(store.current().apiKey()).isEqualTo("first-secret");
    }

    @Test
    void update_clearApiKey_removesIt() {
        DashboardProperties properties = properties();
        AiSettingsStore store = new AiSettingsStore(properties, new SecretCipher(properties));
        store.update(update("Acme", "https://models.example/v1", "acme-large", "first-secret"));

        store.update(new AiSettingsStore.AiSettingsUpdate(
                "Acme", "https://models.example/v1", "acme-large", "", "", true));

        assertThat(store.current().apiKey()).isEmpty();
        assertThat(store.current().hasApiKey()).isFalse();
    }

    @Test
    void update_apiKeyOnDisk_isNotStoredInCleartext() throws IOException {
        DashboardProperties properties = properties();
        AiSettingsStore store = new AiSettingsStore(properties, new SecretCipher(properties));

        store.update(update("Acme", "https://models.example/v1", "acme-large", "super-secret-value"));

        String saved = Files.readString(tempDir.resolve("ai-settings.yml"));
        assertThat(saved).doesNotContain("super-secret-value");
    }
}
