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
 * The live, effective LDAP configuration. Starts from {@code application.yml}
 * / environment variables ({@link DashboardProperties.Ldap}); the Management
 * tab's LDAP panel can save an override on top of that, persisted under the
 * dashboard's data directory so it survives a restart.
 *
 * <p>Everything except {@code enabled} takes effect on the next login
 * attempt - see {@link DynamicActiveDirectoryAuthenticationProvider}, which
 * rebuilds its delegate whenever these settings change. {@code enabled}
 * itself picks which {@code SecurityFilterChain} bean
 * {@link SecurityConfig} registers, which is decided once at startup, so
 * flipping it still needs a restart; {@link #restartRequired()} reports
 * that so the UI can say so.
 */
@Component
public class LdapSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(LdapSettingsStore.class);

    private final Path file;
    private final SecretCipher cipher;
    private final boolean startupEnabled;
    private final AtomicReference<LdapSettings> current;

    public LdapSettingsStore(DashboardProperties properties, SecretCipher cipher) {
        this.cipher = cipher;
        this.file = DataDirs.resolve(properties).resolve("ldap-settings.yml");
        LdapSettings defaults = LdapSettings.fromProperties(properties.getLdap());
        this.startupEnabled = defaults.enabled();
        this.current = new AtomicReference<>(loadOverride(defaults));
    }

    public LdapSettings current() {
        return current.get();
    }

    public boolean restartRequired() {
        return current.get().enabled() != startupEnabled;
    }

    public synchronized LdapSettings update(LdapSettingsUpdate update) {
        LdapSettings existing = current.get();
        String bindPassword = update.clearBindPassword()
                ? ""
                : (update.bindPassword() == null || update.bindPassword().isBlank())
                        ? existing.bindPassword()
                        : update.bindPassword();
        LdapSettings next = new LdapSettings(
                update.enabled(),
                clean(update.host()),
                update.port(),
                clean(update.defaultDomain()),
                clean(update.searchBaseDn()),
                clean(update.group()),
                clean(update.bindUsername()),
                bindPassword
        );
        validate(next);
        persist(next);
        current.set(next);
        return next;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void validate(LdapSettings settings) {
        if (!settings.enabled()) {
            return;
        }
        require(settings.host(), "LDAP host");
        require(settings.defaultDomain(), "LDAP default domain");
        require(settings.searchBaseDn(), "LDAP search base DN");
        require(settings.group(), "LDAP group");
        require(settings.bindUsername(), "LDAP bind username");
        if (settings.port() < 1 || settings.port() > 65535) {
            throw DashboardException.badRequest("LDAP port must be between 1 and 65535");
        }
    }

    private static void require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw DashboardException.badRequest(label + " is required while LDAP login is enabled");
        }
    }

    private LdapSettings loadOverride(LdapSettings defaults) {
        if (!Files.isRegularFile(file)) {
            return defaults;
        }
        try {
            Map<String, Object> root = YamlMaps.object(YamlMaps.load(Files.readString(file)));
            if (root.isEmpty()) {
                return defaults;
            }
            String bindPasswordEncrypted = YamlMaps.text(root, "bindPasswordEncrypted");
            String bindPassword = bindPasswordEncrypted.isBlank()
                    ? defaults.bindPassword()
                    : cipher.decrypt(bindPasswordEncrypted);
            return new LdapSettings(
                    booleanOr(root, "enabled", defaults.enabled()),
                    textOr(root, "host", defaults.host()),
                    YamlMaps.integer(root, "port", defaults.port()),
                    textOr(root, "defaultDomain", defaults.defaultDomain()),
                    textOr(root, "searchBaseDn", defaults.searchBaseDn()),
                    textOr(root, "group", defaults.group()),
                    textOr(root, "bindUsername", defaults.bindUsername()),
                    bindPassword
            );
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not read saved LDAP settings from {}: {}", file, exception.getMessage());
            return defaults;
        }
    }

    private static String textOr(Map<String, Object> root, String key, String fallback) {
        String value = YamlMaps.text(root, key);
        return value.isBlank() ? fallback : value;
    }

    private static boolean booleanOr(Map<String, Object> root, String key, boolean fallback) {
        Object value = root.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private void persist(LdapSettings settings) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("enabled", settings.enabled());
        root.put("host", settings.host());
        root.put("port", settings.port());
        root.put("defaultDomain", settings.defaultDomain());
        root.put("searchBaseDn", settings.searchBaseDn());
        root.put("group", settings.group());
        root.put("bindUsername", settings.bindUsername());
        root.put("bindPasswordEncrypted", cipher.encrypt(settings.bindPassword()));
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
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the LDAP settings locally");
        }
    }

    /** Fields the Management tab's LDAP panel can change. A blank {@code bindPassword} keeps
     * the currently stored one; set {@code clearBindPassword} to actually remove it. */
    public record LdapSettingsUpdate(
            boolean enabled,
            String host,
            int port,
            String defaultDomain,
            String searchBaseDn,
            String group,
            String bindUsername,
            String bindPassword,
            boolean clearBindPassword
    ) {
    }
}
