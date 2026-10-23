package com.qlink;

import com.qlink.features.auth.enums.AuthProvider;
import com.qlink.features.user.service.UserService;
import com.qlink.security.oauth.*;
import com.qlink.security.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4").withDatabaseName("qlink_test");
    static final Path keyDir = generateKeys();
    @org.junit.jupiter.api.io.TempDir static Path storageDir;
    static Path generateKeys() {
        try {
            Path dir = Files.createTempDirectory("qlink-test-keys-");
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            var pair = generator.generateKeyPair();
            writePem(dir.resolve("private.pem"), "PRIVATE KEY", pair.getPrivate().getEncoded());
            writePem(dir.resolve("public.pem"), "PUBLIC KEY", pair.getPublic().getEncoded());
            return dir;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    static void writePem(Path path, String label, byte[] bytes) throws Exception {
        Files.writeString(path, "-----BEGIN " + label + "-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes) + "\n-----END " + label + "-----\n");
    }
    @AfterAll static void removeKeys() throws Exception {
        Files.deleteIfExists(keyDir.resolve("private.pem")); Files.deleteIfExists(keyDir.resolve("public.pem")); Files.deleteIfExists(keyDir);
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.root-path", () -> storageDir.toString());
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("qlink.jwt.private-key", () -> keyDir.resolve("private.pem").toUri().toString());
        registry.add("qlink.jwt.public-key", () -> keyDir.resolve("public.pem").toUri().toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired JwtEncoder encoder;
    @MockitoBean ProviderTokenVerifier verifier;

    String body(Object value) { return json.writeValueAsString(value); }
    JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).get("data"); }
    MvcResult postJson(String path, Object payload, int status) throws Exception {
        return mvc.perform(post("/api/v1/auth/" + path).contentType("application/json").content(body(payload)))
                .andExpect(status().is(status)).andReturn();
    }
    Map<String, String> provider(String token) { return Map.of("provider", "GOOGLE", "idToken", token); }
    JsonNode signup(String subject) throws Exception {
        when(verifier.verify(AuthProvider.GOOGLE, subject)).thenReturn(new ProviderIdentity(subject, "shared@example.com", "User", null, null));
        return data(postJson("signup", provider(subject), 201));
    }
    @Test void profileValidationUniquenessAndIdentityProtection() throws Exception {
        var first=signup(UUID.randomUUID().toString());
        var second=signup(UUID.randomUUID().toString());
        String token="Bearer "+first.get("accessToken").asText();
        String name="p"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        mvc.perform(patch("/api/v1/users/me").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/users/me").header("Authorization",token).contentType("application/json")
            .content(body(Map.of("displayName","  My Name  ","username",name.toUpperCase()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("My Name"))
            .andExpect(jsonPath("$.data.username").value(name));
        mvc.perform(patch("/api/v1/users/me").header("Authorization","Bearer "+second.get("accessToken").asText())
            .contentType("application/json").content(body(Map.of("username",name.toUpperCase()))))
            .andExpect(status().isConflict());
        for(var payload:List.of(Map.of("displayName","   "),Map.of("username","bad name"),Map.of("username","admin"))) {
            mvc.perform(patch("/api/v1/users/me").header("Authorization",token).contentType("application/json").content(body(payload)))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/api/v1/users/me").header("Authorization",token).contentType("application/json")
            .content(body(Map.of("displayName","Changed"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(name))
            .andExpect(jsonPath("$.data.email").value("shared@example.com"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+second.get("accessToken").asText()))
            .andExpect(jsonPath("$.data.displayName").value("User"));
    }
    @Test void profileAvatarReplacementReadAuthorizationAndRemoval() throws Exception {
        String subject=UUID.randomUUID().toString();
        var account=signup(subject);
        String token="Bearer "+account.get("accessToken").asText();
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV1sAAAAASUVORK5CYII=");
        var file=new org.springframework.mock.web.MockMultipartFile("file","avatar.png","image/png",png);
        mvc.perform(multipart("/api/v1/users/me/avatar").file(file).with(r -> {r.setMethod("PUT");return r;}))
            .andExpect(status().isUnauthorized());
        var saved=data(mvc.perform(multipart("/api/v1/users/me/avatar").file(file).header("Authorization",token)
            .with(r -> {r.setMethod("PUT");return r;})).andExpect(status().isOk()).andReturn());
        String url=saved.get("avatarUrl").asText();
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        var other=signup(UUID.randomUUID().toString());
        mvc.perform(get(url).header("Authorization","Bearer "+other.get("accessToken").asText())).andExpect(status().isOk());
        mvc.perform(delete(url.replace("/download", "")).header("Authorization",token)).andExpect(status().isBadRequest());
        var invalid=new org.springframework.mock.web.MockMultipartFile("file","bad.txt","text/plain","not an image".getBytes());
        mvc.perform(multipart("/api/v1/users/me/avatar").file(invalid).header("Authorization",token)
            .with(r -> {r.setMethod("PUT");return r;})).andExpect(status().isBadRequest());
        var replaced=data(mvc.perform(multipart("/api/v1/users/me/avatar").file(file).header("Authorization",token)
            .with(r -> {r.setMethod("PUT");return r;})).andExpect(status().isOk()).andReturn());
        mvc.perform(get(url).header("Authorization",token)).andExpect(status().isNotFound());
        var signin=data(postJson("signin",provider(subject),200));
        assertThat(signin.get("user").get("avatarUrl").asText()).isEqualTo(replaced.get("avatarUrl").asText());
        mvc.perform(delete("/api/v1/users/me/avatar").header("Authorization",token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.avatarUrl").doesNotExist());
        mvc.perform(get(replaced.get("avatarUrl").asText()).header("Authorization",token)).andExpect(status().isNotFound());
    }
    @Test void registrationSigninRotationReuseAndSignout() throws Exception {
        String subject = UUID.randomUUID().toString();
        JsonNode registered = signup(subject);
        String userId = registered.get("user").get("id").asText();
        String original = registered.get("refreshToken").asText();
        assertThat(registered.get("expiresIn").asLong()).isEqualTo(900);
        postJson("signup", provider(subject), 409);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + registered.get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(userId));

        JsonNode rotated = data(postJson("refresh", Map.of("refreshToken", original), 200));
        assertThat(rotated.get("refreshToken").asText()).isNotEqualTo(original);
        assertThat(jdbc.queryForObject("select token_hash from refresh_tokens where token_hash = ?", String.class, TokenHash.sha256(original)))
                .isEqualTo(TokenHash.sha256(original));
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, original)).isZero();
        postJson("refresh", Map.of("refreshToken", original), 401);
        postJson("refresh", Map.of("refreshToken", rotated.get("refreshToken").asText()), 401);

        JsonNode signin = data(postJson("signin", provider(subject), 200));
        mvc.perform(post("/api/v1/auth/signout").header("Authorization", "Bearer " + signin.get("accessToken").asText()))
                .andExpect(status().isOk());
        postJson("refresh", Map.of("refreshToken", signin.get("refreshToken").asText()), 401);
        // Access JWTs are intentionally stateless after signout.
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + signin.get("accessToken").asText()))
                .andExpect(status().isOk());
        UUID id = UUID.fromString(userId);
        byte[] actor = jdbc.queryForObject("select updated_by from users where id = UNHEX(REPLACE(?, '-', ''))", byte[].class, userId);
        assertThat(actor).hasSize(16);
        users.softDelete(id, id);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + signin.get("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from users where id = UNHEX(REPLACE(?, '-', '')) and deleted_at is not null and deleted_by is not null", Integer.class, userId)).isEqualTo(1);
    }
    @Test void sameEmailNeverMergesDifferentProviderSubjects() throws Exception {
        JsonNode first = signup(UUID.randomUUID().toString());
        JsonNode second = signup(UUID.randomUUID().toString());
        assertThat(first.get("user").get("id")).isNotEqualTo(second.get("user").get("id"));
    }
    @Test void unregisteredAndInvalidProviderTokens() throws Exception {
        String subject = UUID.randomUUID().toString();
        when(verifier.verify(AuthProvider.GOOGLE, subject)).thenReturn(new ProviderIdentity(subject, null, null, null, null));
        postJson("signin", provider(subject), 404);
        when(verifier.verify(AuthProvider.GOOGLE, "invalid")).thenThrow(
                com.qlink.common.exception.ApiException.unauthorized("INVALID_PROVIDER_TOKEN", "Provider token is invalid or expired"));
        postJson("signin", provider("invalid"), 401);
        postJson("signup", Map.of("provider", "FACEBOOK", "idToken", "x"), 400);
        postJson("signup", Map.of("provider", "GOOGLE", "idToken", ""), 400);
        postJson("signup", Map.of("provider", "GOOGLE"), 400);
        postJson("signin", Map.of("provider", "GOOGLE"), 400);
        postJson("refresh", Map.of("refreshToken", "invalid"), 401);
    }
    @Test void appleNonceIsRequiredBoundAndSingleUse() throws Exception {
        String subject = UUID.randomUUID().toString();
        when(verifier.verify(AuthProvider.APPLE, subject)).thenReturn(new ProviderIdentity(subject, null, null, null, null));
        postJson("signup", Map.of("provider", "APPLE", "idToken", subject), 401);
        JsonNode challenge = data(postJson("nonce", Map.of("provider", "APPLE"), 200));
        when(verifier.verify(AuthProvider.APPLE, subject)).thenReturn(new ProviderIdentity(subject, null, null, null, challenge.get("nonceHash").asText()));
        postJson("signup", Map.of("provider", "APPLE", "idToken", subject, "nonce", "wrong-nonce"), 401);
        var request = Map.of("provider", "APPLE", "idToken", subject, "nonce", challenge.get("nonce").asText());
        JsonNode registered = data(postJson("signup", request, 201));
        assertThat(registered.get("user").get("email").isNull()).isTrue();
        postJson("signin", request, 401);
    }
    @Test void concurrentRefreshSerializesAndRevokesOnReuse() throws Exception {
        JsonNode registered = signup(UUID.randomUUID().toString());
        var payload = Map.of("refreshToken", registered.get("refreshToken").asText());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcResult> call = () -> { start.await(); return mvc.perform(post("/api/v1/auth/refresh")
                .contentType("application/json").content(body(payload))).andReturn(); };
        try {
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            MvcResult a = first.get(20, TimeUnit.SECONDS), b = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(a.getResponse().getStatus(), b.getResponse().getStatus())).containsExactlyInAnyOrder(200, 401);
            JsonNode rotated = data(a.getResponse().getStatus() == 200 ? a : b);
            postJson("refresh", Map.of("refreshToken", rotated.get("refreshToken").asText()), 401);
        } finally { pool.shutdownNow(); }
    }
    @Test void protectedEndpointsRejectMissingAndWrongTokenTypes() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/signout")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/unknown")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        JsonNode registered = signup(UUID.randomUUID().toString());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + registered.get("refreshToken").asText()))
                .andExpect(status().isUnauthorized());
        postJson("refresh", Map.of("refreshToken", registered.get("accessToken").asText()), 401);
        for (String issuer : List.of("qlink-api", "wrong-issuer")) {
            var claims = JwtClaimsSet.builder().issuer(issuer).subject(registered.get("user").get("id").asText())
                    .issuedAt(Instant.now().minusSeconds(600))
                    .expiresAt(Instant.now().plusSeconds(issuer.equals("qlink-api") ? -120 : 300))
                    .claim("token_use", "access").claim("sid", UUID.randomUUID().toString()).build();
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
            mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
    }
    @Test void swaggerAndCorsAreAvailable() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("qLink API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
        mvc.perform(options("/api/v1/auth/signin").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(options("/api/v1/auth/signin").header("Origin", "https://unapproved.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }
    @Test void registrationAuditsAndDatabaseActiveIdentityConstraint() throws Exception {
        String subject = UUID.randomUUID().toString();
        JsonNode registered = signup(subject);
        String id = registered.get("user").get("id").asText();
        assertThat(jdbc.queryForObject("select count(*) from users where id=UNHEX(REPLACE(?, '-', '')) and created_at is not null and updated_at is not null and created_by is null", Integer.class, id)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into user_oauth_accounts (id,user_id,provider,provider_subject,created_at,updated_at) values (UUID_TO_BIN(UUID()),UNHEX(REPLACE(?, '-', '')),'GOOGLE',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", id, subject))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("update user_oauth_accounts set deleted_at=UTC_TIMESTAMP(6) where provider_subject=?", subject);
        postJson("signin", provider(subject), 404);
        postJson("signup", provider(subject), 201);
    }
    @Test void expiredRefreshAndDeletedUserCannotRefresh() throws Exception {
        JsonNode registered = signup(UUID.randomUUID().toString());
        String refresh = registered.get("refreshToken").asText();
        jdbc.update("update refresh_tokens set expires_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY) where token_hash=?", TokenHash.sha256(refresh));
        postJson("refresh", Map.of("refreshToken", refresh), 401);
        JsonNode deleted = signup(UUID.randomUUID().toString());
        UUID id = UUID.fromString(deleted.get("user").get("id").asText());
        users.softDelete(id, id);
        postJson("refresh", Map.of("refreshToken", deleted.get("refreshToken").asText()), 401);
    }
    @Test void expiredNonceCannotBeUsed() throws Exception {
        String subject = UUID.randomUUID().toString();
        JsonNode challenge = data(postJson("nonce", Map.of("provider", "APPLE"), 200));
        jdbc.update("update nonce_challenges set expires_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY) where nonce_hash=?", challenge.get("nonceHash").asText());
        when(verifier.verify(AuthProvider.APPLE, subject)).thenReturn(new ProviderIdentity(subject, null, null, null, challenge.get("nonceHash").asText()));
        postJson("signup", Map.of("provider", "APPLE", "idToken", subject, "nonce", challenge.get("nonce").asText()), 401);
    }
    @Test void authenticatedRequestsAutomaticallyPopulateAuditActors() throws Exception {
        JsonNode registered = signup(UUID.randomUUID().toString());
        JsonNode challenge = data(mvc.perform(post("/api/v1/auth/nonce")
                .header("Authorization", "Bearer " + registered.get("accessToken").asText())
                .contentType("application/json").content(body(Map.of("provider", "GOOGLE"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(jdbc.queryForObject("select count(*) from nonce_challenges where nonce_hash=? and created_by=UNHEX(REPLACE(?, '-', '')) and updated_by=created_by", Integer.class,
                challenge.get("nonceHash").asText(), registered.get("user").get("id").asText())).isEqualTo(1);
    }
}
