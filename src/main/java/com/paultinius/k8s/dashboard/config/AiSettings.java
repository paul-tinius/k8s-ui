package com.paultinius.k8s.dashboard.config;

/**
 * The AI assist configuration saved from the Management tab's AI assist
 * panel. Unlike {@link LdapSettings}, this deliberately does not default
 * from {@code application.yml}/environment variables ({@link DashboardProperties.Ai})
 * - that server-level configuration is only ever used as a last-resort
 * model/timeout fallback in {@code TroubleshootService}, never as an
 * implicit provider, base URL, or API key, so a dashboard with
 * {@code DASHBOARD_AI_API_KEY} set never calls out on someone's behalf
 * until they've explicitly saved (or sent with Ask) their own
 * configuration. {@code apiKey} is held in cleartext only in memory; see
 * {@link SecretCipher} for how it's protected on disk.
 */
public record AiSettings(
        String providerName,
        String baseUrl,
        String model,
        String apiKey,
        String orgId
) {

    public static final AiSettings NONE = new AiSettings("", "", "", "", "");

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
