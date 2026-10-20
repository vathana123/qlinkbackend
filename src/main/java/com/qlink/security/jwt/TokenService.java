package com.qlink.security.jwt;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.UUID;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final JwtProperties properties;
    public TokenService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder; this.properties = properties;
    }
    public String access(UUID user, UUID session) {
        return encode(user, session, "access", Instant.now().plus(properties.accessTtl()));
    }
    public String refresh(UUID user, UUID session, Instant expiresAt) {
        return encode(user, session, "refresh", expiresAt);
    }
    private String encode(UUID user, UUID session, String use, Instant expiry) {
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).subject(user.toString())
                .issuedAt(Instant.now()).expiresAt(expiry).id(UUID.randomUUID().toString())
                .claim("sid", session.toString()).claim("token_use", use).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
}
