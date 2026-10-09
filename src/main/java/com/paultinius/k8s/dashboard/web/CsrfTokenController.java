package com.paultinius.k8s.dashboard.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolving {@link CsrfToken} as a method argument forces Spring Security's
 * deferred token to be generated and saved - which, with a cookie-backed
 * repository, is what actually writes the {@code XSRF-TOKEN} cookie. The
 * login page calls this once before submitting credentials so the login
 * POST (and every mutating API call afterward) has a token to send back.
 */
@RestController
@ConditionalOnProperty(prefix = "dashboard.ldap", name = "enabled", havingValue = "true")
public class CsrfTokenController {

    @GetMapping("/api/csrf")
    public void csrf(CsrfToken token) {
        // No body: the side effect (writing the cookie) is the point.
    }
}
