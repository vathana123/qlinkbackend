package com.qlink.security.jwt;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;
import java.time.Duration;

@Validated @ConfigurationProperties("qlink.jwt")
public record JwtProperties(@NotBlank String issuer, @NotNull Resource publicKey,
                            @NotNull Resource privateKey, @NotNull Duration accessTtl,
                            @NotNull Duration refreshTtl) {}
