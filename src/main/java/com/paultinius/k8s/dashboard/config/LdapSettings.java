package com.paultinius.k8s.dashboard.config;

/**
 * The effective LDAP configuration: either the defaults bound from
 * {@code application.yml}/environment variables, or an override saved from
 * the Management tab's LDAP panel. {@code bindPassword} is held in
 * cleartext only in memory; see {@link SecretCipher} for how it's protected
 * on disk.
 */
public record LdapSettings(
        boolean enabled,
        String host,
        int port,
        String defaultDomain,
        String searchBaseDn,
        String group,
        String bindUsername,
        String bindPassword
) {

    public static LdapSettings fromProperties(DashboardProperties.Ldap ldap) {
        return new LdapSettings(
                ldap.isEnabled(),
                ldap.getHost(),
                ldap.getPort(),
                ldap.getDefaultDomain(),
                ldap.getSearchBaseDn(),
                ldap.getGroup(),
                ldap.getBindUsername(),
                ldap.getBindPassword()
        );
    }

    /** The user-principal-name domain suffix, e.g. {@code us.lmco.com}. */
    public String userPrincipalDomain() {
        return defaultDomain + ".lmco.com";
    }

    public boolean hasBindPassword() {
        return bindPassword != null && !bindPassword.isBlank();
    }
}
