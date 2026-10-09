package com.paultinius.k8s.dashboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LdapGroupMembershipMapperTest {

    private static final String GROUP_DN = "CN=K8sDashboardUsers,OU=Groups,DC=us,DC=lmco,DC=com";

    private final LdapGroupDnResolver resolver = mock(LdapGroupDnResolver.class);
    private final LdapGroupMembershipMapper mapper = new LdapGroupMembershipMapper(resolver);

    @Test
    void mapUserFromContext_memberOfTheConfiguredGroup_returnsAUser() {
        when(resolver.groupDn()).thenReturn(GROUP_DN);
        DirContextOperations ctx = mock(DirContextOperations.class);
        when(ctx.getStringAttributes("memberOf")).thenReturn(new String[]{
                "CN=OtherGroup,OU=Groups,DC=us,DC=lmco,DC=com",
                GROUP_DN.toLowerCase()
        });

        UserDetails user = mapper.mapUserFromContext(ctx, "jdoe", List.of());

        assertThat(user.getUsername()).isEqualTo("jdoe");
        assertThat(user.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }

    @Test
    void mapUserFromContext_notAMember_isRejected() {
        when(resolver.groupDn()).thenReturn(GROUP_DN);
        DirContextOperations ctx = mock(DirContextOperations.class);
        when(ctx.getStringAttributes("memberOf")).thenReturn(new String[]{
                "CN=OtherGroup,OU=Groups,DC=us,DC=lmco,DC=com"
        });

        assertThatThrownBy(() -> mapper.mapUserFromContext(ctx, "jdoe", List.of()))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void mapUserFromContext_noGroupsAtAll_isRejected() {
        when(resolver.groupDn()).thenReturn(GROUP_DN);
        DirContextOperations ctx = mock(DirContextOperations.class);
        when(ctx.getStringAttributes("memberOf")).thenReturn(null);

        assertThatThrownBy(() -> mapper.mapUserFromContext(ctx, "jdoe", List.of()))
                .isInstanceOf(DisabledException.class);
    }
}
