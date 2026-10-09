package com.paultinius.k8s.dashboard.config;

import com.paultinius.k8s.dashboard.error.DashboardException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LdapSettingsStoreTest {

    @TempDir
    Path tempDir;

    private DashboardProperties properties() {
        DashboardProperties properties = new DashboardProperties();
        properties.getCluster().setDataDir(tempDir.toString());
        properties.getLdap().setEnabled(false);
        properties.getLdap().setHost("acct01.us.lmco.com");
        properties.getLdap().setPort(3269);
        properties.getLdap().setDefaultDomain("us");
        properties.getLdap().setSearchBaseDn("dc=us,dc=lmco,dc=com");
        properties.getLdap().setGroup("DefaultGroup");
        properties.getLdap().setBindUsername("default-bind");
        properties.getLdap().setBindPassword("default-pass");
        return properties;
    }

    private static LdapSettingsStore.LdapSettingsUpdate update(
            boolean enabled, String host, String group, String bindUsername, String bindPassword) {
        return new LdapSettingsStore.LdapSettingsUpdate(
                enabled, host, 3269, "us", "dc=us,dc=lmco,dc=com", group, bindUsername, bindPassword, false);
    }

    @Test
    void current_withNoSavedOverride_returnsApplicationYmlDefaults() {
        DashboardProperties properties = properties();
        LdapSettingsStore store = new LdapSettingsStore(properties, new SecretCipher(properties));

        LdapSettings current = store.current();

        assertThat(current.enabled()).isFalse();
        assertThat(current.host()).isEqualTo("acct01.us.lmco.com");
        assertThat(current.group()).isEqualTo("DefaultGroup");
        assertThat(current.bindPassword()).isEqualTo("default-pass");
    }

    @Test
    void update_isPersistedAndPickedUpByANewStoreInstance() {
        DashboardProperties properties = properties();
        SecretCipher cipher = new SecretCipher(properties);
        LdapSettingsStore store = new LdapSettingsStore(properties, cipher);

        store.update(update(true, "new-host.us.lmco.com", "NewGroup", "svc-new", "new-secret"));

        LdapSettingsStore reloaded = new LdapSettingsStore(properties, cipher);
        LdapSettings current = reloaded.current();
        assertThat(current.enabled()).isTrue();
        assertThat(current.host()).isEqualTo("new-host.us.lmco.com");
        assertThat(current.group()).isEqualTo("NewGroup");
        assertThat(current.bindUsername()).isEqualTo("svc-new");
        assertThat(current.bindPassword()).isEqualTo("new-secret");
    }

    @Test
    void update_blankBindPassword_keepsTheExistingOne() {
        DashboardProperties properties = properties();
        LdapSettingsStore store = new LdapSettingsStore(properties, new SecretCipher(properties));
        store.update(update(true, "host.us.lmco.com", "Group", "bind-user", "first-secret"));

        store.update(update(true, "host.us.lmco.com", "Group", "bind-user", ""));

        assertThat(store.current().bindPassword()).isEqualTo("first-secret");
    }

    @Test
    void update_clearBindPassword_removesIt() {
        DashboardProperties properties = properties();
        LdapSettingsStore store = new LdapSettingsStore(properties, new SecretCipher(properties));
        store.update(update(true, "host.us.lmco.com", "Group", "bind-user", "first-secret"));

        store.update(new LdapSettingsStore.LdapSettingsUpdate(
                true, "host.us.lmco.com", 3269, "us", "dc=us,dc=lmco,dc=com", "Group", "bind-user", "", true));

        assertThat(store.current().bindPassword()).isEmpty();
        assertThat(store.current().hasBindPassword()).isFalse();
    }

    @Test
    void update_passwordOnDisk_isNotStoredInCleartext() throws IOException {
        DashboardProperties properties = properties();
        LdapSettingsStore store = new LdapSettingsStore(properties, new SecretCipher(properties));

        store.update(update(true, "host.us.lmco.com", "Group", "bind-user", "super-secret-value"));

        String saved = Files.readString(tempDir.resolve("ldap-settings.yml"));
        assertThat(saved).doesNotContain("super-secret-value");
    }

    @Test
    void update_enabledWithoutHost_isRejected() {
        DashboardProperties properties = properties();
        LdapSettingsStore store = new LdapSettingsStore(properties, new SecretCipher(properties));

        assertThatThrownBy(() -> store.update(update(true, "", "Group", "bind-user", "secret")))
                .isInstanceOf(DashboardException.class);
    }

    @Test
    void update_disabledWithoutHost_isAccepted() {
        DashboardProperties properties = properties();
        LdapSettingsStore store = new LdapSettingsStore(properties, new SecretCipher(properties));

        LdapSettings saved = store.update(update(false, "", "", "", ""));

        assertThat(saved.enabled()).isFalse();
    }

    @Test
    void restartRequired_reflectsWhetherEnabledChangedSinceStartup() {
        DashboardProperties properties = properties();
        LdapSettingsStore store = new LdapSettingsStore(properties, new SecretCipher(properties));
        assertThat(store.restartRequired()).isFalse();

        store.update(update(true, "host.us.lmco.com", "Group", "bind-user", "secret"));
        assertThat(store.restartRequired()).isTrue();

        store.update(update(false, "host.us.lmco.com", "Group", "bind-user", "secret"));
        assertThat(store.restartRequired()).isFalse();
    }
}
