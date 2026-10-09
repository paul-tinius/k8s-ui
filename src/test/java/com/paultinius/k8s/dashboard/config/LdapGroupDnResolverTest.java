package com.paultinius.k8s.dashboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LdapGroupDnResolverTest {

    private static final String GROUP_DN = "CN=K8sDashboardUsers,OU=Groups,DC=us,DC=lmco,DC=com";

    private final LdapTemplate ldapTemplate = mock(LdapTemplate.class);

    @Test
    void groupDn_found_isCachedAfterTheFirstLookup() {
        when(ldapTemplate.search(anyString(), anyString(), any(AttributesMapper.class)))
                .thenReturn(List.of(GROUP_DN));

        LdapGroupDnResolver resolver = new LdapGroupDnResolver(ldapTemplate, "K8sDashboardUsers");

        assertThat(resolver.groupDn()).isEqualTo(GROUP_DN);
        assertThat(resolver.groupDn()).isEqualTo(GROUP_DN);
        verify(ldapTemplate, times(1)).search(anyString(), anyString(), any(AttributesMapper.class));
    }

    @Test
    void groupDn_missingGroup_throws() {
        when(ldapTemplate.search(anyString(), anyString(), any(AttributesMapper.class)))
                .thenReturn(List.of());

        LdapGroupDnResolver resolver = new LdapGroupDnResolver(ldapTemplate, "NoSuchGroup");

        assertThatThrownBy(resolver::groupDn).isInstanceOf(IllegalStateException.class);
    }
}
