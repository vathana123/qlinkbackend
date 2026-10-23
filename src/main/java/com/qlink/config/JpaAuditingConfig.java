package com.qlink.config;

import org.springframework.context.annotation.*;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.*;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditDateTimeProvider")
public class JpaAuditingConfig {
    @Bean
    org.springframework.data.auditing.DateTimeProvider auditDateTimeProvider() {
        // MySQL DATETIME(6) rounds nanoseconds; normalize before both persistence and response mapping.
        return () -> Optional.of(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Bean
    AuditorAware<UUID> auditorAware() {
        return () -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
                try { return Optional.of(UUID.fromString(jwt.getToken().getSubject())); }
                catch (IllegalArgumentException ignored) { return Optional.empty(); }
            }
            return Optional.empty();
        };
    }
}
