package com.qlink.config;
import com.qlink.common.response.ApiError;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json,
            com.qlink.features.auth.service.SessionAuthorization sessions) throws Exception {
        var entryPoint = (org.springframework.security.web.AuthenticationEntryPoint) (request, response, ex) -> {
            response.setStatus(401); response.setContentType("application/json");
            response.getWriter().write(json.writeValueAsString(ApiError.of("UNAUTHORIZED", "A valid application access token is required")));
        };
        return http.cors(cors -> {}).csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/ws").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/signin", "/api/v1/auth/refresh", "/api/v1/auth/nonce").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler((request, response, ex) -> {
                    response.setStatus(403); response.setContentType("application/json");
                    response.getWriter().write(json.writeValueAsString(ApiError.of("FORBIDDEN", "Access denied")));
                }))
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwt -> {
                    try {
                        sessions.actor(jwt);
                    } catch (com.qlink.common.exception.ApiException ex) {
                        throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                                new org.springframework.security.oauth2.core.OAuth2Error(ex.getCode()), ex);
                    }
                    return new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter().convert(jwt);
                })).authenticationEntryPoint(entryPoint))
                .build();
    }
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${qlink.cors.allowed-origins}") List<String> origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
