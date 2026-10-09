package com.paultinius.k8s.dashboard.web;

import com.paultinius.k8s.dashboard.config.LdapSettings;
import com.paultinius.k8s.dashboard.config.LdapSettingsStore;
import com.paultinius.k8s.dashboard.model.LdapSettingsView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backs the Management tab's LDAP panel. Reading and saving this requires
 * being authenticated whenever LDAP login is enabled - the same
 * {@code anyRequest().authenticated()} rule {@link com.paultinius.k8s.dashboard.config.SecurityConfig}
 * applies to the rest of the API.
 */
@RestController
@RequestMapping("/api/management/ldap")
public class LdapSettingsController {

    private final LdapSettingsStore settingsStore;

    public LdapSettingsController(LdapSettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    @GetMapping
    public LdapSettingsView get() {
        return view(settingsStore.current());
    }

    @PostMapping
    public LdapSettingsView update(@Valid @RequestBody UpdateLdapSettingsRequest request) {
        LdapSettings saved = settingsStore.update(new LdapSettingsStore.LdapSettingsUpdate(
                request.enabled(),
                request.host(),
                request.port(),
                request.defaultDomain(),
                request.searchBaseDn(),
                request.group(),
                request.bindUsername(),
                request.bindPassword(),
                request.clearBindPassword()
        ));
        return view(saved);
    }

    private LdapSettingsView view(LdapSettings settings) {
        return new LdapSettingsView(
                settings.enabled(),
                settings.host(),
                settings.port(),
                settings.defaultDomain(),
                settings.searchBaseDn(),
                settings.group(),
                settings.bindUsername(),
                settings.hasBindPassword(),
                settingsStore.restartRequired()
        );
    }

    public record UpdateLdapSettingsRequest(
            boolean enabled,
            @Size(max = 255) String host,
            int port,
            @Size(max = 120) String defaultDomain,
            @Size(max = 500) String searchBaseDn,
            @Size(max = 200) String group,
            @Size(max = 200) String bindUsername,
            @Size(max = 512) String bindPassword,
            boolean clearBindPassword
    ) {
    }
}
