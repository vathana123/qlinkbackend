# qLink Backend — Phase 1

Existing Spring Boot **4.1.1**, Java **17**, Gradle wrapper **9.7.1**. MySQL 8, Flyway, Spring Security Resource Server / JOSE, MapStruct 1.6.3, SpringDoc 3.1.1. Phase 1 includes provider authentication only.

## Project structure

~~~text
src/main/java/com/qlink/
  QlinkApplication.java
  config/                  SecurityConfig, OpenApiConfig, JpaAuditingConfig
  common/
    entity/                BaseEntity
    exception/             ApiException, GlobalExceptionHandler
    response/              ApiResponse, ApiError
  features/
    auth/
      controller/          AuthController
      dto/                 ProviderAuthRequest, RefreshRequest, AuthResponse,
                           NonceRequest, NonceResponse
      entity/              OAuthAccount, AuthSession, RefreshToken, NonceChallenge
      enums/               AuthProvider
      repository/          OAuthAccountRepository, AuthSessionRepository,
                           RefreshTokenRepository, NonceChallengeRepository
      service/             AuthService, NonceService
    user/
      dto/                 UserResponse
      entity/              User
      enums/               UserStatus
      mapper/              UserMapper
      repository/          UserRepository
      service/             UserService
  security/
    jwt/                   JwtConfig, JwtProperties, TokenHash, TokenService
    oauth/                 OAuthProperties, ProviderIdentity,
                           ProviderTokenVerifier, OidcProviderTokenVerifier
src/main/resources/
  application.yml
  db/migration/V1__authentication_schema.sql
src/test/java/com/qlink/
  AuthIntegrationTest.java
  security/oauth/OidcProviderTokenVerifierTest.java
~~~

Empty architecture folders are omitted. Authentication uses explicit construction for new entities; user profile conversion uses the generated MapStruct mapper. No entities are returned by controllers.

## Local setup

Prerequisites: Java 17, Docker for integration tests, MySQL 8 for running the application, and OpenSSL for generating your application signing keys.

1. Create an empty database:

~~~sql
CREATE DATABASE qlink_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
~~~

2. Generate an RSA key pair locally (never commit the private key). In PowerShell 7:

~~~powershell
./scripts/setup-jwt.ps1
~~~

The script refuses to overwrite existing keys. Alternatively, use OpenSSL:

~~~sh
mkdir secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out secrets/private.pem
openssl pkey -in secrets/private.pem -pubout -out secrets/public.pem
~~~

Use PKCS#8 private PEM and X.509 public PEM. Keys must match and be at least 2048 bits. The application defaults to secrets/private.pem and secrets/public.pem relative to the working directory. Run from the project root; in IntelliJ, set the run configuration's working directory to the project root. The application fails startup if keys are missing or invalid. Production should mount keys from a secret manager with restricted filesystem permissions and set JWT_PRIVATE_KEY/JWT_PUBLIC_KEY to those locations. This phase uses one active key pair; replacing it invalidates existing access and refresh JWTs.

3. Copy [.env.example](.env.example) to `.env` for local development, or export environment variables / configure them in your IDE. This project explicitly loads `.env` through `spring.config.import: optional:file:./.env[.properties]`. Run from the project root. The file uses Java properties syntax: `KEY=value`, without shell quotes or `export`; prefer forward slashes in file paths. OS environment variables override matching values in `.env`. The import is optional, so deployments do not require or need to ship this ignored local file. PowerShell environment override example:

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:DB_HOST = 'localhost'
$env:DB_PORT = '3306'
$env:DB_NAME = 'qlink_db'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = 'your-local-password'
$env:JWT_PRIVATE_KEY = 'file:./secrets/private.pem'
$env:JWT_PUBLIC_KEY = 'file:./secrets/public.pem'
$env:GOOGLE_CLIENT_ID = '66607447341-57pg2483cp73jo2geh525sa1ucm1b7os.apps.googleusercontent.com'
$env:APPLE_CLIENT_IDS = 'com.example.qlink'
$env:CORS_ALLOWED_ORIGINS = 'http://localhost:3000,http://localhost:5173'
.\gradlew.bat bootRun
~~~

Unix/macOS: export the same variables and run ./gradlew bootRun. Use a dedicated database user in deployed environments.

| Variable | Default / purpose |
|---|---|
| DB_HOST / DB_PORT | localhost / 3306 |
| DB_NAME | qlink_db |
| DB_USERNAME / DB_PASSWORD | root / empty; set real credentials externally |
| JWT_PRIVATE_KEY / JWT_PUBLIC_KEY | Defaults: file:./secrets/private.pem and file:./secrets/public.pem; override for deployed environments |
| JWT_ISSUER | qlink-api |
| GOOGLE_CLIENT_ID | Expected Google Web Client ID; takes precedence over the legacy GOOGLE_CLIENT_IDS variable |
| GOOGLE_CLIENT_IDS | Legacy fallback, comma-separated allowlist; used only when GOOGLE_CLIENT_ID is unset |
| GOOGLE_ANDROID_CLIENT_IDS | Comma-separated trusted Android OAuth client IDs for azp only; empty by default |
| APPLE_CLIENT_IDS | Comma-separated Apple bundle IDs and/or Services IDs; empty disables successful Apple verification |
| CORS_ALLOWED_ORIGINS | http://localhost:3000,http://localhost:5173; replace with approved web origins |

Access tokens live 15 minutes. Sessions and refresh tokens expire 30 days after initial login; rotation does not extend that deadline. No source-controlled credentials or signing keys are supplied.

## Google configuration

In Google Cloud Console, select project **qlink-511909** and configure the OAuth consent screen, audience, and test users where applicable. Verify that Web Client ID **66607447341-57pg2483cp73jo2geh525sa1ucm1b7os.apps.googleusercontent.com** belongs to this project. Create OAuth clients appropriate to the Flutter platforms (Android package/signing fingerprints and iOS bundle configuration). Configure the Flutter SDK's server client ID to request a token whose audience matches GOOGLE_CLIENT_ID.

For local PowerShell startup, run `./scripts/run-dev.ps1`; it launches the backend from the project root so the optional `.env` import resolves correctly. Alternatively run QlinkApplication in IntelliJ with the project root as its working directory. `.env.example` itself is never loaded: copy it to `.env`. Use GOOGLE_CLIENT_ID for deployment overrides; the legacy GOOGLE_CLIENT_IDS fallback is used only when GOOGLE_CLIENT_ID is absent from all sources. The verifier's existing property is `qlink.oauth.google-client-ids`; there is no separate `app.oauth.google.client-id` property.

Manually verify the Android OAuth client is in the same Cloud project, uses the actual applicationId (`com.example.qlinkfrontend` in the inspected Flutter project), and has the SHA-1 of the certificate used to sign the installed build. Obtain fingerprints with `./gradlew signingReport` in the Flutter project's android directory; include the appropriate debug, release, or Play signing certificate. Cloud Console settings and real provider sign-in have not been verified here.

The backend verifies RS256 signatures using Google's fixed HTTPS JWKS endpoint, accepts the two documented Google issuer forms, and checks audience, authorized party when present, issued-at, expiry, not-before when present, and subject. Email is stored only when the signed email_verified claim is true.

The audience must match GOOGLE_CLIENT_ID (or the legacy Web audience allowlist). A present azp must match either an accepted Web client ID or an explicitly configured GOOGLE_ANDROID_CLIENT_IDS entry. Android IDs never become accepted audiences through this setting. An absent azp is allowed only for a single audience; multiple audiences require a trusted azp. This supports Google's documented [Android/Web hybrid client flow](https://developers.google.com/identity/openid-connect/openid-connect) without requiring azp to equal aud.

The configured Android OAuth client ID is **66607447341-276mjnoog2vbe22l09r7gril5ih5ioh3.apps.googleusercontent.com**, supplied by the project owner for com.example.qlinkfrontend in qlink-511909. It is set in GOOGLE_ANDROID_CLIENT_IDS in .env and .env.example. Restart and compare the signature-verified azp diagnostic with this ID. Cloud Console registration and the real token's claims have not been independently inspected. Do not put the package name in this variable or add an unfamiliar ID just because it appeared in a token. Multiple verified debug/release client IDs can be comma-separated.

Google tokens without a nonce are accepted for SDK flows that do not supply one. If a flow supplies a nonce, obtain one from the backend first, send the raw nonce to Google, and include the raw nonce in signup/signin. A signed nonce without a matching backend challenge is rejected.

Provider reference: [Verify Google ID tokens](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token) and [OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect).

## Apple configuration

Enable Sign in with Apple for your App ID in the Apple Developer account and enable the app capability. Native iOS tokens normally target the bundle ID. For web/Android flows, configure a Services ID with its domain and return URL, associated with the primary App ID. Add the exact expected audience(s) to APPLE_CLIENT_IDS.

Before invoking the Apple SDK:

1. POST /api/v1/auth/nonce with provider APPLE.
2. Pass the returned **nonceHash** (SHA-256 hexadecimal digest) as the Apple request nonce.
3. Send the returned raw **nonce** alongside Apple's ID token to signup/signin.

If your Flutter library hashes a raw nonce itself, give it the raw nonce instead: the claim Apple signs must equal nonceHash. Do not double-hash it. Challenges expire after five minutes and are consumed once, transactionally with successful authentication. Request a fresh challenge for every attempt.

The backend verifies Apple's RS256 signature, issuer https://appleid.apple.com, configured audience, timestamps, subject, and the nonce. Missing email and display name are supported. Apple names are not included in ID tokens; this phase leaves them null rather than trusting an extra client identity field.

This ID-token verification flow does not exchange Apple authorization codes and does not require an Apple .p8 key or client secret in the backend. Provider credential exchange/revocation is outside this phase. Application signout does not revoke Apple or Google credentials.

Provider reference: [Authenticating users with Sign in with Apple](https://developer.apple.com/documentation/signinwithapple/authenticating-users-with-sign-in-with-apple).

## API examples

### Diagnosing Google provider rejection

In IntelliJ, select the shared **qLink Google OAuth** run configuration, which supplies GOOGLE_CLIENT_ID and the project working directory. Alternatively use `./scripts/run-dev.ps1`. Restart the backend after changing environment variables. The current terminal's variables do not establish what an already-running IDE process received.

Startup logs report `OAuth configuration provider=GOOGLE audienceConfigured=true` (or false). Request logs identify signup/signin, the selected provider, and whether an ID token was received. Verification logs report success or controlled failure categories: provider_configuration, issuer, audience, authorized_party, expiration, subject, issued_at, not_before, signature_or_signing_key, provider_keys_unavailable, or malformed_token_or_claims. Missing request fields return 400 before verification. Detailed categories remain in backend logs; Flutter still receives the existing safe INVALID_PROVIDER_TOKEN error.

Diagnostics never print token strings, personal claims, decoder exception messages, or stack traces. After signature verification, Google client diagnostics show only aud/azp values matching the OAuth client-ID format and boolean issuer/audience/authorized-party/expiration results; other values are redacted. These claims are signature-verified, but may still fail policy validation. `signature_or_signing_key` includes invalid signatures, untrusted algorithms, and no matching signing key; `provider_keys_unavailable` indicates failure retrieving trusted provider keys. The backend still verifies Google's signature, issuer, audience, timestamps, and subject, with nonce validation in AuthService/NonceService after token verification.

The reported signup logs confirmed the request reached the backend with an ID token, but `provider_configuration` rejected it because the expected audience was empty. `.env.example` was not loaded and `.env` did not exist. The optional properties import fixes that configuration gap. Restart after creating/editing `.env`; startup should report `provider=GOOGLE audienceConfigured=true`. Real Google sign-in still needs a fresh valid provider token. Share only the controlled diagnostic lines, never the token or request body.

All JSON responses have success, message, and data. Provider identity is always the pair (provider, signed subject), never an email lookup. Signup returns 201 and rejects an existing identity with 409. Signin returns 404 for an unregistered identity.

~~~http
POST /api/v1/auth/signup
Content-Type: application/json

{"provider":"GOOGLE","idToken":"<real-google-id-token>"}
~~~

~~~http
POST /api/v1/auth/signin
Content-Type: application/json

{"provider":"GOOGLE","idToken":"<real-google-id-token>"}
~~~

~~~http
POST /api/v1/auth/nonce
Content-Type: application/json

{"provider":"APPLE"}
~~~

~~~http
POST /api/v1/auth/signup
Content-Type: application/json

{"provider":"APPLE","idToken":"<real-apple-id-token>","nonce":"<raw-backend-nonce>"}
~~~

Successful signup/signin/refresh:

~~~json
{
  "success": true,
  "message": "Sign in successful",
  "data": {
    "accessToken": "<signed-access-jwt>",
    "refreshToken": "<signed-refresh-jwt>",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "user": {
      "id": "4bf568db-6a87-4d84-8a12-3e21ada11646",
      "displayName": "Example User",
      "email": "user@example.com",
      "avatarUrl": null,
      "status": "ACTIVE"
    }
  }
}
~~~

~~~http
POST /api/v1/auth/refresh
Content-Type: application/json

{"refreshToken":"<current-application-refresh-jwt>"}
~~~

~~~http
GET /api/v1/auth/me
Authorization: Bearer <application-access-jwt>
~~~

~~~http
POST /api/v1/auth/signout
Authorization: Bearer <application-access-jwt>
~~~

Signout requires no body; it revokes the session identified by the access JWT's sid. It is idempotent while that access token is valid. Already-issued stateless access tokens remain valid until expiration; /me additionally rejects unavailable users. Google/Apple tokens are never accepted as application access tokens.

Example error:

~~~json
{"success":false,"message":"Provider identity is already registered","data":{"code":"ALREADY_REGISTERED","fields":{}}}
~~~

400: malformed JSON, unsupported provider, or bean validation failure.
401: invalid/expired provider or application token, invalid nonce, invalid/revoked/reused refresh token, unavailable user.
403: access denied.
404: identity not registered.
409: duplicate identity (including registration races).
500: generic internal error without exception details.

## Session and persistence behavior

V1 creates users, user_oauth_accounts, auth_sessions, refresh_tokens, and nonce_challenges. Every application table uses a BINARY(16) UUID primary key and the reusable audit fields. Flyway's own history table is framework metadata. Hibernate validates the schema and never creates or updates it. JDBC sessions and Hibernate timestamp conversion use UTC; DATETIME(6) values represent UTC.

The OAuth table has a generated active_identity column. A unique index on (provider, provider_subject, active_identity) enforces active identity uniqueness while allowing historical soft-deleted rows. Provider subjects use case-sensitive ASCII collation. User email is deliberately not unique. Foreign keys preserve relational integrity.

JPA auditing records timestamps and UUID actors from authenticated application JWTs. Unauthenticated registration has null actor fields. Once provider signin establishes identity, the service records that user as the update actor. Session and refresh operations also record the known user. There is no hard-delete method on the application repositories. UserService.softDelete records deleted_at/deleted_by; Hibernate excludes soft-deleted users and OAuth identities from normal queries. No user deletion API is exposed in Phase 1. Deleting a user prevents signin and refresh; its OAuth identity remains reserved unless separately soft-deleted through a future explicit account-lifecycle workflow.

Refresh JWTs have random jti claims, a distinct token_use, and a session UUID. Only SHA-256 hashes are stored. SHA-256 is appropriate here because signed refresh tokens carry high-entropy identifiers; there are no user passwords. Rotation locks the session row, revokes the previous token, and records its replacement in one transaction. Reusing a rotated token revokes the entire session, including the newest token. That revocation commits even when the request returns 401. Different sessions are independent. Clients must serialize refresh requests and securely store the new token; after a lost refresh response, sign in again rather than retrying the old token.

Expired challenges and expired session/token history may be purged by a future maintenance policy; no scheduled cleanup is included. Keep rotated hashes at least until their session expires so reuse detection remains possible. Apply HTTPS and ingress rate limits to public authentication and nonce endpoints in deployment.

## Swagger and tests

- [Swagger UI](http://localhost:8080/swagger-ui/index.html)
- [OpenAPI JSON](http://localhost:8080/v3/api-docs)

Both are public. Use Authorize with an application **access token** to test /me and /signout. Provider verification requires outbound HTTPS to Google/Apple JWKS endpoints.

~~~powershell
.\gradlew.bat clean test bootJar
~~~

Tests require a running Docker engine and pull mysql:8.4 if needed. Integration tests create an isolated MySQL database, run the real Flyway migration and Hibernate validation, generate ephemeral RSA keys, and mock external provider verification. They do not use your application database or provider credentials. Provider verifier tests use local RSA signatures to test Google/Apple issuer, audience, expiry, signature, and required claims without network access.

See [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md) for the file inventory and verification results. Real Google/Apple account authentication has not been verified; it requires your provider client configuration and valid SDK-issued tokens.
