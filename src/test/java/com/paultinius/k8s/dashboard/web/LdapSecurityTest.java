package com.paultinius.k8s.dashboard.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the LDAP-enabled filter chain's routing and failure handling.
 * There is no live Active Directory in this environment, so successful
 * login and group-membership gating are covered separately (and without a
 * network dependency) by {@link com.paultinius.k8s.dashboard.config.LdapGroupMembershipMapperTest}
 * and {@link com.paultinius.k8s.dashboard.config.LdapGroupDnResolverTest}.
 */
@SpringBootTest(properties = {
        "dashboard.ldap.enabled=true",
        "dashboard.ldap.host=127.0.0.1",
        "dashboard.ldap.port=1",
        "dashboard.ldap.default-domain=us",
        "dashboard.ldap.search-base-dn=dc=us,dc=lmco,dc=com",
        "dashboard.ldap.group=K8sDashboardUsers",
        "dashboard.ldap.bind-username=svc-dashboard",
        "dashboard.ldap.bind-password=test"
})
@AutoConfigureMockMvc
class LdapSecurityTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("dashboard.cluster.data-dir", () -> Path.of(
                System.getProperty("java.io.tmpdir"),
                "k8s-dashboard-ldap-" + UUID.randomUUID()
        ).toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void clusters_noSession_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/clusters")).andExpect(status().isUnauthorized());
    }

    @Test
    void index_withoutSession_staysPublic() throws Exception {
        mockMvc.perform(get("/index.html")).andExpect(status().isOk());
    }

    @Test
    void loginPage_withoutSession_staysPublic() throws Exception {
        mockMvc.perform(get("/login.html")).andExpect(status().isOk());
    }

    @Test
    void login_unreachableDirectory_isRejectedNotCrashed() throws Exception {
        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "jdoe")
                        .param("password", "wrong")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
    }
}
