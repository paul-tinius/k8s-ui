package com.paultinius.k8s.dashboard.config;

import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.support.LdapEncoder;

import java.util.List;

/**
 * Resolves the distinguished name of the Active Directory group that gates
 * dashboard access, so a login's {@code memberOf} values can be compared
 * against it by distinguished name rather than by name alone.
 *
 * <p>The lookup needs its own service-account bind (the dashboard's end
 * users aren't guaranteed rights to search the directory before they've
 * logged in), and is resolved once and cached per instance: build a new one
 * whenever the underlying {@link LdapTemplate} or group name changes - see
 * {@link DynamicActiveDirectoryAuthenticationProvider}, which does exactly
 * that whenever {@link LdapSettingsStore}'s settings change.
 */
public class LdapGroupDnResolver {

    private final LdapTemplate ldapTemplate;
    private final String group;
    private volatile String groupDn;

    public LdapGroupDnResolver(LdapTemplate ldapTemplate, String group) {
        this.ldapTemplate = ldapTemplate;
        this.group = group;
    }

    public String groupDn() {
        String resolved = groupDn;
        if (resolved == null) {
            synchronized (this) {
                resolved = groupDn;
                if (resolved == null) {
                    resolved = resolve();
                    groupDn = resolved;
                }
            }
        }
        return resolved;
    }

    private String resolve() {
        String filter = "(&(objectClass=group)(cn=" + LdapEncoder.filterEncode(group) + "))";
        List<String> found = ldapTemplate.search(
                "", filter, (AttributesMapper<String>) attributes -> {
                    Object value = attributes.get("distinguishedName").get();
                    return String.valueOf(value);
                });
        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "No Active Directory group named '" + group + "' was found under the configured search base");
        }
        return found.get(0);
    }
}
