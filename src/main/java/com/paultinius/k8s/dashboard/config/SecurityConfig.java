package com.paultinius.k8s.dashboard.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.io.IOException;

/**
 * Replaces the previous shared-token {@code AccessFilter} with session-based
 * login. Two mutually exclusive filter chains are registered depending on
 * {@code dashboard.ldap.enabled}:
 *
 * <ul>
 *   <li>disabled (the default): every route stays public - the same no-auth
 *       experience the dashboard had before, used for local development, the
 *       demo cluster, and tests;</li>
 *   <li>enabled: a user must authenticate against Active Directory and be a
 *       member of {@code dashboard.ldap.group} before any route other than
 *       the login page and static assets is reachable.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/", "/index.html", "/login.html", "/login.js", "/app.js", "/app.css", "/favicon.svg",
            "/actuator/health", "/actuator/health/**", "/api/csrf"
    };

    @Bean
    @ConditionalOnProperty(prefix = "dashboard.ldap", name = "enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "dashboard.ldap", name = "enabled", havingValue = "true")
    SecurityFilterChain ldapFilterChain(HttpSecurity http, AuthenticationManager authenticationManager) throws Exception {
        http.authenticationManager(authenticationManager)
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .successHandler((request, response, authentication) -> response.setStatus(HttpStatus.OK.value()))
                        .failureHandler(SecurityConfig::writeLoginFailure))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpStatus.OK.value())))
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), PathPatternRequestMatcher.withDefaults().matcher("/api/**")));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "dashboard.ldap", name = "enabled", havingValue = "true")
    AuthenticationManager authenticationManager(LdapSettingsStore settingsStore) {
        return new ProviderManager(new DynamicActiveDirectoryAuthenticationProvider(settingsStore));
    }

    private static void writeLoginFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = exception instanceof DisabledException
                ? exception.getMessage()
                : "Username or password was rejected.";
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
