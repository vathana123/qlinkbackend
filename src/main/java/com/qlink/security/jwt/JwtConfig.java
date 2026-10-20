package com.qlink.security.jwt;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.context.annotation.*;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.io.IOException;
import java.util.UUID;

@Configuration
public class JwtConfig {
    @Bean
    RSAPublicKey rsaPublicKey(JwtProperties properties) throws IOException {
        try (var in = properties.publicKey().getInputStream()) { return RsaKeyConverters.x509().convert(in); }
    }
    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties, RSAPublicKey publicKey) throws IOException {
        RSAPrivateKey privateKey;
        try (var in = properties.privateKey().getInputStream()) { privateKey = RsaKeyConverters.pkcs8().convert(in); }
        if (publicKey.getModulus().bitLength() < 2048 || !publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalStateException("JWT keys must be a matching RSA pair of at least 2048 bits");
        }
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
    }
    @Bean @Primary
    JwtDecoder accessJwtDecoder(RSAPublicKey key, JwtProperties properties) { return decoder(key, properties, "access"); }
    @Bean
    JwtDecoder refreshJwtDecoder(RSAPublicKey key, JwtProperties properties) { return decoder(key, properties, "refresh"); }

    private JwtDecoder decoder(RSAPublicKey key, JwtProperties properties, String use) {
        var decoder = NimbusJwtDecoder.withPublicKey(key).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            try {
                UUID.fromString(jwt.getSubject());
                UUID.fromString(jwt.getClaimAsString("sid"));
                if (!use.equals(jwt.getClaimAsString("token_use")) || jwt.getExpiresAt() == null
                        || jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(java.time.Instant.now().plusSeconds(60)))
                    throw new IllegalArgumentException();
                return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException ex) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid application token", null));
            }
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(properties.issuer()), claims));
        return decoder;
    }
}
