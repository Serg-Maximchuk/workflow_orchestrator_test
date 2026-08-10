package com.example.sil.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Inbound security is out of scope for this project - the OAuth2 dependency is here for the
 * <em>outbound</em> direction, where the supplier API requires a client-credentials token.
 *
 * <p>Spring Security is on the classpath as a result, and its default filter chain would lock down
 * every endpoint. This chain opens them again explicitly, so the choice is visible in code rather
 * than being an accident of which starter was added.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // No browser clients and no cookies: a token-authenticated API has nothing for
                // CSRF to protect.
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
