package com.paultinius.k8s.dashboard.config;

import com.paultinius.k8s.dashboard.cluster.YamlMaps;
import com.paultinius.k8s.dashboard.error.DashboardException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The live, effective AI assist configuration last saved from the
 * Management tab's AI assist panel - see {@link AiSettings} for why this
 * does not default from {@code application.yml}/environment variables.
 * Persisted under the dashboard's data directory - by default
 * {@code ~/.k8s-dashboard} (see {@link DataDirs}) - so it survives a
 * restart and is shared by every browser the user opens the dashboard in.
 *
 * <p>The API key is encrypted at rest with {@link SecretCipher} the same
 * way the LDAP bind password is; it is never sent back to the browser,
 * only whether one is currently set.
 */
@Component
public class AiSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(AiSettingsStore.class);

    private final Path file;
    private final SecretCipher cipher;
    private final AtomicReference<AiSettings> current;

    public AiSettingsStore(DashboardProperties properties, SecretCipher cipher) {
        this.cipher = cipher;
        this.file = DataDirs.resolve(properties).resolve("ai-settings.yml");
        this.current = new AtomicReference<>(loadOverride(AiSettings.NONE));
    }

    public AiSettings current() {
        return current.get();
    }

    public synchronized AiSettings update(AiSettingsUpdate update) {
        AiSettings existing = current.get();
        String apiKey = update.clearApiKey()
                ? ""
                : (update.apiKey() == null || update.apiKey().isBlank())
                        ? existing.apiKey()
                        : update.apiKey();
        AiSettings next = new AiSettings(
                clean(update.providerName()),
                clean(update.baseUrl()),
                clean(update.model()),
                apiKey,
                clean(update.orgId())
        );
        persist(next);
        current.set(next);
        return next;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private AiSettings loadOverride(AiSettings defaults) {
        if (!Files.isRegularFile(file)) {
            return defaults;
        }
        try {
            Map<String, Object> root = YamlMaps.object(YamlMaps.load(Files.readString(file)));
            if (root.isEmpty()) {
                return defaults;
            }
            String apiKeyEncrypted = YamlMaps.text(root, "apiKeyEncrypted");
            String apiKey = apiKeyEncrypted.isBlank() ? defaults.apiKey() : cipher.decrypt(apiKeyEncrypted);
            return new AiSettings(
                    textOr(root, "providerName", defaults.providerName()),
                    textOr(root, "baseUrl", defaults.baseUrl()),
                    textOr(root, "model", defaults.model()),
                    apiKey,
                    textOr(root, "orgId", defaults.orgId())
            );
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not read saved AI assist settings from {}: {}", file, exception.getMessage());
            return defaults;
        }
    }

    private static String textOr(Map<String, Object> root, String key, String fallback) {
        String value = YamlMaps.text(root, key);
        return value.isBlank() ? fallback : value;
    }

    private void persist(AiSettings settings) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("providerName", settings.providerName());
        root.put("baseUrl", settings.baseUrl());
        root.put("model", settings.model());
        root.put("orgId", settings.orgId());
        root.put("apiKeyEncrypted", cipher.encrypt(settings.apiKey()));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, YamlMaps.dump(root));
            try {
                Files.setPosixFilePermissions(file, Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                ));
            } catch (UnsupportedOperationException ignored) {
                // Windows has no POSIX modes.
            }
        } catch (IOException exception) {
            throw new DashboardException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the AI assist settings locally");
        }
    }

    /** Fields the Management tab's AI assist panel can change. A blank {@code apiKey} keeps
     * the currently stored one; set {@code clearApiKey} to actually remove it. */
    public record AiSettingsUpdate(
            String providerName,
            String baseUrl,
            String model,
            String apiKey,
            String orgId,
            boolean clearApiKey
    ) {
    }
}
