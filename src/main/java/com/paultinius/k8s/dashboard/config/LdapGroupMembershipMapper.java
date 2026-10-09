package com.paultinius.k8s.dashboard.config;

import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Rejects an otherwise-valid Active Directory login unless the account is a
 * member of the configured access group. {@code ctx} already carries the
 * authenticated user's own attributes - including {@code memberOf} - because
 * {@link org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider}
 * searches for them using the credentials just presented, so no extra
 * directory round trip is needed here.
 */
public class LdapGroupMembershipMapper implements UserDetailsContextMapper {

    private final LdapGroupDnResolver groupDnResolver;

    public LdapGroupMembershipMapper(LdapGroupDnResolver groupDnResolver) {
        this.groupDnResolver = groupDnResolver;
    }

    @Override
    public UserDetails mapUserFromContext(
            DirContextOperations ctx, String username, Collection<? extends GrantedAuthority> authorities) {
        String groupDn = groupDnResolver.groupDn();
        String[] memberOf = ctx.getStringAttributes("memberOf");
        boolean isMember = memberOf != null && Arrays.stream(memberOf).anyMatch(groupDn::equalsIgnoreCase);
        if (!isMember) {
            throw new DisabledException("That account is not a member of the required access group");
        }
        return new User(username, "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Override
    public void mapUserToContext(UserDetails user, DirContextAdapter ctx) {
        throw new UnsupportedOperationException("Dashboard LDAP users are read-only");
    }
}
