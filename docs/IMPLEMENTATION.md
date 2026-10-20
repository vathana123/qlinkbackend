# Phase 1 implementation inventory

## Preserved and modified

- build.gradle: retained Spring Boot 4.1.1 and Java 17; added web, security, JOSE, validation, Flyway, SpringDoc, MapStruct, and test dependencies.
- .gitignore: excludes environment secrets and PEM keys.
- Existing Gradle wrapper, settings, and IDE files were preserved.

## Replaced starter files

- src/main/java/com/qlink/qlinkbackend/QlinkbackendApplication.java moved to src/main/java/com/qlink/QlinkApplication.java for the requested root package and component scanning.
- src/main/resources/application.yaml replaced by application.yml with database, JWT, provider, and CORS settings.
- The original unconfigured context-load test was replaced by isolated MySQL-backed integration tests.

## Created files

- .env.example
- README.md
- docs/IMPLEMENTATION.md
- src/main/java/com/qlink/common/entity/BaseEntity.java
- src/main/java/com/qlink/common/exception/ApiException.java
- src/main/java/com/qlink/common/exception/GlobalExceptionHandler.java
- src/main/java/com/qlink/common/response/ApiError.java
- src/main/java/com/qlink/common/response/ApiResponse.java
- src/main/java/com/qlink/config/JpaAuditingConfig.java
- src/main/java/com/qlink/config/OpenApiConfig.java
- src/main/java/com/qlink/config/SecurityConfig.java
- src/main/java/com/qlink/features/auth/controller/AuthController.java
- src/main/java/com/qlink/features/auth/dto/AuthResponse.java
- src/main/java/com/qlink/features/auth/dto/NonceRequest.java
- src/main/java/com/qlink/features/auth/dto/NonceResponse.java
- src/main/java/com/qlink/features/auth/dto/ProviderAuthRequest.java
- src/main/java/com/qlink/features/auth/dto/RefreshRequest.java
- src/main/java/com/qlink/features/auth/entity/AuthSession.java
- src/main/java/com/qlink/features/auth/entity/NonceChallenge.java
- src/main/java/com/qlink/features/auth/entity/OAuthAccount.java
- src/main/java/com/qlink/features/auth/entity/RefreshToken.java
- src/main/java/com/qlink/features/auth/enums/AuthProvider.java
- src/main/java/com/qlink/features/auth/repository/AuthSessionRepository.java
- src/main/java/com/qlink/features/auth/repository/NonceChallengeRepository.java
- src/main/java/com/qlink/features/auth/repository/OAuthAccountRepository.java
- src/main/java/com/qlink/features/auth/repository/RefreshTokenRepository.java
- src/main/java/com/qlink/features/auth/service/AuthService.java
- src/main/java/com/qlink/features/auth/service/NonceService.java
- src/main/java/com/qlink/features/user/dto/UserResponse.java
- src/main/java/com/qlink/features/user/entity/User.java
- src/main/java/com/qlink/features/user/enums/UserStatus.java
- src/main/java/com/qlink/features/user/mapper/UserMapper.java
- src/main/java/com/qlink/features/user/repository/UserRepository.java
- src/main/java/com/qlink/features/user/service/UserService.java
- src/main/java/com/qlink/QlinkApplication.java
- src/main/java/com/qlink/security/jwt/JwtConfig.java
- src/main/java/com/qlink/security/jwt/JwtProperties.java
- src/main/java/com/qlink/security/jwt/TokenHash.java
- src/main/java/com/qlink/security/jwt/TokenService.java
- src/main/java/com/qlink/security/oauth/OAuthProperties.java
- src/main/java/com/qlink/security/oauth/OidcProviderTokenVerifier.java
- src/main/java/com/qlink/security/oauth/ProviderIdentity.java
- src/main/java/com/qlink/security/oauth/ProviderTokenVerifier.java
- src/main/resources/application.yml
- src/main/resources/db/migration/V1__authentication_schema.sql
- src/test/java/com/qlink/AuthIntegrationTest.java
- src/test/java/com/qlink/security/oauth/OidcProviderTokenVerifierTest.java

## Verification — 2026-10-17

- Command: .\gradlew.bat clean test bootJar using Java 17.
- Result: BUILD SUCCESSFUL; 30 tests, zero failures/errors/skips.
- Provider verification tests use generated RSA keys and never call live providers.
- Integration tests ran against an isolated mysql:8.4 Testcontainer with real Flyway migration and Hibernate schema validation.
- Verified registration, conflicts, signin, missing identities, Apple nonce binding/expiry/reuse, access JWT protection, refresh rotation/reuse/revocation/concurrency/expiry, signout, auditing, soft deletion, active identity uniqueness, and CORS.
- Verified HTTP 200 for /swagger-ui/index.html and /v3/api-docs through Spring MockMvc, including API title and bearer scheme. A manual browser session was not performed.
- Executable artifact: build/libs/qlinkbackend-0.0.1-SNAPSHOT.jar.
- Non-failing tooling notices: Mockito JVM class-sharing warning and a deprecated test API notice.
- No real Google or Apple account authentication was attempted. Real client IDs, valid provider-issued ID tokens, application RSA keys, and runtime database credentials remain deployment configuration.

See README.md for architecture, migration details, provider setup, environment variables, and API examples.
