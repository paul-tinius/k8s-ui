package com.paultinius.k8s.dashboard.config;

import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;

/**
 * Delegates to a real {@link ActiveDirectoryLdapAuthenticationProvider}
 * built from whatever {@link LdapSettingsStore} currently holds, rebuilding
 * it only when those settings actually change. This lets the Management
 * tab's LDAP panel edit the host, bind account, group, and so on without a
 * restart - a plain {@code ActiveDirectoryLdapAuthenticationProvider} bean
 * would otherwise bake its connection details in at construction time.
 */
public class DynamicActiveDirectoryAuthenticationProvider implements AuthenticationProvider {

    private final LdapSettingsStore settingsStore;
    private volatile Built built;

    public DynamicActiveDirectoryAuthenticationProvider(LdapSettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        return delegateFor(settingsStore.current()).authenticate(authentication);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private ActiveDirectoryLdapAuthenticationProvider delegateFor(LdapSettings settings) {
        Built snapshot = built;
        if (snapshot != null && snapshot.settings().equals(settings)) {
            return snapshot.provider();
        }
        synchronized (this) {
            if (built != null && built.settings().equals(settings)) {
                return built.provider();
            }
            Built fresh = build(settings);
            built = fresh;
            return fresh.provider();
        }
    }

    private static Built build(LdapSettings settings) {
        String url = "ldaps://" + settings.host() + ":" + settings.port();
        try {
            LdapContextSource contextSource = new LdapContextSource();
            contextSource.setUrl(url);
            contextSource.setBase(settings.searchBaseDn());
            contextSource.setUserDn(settings.bindUsername() + "@" + settings.userPrincipalDomain());
            contextSource.setPassword(settings.bindPassword());
            contextSource.afterPropertiesSet();
            LdapTemplate ldapTemplate = new LdapTemplate(contextSource);
            LdapGroupDnResolver groupDnResolver = new LdapGroupDnResolver(ldapTemplate, settings.group());

            ActiveDirectoryLdapAuthenticationProvider provider = new ActiveDirectoryLdapAuthenticationProvider(
                    settings.userPrincipalDomain(), url, settings.searchBaseDn());
            provider.setConvertSubErrorCodesToExceptions(true);
            provider.setUserDetailsContextMapper(new LdapGroupMembershipMapper(groupDnResolver));
            return new Built(settings, provider);
        } catch (Exception exception) {
            throw new AuthenticationServiceException("Could not prepare the LDAP connection", exception);
        }
    }

    private record Built(LdapSettings settings, ActiveDirectoryLdapAuthenticationProvider provider) {
    }
}
