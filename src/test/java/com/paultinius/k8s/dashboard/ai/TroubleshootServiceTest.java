package com.paultinius.k8s.dashboard.ai;

import com.paultinius.k8s.dashboard.cluster.ClusterClient;
import com.paultinius.k8s.dashboard.cluster.ClusterRegistry;
import com.paultinius.k8s.dashboard.config.AiSettings;
import com.paultinius.k8s.dashboard.config.AiSettingsStore;
import com.paultinius.k8s.dashboard.config.DashboardProperties;
import com.paultinius.k8s.dashboard.error.DashboardException;
import com.paultinius.k8s.dashboard.model.AskResponse;
import com.paultinius.k8s.dashboard.model.ClusterInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TroubleshootServiceTest {

    @Mock
    private ClusterRegistry clusters;

    @Mock
    private AiClient client;

    @Mock
    private ClusterClient cluster;

    @Mock
    private AiSettingsStore settingsStore;

    @Test
    void ask_blankProvider_staysLocalEvenWhenTheServerHasAKey() {
        DashboardProperties properties = configured();
        when(clusters.require("demo")).thenReturn(cluster);
        noSavedOverride();
        TroubleshootService service = new TroubleshootService(clusters, client, properties, settingsStore);

        AskResponse response = service.ask("demo", "", "", "", "why is it failing?", false, "", "", "", "", "");

        assertThat(response.modelUsed()).isFalse();
        assertThat(response.answer()).contains("Assist is off");
        verify(client, never()).complete(any(), any(), any());
    }

    @Test
    void ask_userProvider_callsThatBaseUrlWithTheBrowserKey() {
        DashboardProperties properties = configured();
        when(clusters.require("demo")).thenReturn(cluster);
        when(cluster.info()).thenReturn(new ClusterInfo("demo", "Demo", "https://demo", "shop", true, true, "demo"));
        when(client.complete(any(), any(), any())).thenReturn("from the model");
        noSavedOverride();
        TroubleshootService service = new TroubleshootService(clusters, client, properties, settingsStore);

        AskResponse response = service.ask(
                "demo", "", "", "", "why is it failing?", false,
                "Acme", "https://models.example/v1", "acme-large", "browser-key", "");

        assertThat(response.modelUsed()).isTrue();
        assertThat(response.provider()).isEqualTo("Acme");
        assertThat(response.model()).isEqualTo("acme-large");
        assertThat(response.answer()).isEqualTo("from the model");
        ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(client).complete(captor.capture(), any(), any());
        assertThat(captor.getValue().baseUrl()).isEqualTo("https://models.example/v1");
        assertThat(captor.getValue().apiKey()).isEqualTo("browser-key");
        assertThat(captor.getValue().model()).isEqualTo("acme-large");
    }

    @Test
    void ask_blankModel_usesTheServerModel() {
        when(clusters.require("demo")).thenReturn(cluster);
        when(cluster.info()).thenReturn(new ClusterInfo("demo", "Demo", "https://demo", "shop", true, true, "demo"));
        when(client.complete(any(), any(), any())).thenReturn("from the model");
        noSavedOverride();
        TroubleshootService service = new TroubleshootService(clusters, client, configured(), settingsStore);

        service.ask("demo", "", "", "", "why is it failing?", false, "Acme", "https://models.example/v1", "", "browser-key", "");

        ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(client).complete(captor.capture(), any(), any());
        assertThat(captor.getValue().model()).isEqualTo("grok-4.7");
    }

    @Test
    void ask_missingBaseUrl_isRejected() {
        noSavedOverride();
        TroubleshootService service = new TroubleshootService(clusters, client, configured(), settingsStore);

        assertThatThrownBy(() -> service.ask("demo", "", "", "", "why is it failing?", false, "Acme", "", "", "browser-key", ""))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("Base URL");
        verify(client, never()).complete(any(), any(), any());
    }

    @Test
    void ask_devinWithoutOrganization_isRejected() {
        noSavedOverride();
        TroubleshootService service = new TroubleshootService(clusters, client, new DashboardProperties(), settingsStore);

        assertThatThrownBy(() -> service.ask(
                "demo", "", "", "", "why is it failing?", false,
                "Devin", "https://api.devin.ai/v3", "lite", "cog_user_key", ""))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("organization id");
        verify(client, never()).complete(any(), any(), any());
    }

    @Test
    void ask_blankRequest_fallsBackToTheSavedAssistConfiguration() {
        when(clusters.require("demo")).thenReturn(cluster);
        when(cluster.info()).thenReturn(new ClusterInfo("demo", "Demo", "https://demo", "shop", true, true, "demo"));
        when(client.complete(any(), any(), any())).thenReturn("from the saved config");
        when(settingsStore.current()).thenReturn(
                new AiSettings("Acme", "https://models.example/v1", "acme-large", "saved-key", ""));
        TroubleshootService service = new TroubleshootService(clusters, client, configured(), settingsStore);

        AskResponse response = service.ask("demo", "", "", "", "why is it failing?", false, "", "", "", "", "");

        assertThat(response.modelUsed()).isTrue();
        ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(client).complete(captor.capture(), any(), any());
        assertThat(captor.getValue().baseUrl()).isEqualTo("https://models.example/v1");
        assertThat(captor.getValue().apiKey()).isEqualTo("saved-key");
    }

    private void noSavedOverride() {
        when(settingsStore.current()).thenReturn(new AiSettings("", "", "", "", ""));
    }

    private static DashboardProperties configured() {
        DashboardProperties properties = new DashboardProperties();
        properties.getAi().setApiKey("server-key");
        return properties;
    }
}
