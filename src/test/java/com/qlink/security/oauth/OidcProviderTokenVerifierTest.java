package com.qlink.security.oauth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import com.qlink.common.exception.ApiException;
import com.qlink.features.auth.enums.AuthProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class OidcProviderTokenVerifierTest {
    static KeyPair keys;
    @BeforeAll static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys = generator.generateKeyPair();
    }
    private OidcProviderTokenVerifier verifier(AuthProvider provider) {
        return verifier(provider, List.of());
    }
    private OidcProviderTokenVerifier verifier(AuthProvider provider, List<String> androidIds) {
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(OidcProviderTokenVerifier.validator(provider, List.of("client-id"), androidIds));
        return new OidcProviderTokenVerifier(Map.of(provider, decoder));
    }
    private JWTClaimsSet.Builder claims(AuthProvider provider) {
        return new JWTClaimsSet.Builder().issuer(provider == AuthProvider.GOOGLE ? "https://accounts.google.com" : "https://appleid.apple.com")
                .audience("client-id").subject("trusted-subject").issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    }
    private String signed(JWTClaimsSet.Builder claims, KeyPair key) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }
    @ParameterizedTest @EnumSource(AuthProvider.class)
    void acceptsValidSignatureAndVerifiedEmail(AuthProvider provider, CapturedOutput output) throws Exception {
        String token = signed(claims(provider).claim("email", "user@example.com")
                .claim("email_verified", provider == AuthProvider.APPLE ? "true" : true), keys);
        var identity = verifier(provider).verify(provider, token);
        assertThat(identity.subject()).isEqualTo("trusted-subject");
        assertThat(identity.email()).isEqualTo("user@example.com");
        assertThat(output.getOut()).contains("succeeded=true").doesNotContain(token, "user@example.com", "trusted-subject");
    }
    @ParameterizedTest @EnumSource(AuthProvider.class)
    void rejectsWrongIssuer(AuthProvider provider) throws Exception {
        String token = signed(claims(provider).issuer("https://attacker.example"), keys);
        assertThatThrownBy(() -> verifier(provider).verify(provider, token)).isInstanceOf(ApiException.class);
    }
    @ParameterizedTest @EnumSource(AuthProvider.class)
    void rejectsWrongAudience(AuthProvider provider, CapturedOutput output) throws Exception {
        String token = signed(claims(provider).audience("wrong-client"), keys);
        assertThatThrownBy(() -> verifier(provider).verify(provider, token)).isInstanceOf(ApiException.class);
        assertThat(output.getOut()).contains("category=audience").doesNotContain(token);
    }
    @ParameterizedTest @EnumSource(AuthProvider.class)
    void rejectsExpiredToken(AuthProvider provider, CapturedOutput output) throws Exception {
        String token = signed(claims(provider).issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(Instant.now().minusSeconds(120))), keys);
        assertThatThrownBy(() -> verifier(provider).verify(provider, token)).isInstanceOf(ApiException.class);
        assertThat(output.getOut()).contains("category=expiration").doesNotContain(token);
    }
    @ParameterizedTest @EnumSource(AuthProvider.class)
    void rejectsInvalidSignature(AuthProvider provider, CapturedOutput output) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        String token = signed(claims(provider), generator.generateKeyPair());
        assertThatThrownBy(() -> verifier(provider).verify(provider, token)).isInstanceOf(ApiException.class);
        assertThat(output.getOut()).contains("category=signature_or_signing_key").doesNotContain(token, "signatureVerified=true");
    }
    @ParameterizedTest @EnumSource(AuthProvider.class)
    void rejectsMissingExpiryAndSubject(AuthProvider provider) throws Exception {
        for (var claims : List.of(claims(provider).expirationTime(null), claims(provider).subject(null))) {
            String token = signed(claims, keys);
            assertThatThrownBy(() -> verifier(provider).verify(provider, token)).isInstanceOf(ApiException.class);
        }
    }
    @Test void appleAllowsMissingEmailAndName() throws Exception {
        var identity = verifier(AuthProvider.APPLE).verify(AuthProvider.APPLE, signed(claims(AuthProvider.APPLE), keys));
        assertThat(identity.email()).isNull(); assertThat(identity.displayName()).isNull();
    }
    @Test void ignoresUnverifiedEmail() throws Exception {
        var identity = verifier(AuthProvider.GOOGLE).verify(AuthProvider.GOOGLE,
                signed(claims(AuthProvider.GOOGLE).claim("email", "unverified@example.com"), keys));
        assertThat(identity.email()).isNull();
    }
    @Test void acceptsGoogleLegacyIssuer() throws Exception {
        assertThat(verifier(AuthProvider.GOOGLE).verify(AuthProvider.GOOGLE,
                signed(claims(AuthProvider.GOOGLE).issuer("accounts.google.com"), keys)).subject()).isEqualTo("trusted-subject");
    }
    @Test void rejectsUnapprovedAuthorizedParty() throws Exception {
        String token = signed(claims(AuthProvider.GOOGLE).claim("azp", "attacker-client"), keys);
        assertThatThrownBy(() -> verifier(AuthProvider.GOOGLE).verify(AuthProvider.GOOGLE, token)).isInstanceOf(ApiException.class);
    }
    @Test void rejectsFutureIssueTime() throws Exception {
        String token = signed(claims(AuthProvider.GOOGLE).issueTime(Date.from(Instant.now().plusSeconds(600))), keys);
        assertThatThrownBy(() -> verifier(AuthProvider.GOOGLE).verify(AuthProvider.GOOGLE, token)).isInstanceOf(ApiException.class);
    }
    @ParameterizedTest @EnumSource(AuthProvider.class)
    void rejectsMissingAudience(AuthProvider provider) throws Exception {
        String token = signed(claims(provider).audience((String) null), keys);
        assertThatThrownBy(() -> verifier(provider).verify(provider, token)).isInstanceOf(ApiException.class);
    }
    @Test void missingConfigurationIsDiagnosedBeforeFetchingKeys(CapturedOutput output) {
        var verifier = new OidcProviderTokenVerifier(new OAuthProperties(List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> verifier.verify(AuthProvider.GOOGLE, "sensitive-test-token"))
                .isInstanceOf(ApiException.class);
        assertThat(output.getOut()).contains("category=provider_configuration").doesNotContain("sensitive-test-token");
    }
    @Test void decoderExceptionMessagesAreNeverLogged(CapturedOutput output) {
        var verifier = new OidcProviderTokenVerifier(Map.of(AuthProvider.GOOGLE, token -> {
            throw new org.springframework.security.oauth2.jwt.BadJwtException("secret-token-in-exception");
        }));
        assertThatThrownBy(() -> verifier.verify(AuthProvider.GOOGLE, "secret-token-in-request")).isInstanceOf(ApiException.class);
        assertThat(output.getOut()).contains("exceptionType=BadJwtException")
                .doesNotContain("secret-token-in-exception", "secret-token-in-request");
    }
    private static final String ANDROID_ID = "123456789-trustedandroid.apps.googleusercontent.com";

    @Test void acceptsConfiguredQlinkWebAudienceAndAndroidParty() throws Exception {
        String webId = "66607447341-57pg2483cp73jo2geh525sa1ucm1b7os.apps.googleusercontent.com";
        String androidId = "66607447341-276mjnoog2vbe22l09r7gril5ih5ioh3.apps.googleusercontent.com";
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(OidcProviderTokenVerifier.validator(AuthProvider.GOOGLE, List.of(webId), List.of(androidId)));
        var verifier = new OidcProviderTokenVerifier(Map.of(AuthProvider.GOOGLE, decoder));
        String valid = signed(claims(AuthProvider.GOOGLE).audience(webId).claim("azp", androidId), keys);
        assertThat(verifier.verify(AuthProvider.GOOGLE, valid).subject()).isEqualTo("trusted-subject");
        String wrongAudience = signed(claims(AuthProvider.GOOGLE).audience(androidId).claim("azp", androidId), keys);
        assertThatThrownBy(() -> verifier.verify(AuthProvider.GOOGLE, wrongAudience)).isInstanceOf(ApiException.class);
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        String wrongSignature = signed(claims(AuthProvider.GOOGLE).audience(webId).claim("azp", androidId), generator.generateKeyPair());
        assertThatThrownBy(() -> verifier.verify(AuthProvider.GOOGLE, wrongSignature)).isInstanceOf(ApiException.class);
    }

    @Test void acceptsTrustedAndroidPartyWithWebAudience(CapturedOutput output) throws Exception {
        String token = signed(claims(AuthProvider.GOOGLE).claim("azp", ANDROID_ID), keys);
        assertThat(verifier(AuthProvider.GOOGLE, List.of(ANDROID_ID)).verify(AuthProvider.GOOGLE, token).subject())
                .isEqualTo("trusted-subject");
        assertThat(output.getOut()).contains("azp=" + ANDROID_ID, "authorizedPartyValid=true").doesNotContain(token);
    }

    @Test void androidAllowlistDoesNotExpandAcceptedAudiences() throws Exception {
        String token = signed(claims(AuthProvider.GOOGLE).audience(ANDROID_ID).claim("azp", ANDROID_ID), keys);
        assertThatThrownBy(() -> verifier(AuthProvider.GOOGLE, List.of(ANDROID_ID)).verify(AuthProvider.GOOGLE, token))
                .isInstanceOf(ApiException.class);
    }

    @Test void rejectsUnknownPartyEvenWithAndroidAllowlist() throws Exception {
        String token = signed(claims(AuthProvider.GOOGLE).claim("azp", "123456789-untrusted.apps.googleusercontent.com"), keys);
        assertThatThrownBy(() -> verifier(AuthProvider.GOOGLE, List.of(ANDROID_ID)).verify(AuthProvider.GOOGLE, token))
                .isInstanceOf(ApiException.class);
    }

    @Test void multipleAudiencesRequireAuthorizedParty() throws Exception {
        String token = signed(claims(AuthProvider.GOOGLE).audience(List.of("client-id", "other-client")), keys);
        assertThatThrownBy(() -> verifier(AuthProvider.GOOGLE, List.of(ANDROID_ID)).verify(AuthProvider.GOOGLE, token))
                .isInstanceOf(ApiException.class);
    }

    @Test void trustedAndroidPartyDoesNotBypassExpiry() throws Exception {
        String token = signed(claims(AuthProvider.GOOGLE).claim("azp", ANDROID_ID)
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(Instant.now().minusSeconds(120))), keys);
        assertThatThrownBy(() -> verifier(AuthProvider.GOOGLE, List.of(ANDROID_ID)).verify(AuthProvider.GOOGLE, token))
                .isInstanceOf(ApiException.class);
    }

    @Test void clientDiagnosticsRedactNonClientValues(CapturedOutput output) throws Exception {
        String token = signed(claims(AuthProvider.GOOGLE).claim("azp", "private-person@example.com\nsecret-token"), keys);
        assertThatThrownBy(() -> verifier(AuthProvider.GOOGLE).verify(AuthProvider.GOOGLE, token)).isInstanceOf(ApiException.class);
        assertThat(output.getOut()).contains("azp=redacted_non_client_id")
                .doesNotContain("private-person@example.com", "secret-token", token);
    }
}
