package com.paultinius.k8s.dashboard.model;

/**
 * The LDAP configuration shown and edited in the Management tab's LDAP
 * panel. {@code bindPassword} itself is never returned - only whether one
 * is currently set.
 */
public record LdapSettingsView(
        boolean enabled,
        String host,
        int port,
        String defaultDomain,
        String searchBaseDn,
        String group,
        String bindUsername,
        boolean hasBindPassword,
        boolean restartRequired
) {
}
