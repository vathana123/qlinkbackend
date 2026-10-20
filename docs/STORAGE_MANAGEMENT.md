# Backend storage management

Implementation date: 2026-10-19. Scope: qlinkbackend only, before Phase 2.4. No Flutter changes, chat attachment integration, database reset, or changes to message sending.

## Architecture and entity

The storage feature separates metadata persistence from FileStorageProvider. LocalFileStorageProvider stores bytes outside the web root using random UUID-based keys. StoredFile extends the existing BaseEntity, retaining UUID generation, created/updated auditing, and soft-delete actor/timestamps.

StoredFile records originalName, storageKey, contentType, sizeBytes, checksumSha256, category, status, and uploadedBy. Public DTOs expose only id, sanitized originalName, detected contentType, sizeBytes, category, status, and createdAt. Paths, keys, owner details, and server directory names are not returned.

Flyway migration **V5__stored_files.sql** adds only stored_files. It has a unique key constraint, foreign key to users, owner/deletion/time and cleanup indexes, positive-size check, enum checks, and a status/deletion consistency check. Existing tables and migrations remain unchanged. PENDING is reserved for unavailable metadata; this synchronous upload path commits only AVAILABLE rows. DELETED rows remain for audit and cleanup retry.

## Configuration

| Environment | Configuration |
| --- | --- |
| Default / dev | application.yml: STORAGE_ROOT defaults to ./storage/dev |
| Test | application-test.yml requires TEST_STORAGE_ROOT, or a dynamically supplied app.storage.root-path |
| Production | application-prod.yml: STORAGE_ROOT defaults to /var/lib/qlink/storage |

A separate development profile file is unnecessary because the base configuration supplies the development default. Automated application tests override the root with JUnit temporary directories that are cleaned after use. Test configuration has no fallback to development storage.

STORAGE_MAX_FILE_SIZE defaults to 25MB (Spring DataSize: 26,214,400 bytes, or 25 MiB). StorageMultipartConfiguration derives the servlet per-file limit from that same property and allows an additional 64 KiB for multipart framing. The servlet spills uploads to its temporary directory immediately; the provider enforces the byte limit again while streaming. Ensure both temporary and storage volumes have sufficient disk capacity.

The provider creates missing directories safely, rejects symlink/resolved-path escapes, and performs a startup write/delete probe. Unwritable roots fail startup with a configuration error. New directories/files use owner-only POSIX permissions where supported. On Windows configure equivalent service-account ACLs. Local storage is ignored by Git.

Production environment example:

    SPRING_PROFILES_ACTIVE=prod
    STORAGE_ROOT=/var/lib/qlink/storage
    STORAGE_MAX_FILE_SIZE=25MB

Supply these through the deployment's environment or supported .env configuration. Provision a persistent volume mounted at STORAGE_ROOT, writable only by the service account. Back up metadata and bytes together. Do not place uploads in resources, static/public directories, or ephemeral container layers.

## APIs

All routes require the existing qLink access JWT. The existing bearerAuth OpenAPI scheme is reused.

| Method | Route | Result |
| --- | --- | --- |
| POST | /api/v1/storage/files | Multipart file field and optional category; 201 with ApiResponse<StoredFileResponse> |
| GET | /api/v1/storage/files/{fileId} | Owner-only metadata in the existing response wrapper |
| GET | /api/v1/storage/files/{fileId}/download | Owner-only streamed attachment |
| DELETE | /api/v1/storage/files/{fileId} | Soft deletion; repeating safely retries physical cleanup |

Example upload (shell):

    curl -H "Authorization: Bearer $ACCESS_TOKEN" -F "file=@photo.png" -F "category=IMAGE" https://api.example.com/api/v1/storage/files

Downloads set detected Content-Type, Content-Length, safe UTF-8 attachment Content-Disposition, X-Content-Type-Options: nosniff, and Cache-Control: no-store. Streams close on completion or failure. Metadata and upload responses also disable caching.

Errors follow ApiError: 400 for empty/invalid input, category mismatch or malformed UUID; 401 for missing/invalid/revoked authentication; 404 for nonexistent, unavailable, deleted, missing-byte, or other users' files; 413 for size limits; 415 for unsupported content/extensions; 503 for storage/upload persistence failures. Unexpected database errors retain the existing generic 500 handler. The same 404 for absent and foreign files limits identifier probing.

Swagger includes multipart fields/example, bearer authentication, DTOs, default size, allowlist, common errors, and binary download response documentation. Existing Swagger endpoints remain available.

## Authorization and validation

StorageAccessPolicy resolves the owner from the authenticated JWT, requires an active existing user and matching unrevoked/unexpired authentication session, and enforces owner-only metadata, download, and deletion. Upload rechecks the session before metadata publication. No administrative bypass or implicit chat membership permission was added. This policy is the extension point for later attachment authorization.

FileContentPolicy uses Apache Tika core 3.3.3 with no client MIME or filename passed to detection. The original name is sanitized for display and never determines a filesystem path. Its extension must also match the detected allowlist. Client category, when provided, must match the derived category. A valid PNG with a false client Content-Type is safely stored as image/png; executable bytes labeled image/png are rejected.

| Category | Allowed formats/extensions |
| --- | --- |
| IMAGE | JPEG (.jpg/.jpeg), PNG, GIF, WebP |
| AUDIO | MP3, WAV, FLAC, Ogg Vorbis/Opus (.ogg/.opus), M4A |
| VIDEO | MP4, QuickTime MOV, WebM |
| DOCUMENT | PDF, DOC/DOCX, XLS/XLSX, PPT/PPTX, plain text TXT/CSV |
| OTHER | JSON object or array (.json) |

MP4 brands, WebM DocType, and Ogg audio codec headers supplement generic container detection with bounded reads. JSON is validated token by token with limits on nesting (100), strings (65,536 characters), and numbers (1,000 characters), without building a document tree. Some otherwise valid but unusual container brands/header layouts or oversized JSON tokens are intentionally rejected.

Executables, scripts identified by detection, HTML, SVG, generic ZIP containers, other archives, generic binary data, unsupported extensions, and mismatched categories are not allowed. Content signatures/type checks are not a guarantee that a document or media payload is malware-free; no virus scanning, full media decoding, transcoding, or sanitization is claimed. Plain text can contain source code as inert text. All accepted files remain private attachments.

## Filesystem/database consistency

Upload:

1. Stream bytes into a private randomly named staging file, enforce the limit, and compute SHA-256 with an 8 KiB buffer.
2. Validate staged content through a bounded-buffer stream; failures remove staging.
3. Force file contents and move to a random final key with an atomic move where supported (same-directory non-overwriting move fallback).
4. Persist AVAILABLE metadata using a transaction whose commit completes before returning.
5. If persistence/commit fails, attempt to remove published bytes. Cleanup failures are logged for reconciliation. No partially persisted file is downloadable through the API.

Delete:

1. Lock metadata, verify the owner, mark DELETED and set BaseEntity deletion audit fields.
2. Commit before deleting physical bytes.
3. Attempt disk cleanup. Failure leaves metadata inaccessible and bytes retryable.
4. The owner can repeat DELETE. Internal StorageCleanupService.cleanupDeleted(fileId) also retries only committed tombstones, by UUID. It returns false if no tombstone exists or cleanup fails. It is not exposed as a new administrative API.

For operational cleanup, select tombstone IDs (status=DELETED and deleted_at IS NOT NULL) and invoke the internal cleanup service through trusted maintenance tooling. Missing bytes are already cleaned. Keep tombstones; no automatic scheduled job or public path/key deletion was introduced.

## Security and operational limits

- Provider paths accept only UUID.blob keys; APIs accept file UUIDs, never disk keys/paths. Symlink directories/files and unexpected root resolution are rejected, and opens use NOFOLLOW_LINKS.
- Storage directories and ancestors must be controlled by trusted administrators and protected from unrelated local writers. Portable path checks cannot guarantee race resistance against a privileged local process replacing directories between checks.
- Filesystem and MySQL are not a distributed transaction. A process crash between disk publication and database commit can leave orphan bytes or staging files. Abrupt power loss and ambiguous database commit outcomes also require reconciliation; there is no durable outbox or automatic orphan sweeper.
- Reconciliation must run with uploads stopped: compare final keys against all stored_files rows, preserve referenced AVAILABLE/PENDING files, and remove only confirmed unreferenced staging/final files after backup and an appropriate retention window. Restore missing referenced bytes from backup. Do not delete by original filename.
- Deletion prevents new authorized requests after commit; an already-authorized stream cannot retract bytes already sent and may complete. On Windows an open handle can defer physical deletion until retry.
- This local provider targets one server/persistent volume and serializes store/delete operations. No cross-node coordination, quota system, cloud provider, public URLs, range-download protocol, or malware scanner was added.

## Verification

Final command: Java 17, gradlew.bat compileJava test --console=plain — BUILD SUCCESSFUL. **99 tests passed, zero failures/errors/skips**, including 30 new storage tests and all 69 existing regression tests.

Docker Desktop was initially stopped; that infrastructure failure was resolved by starting the installed runtime. The successful final run exercised MySQL 8.4 Testcontainers, applied migrations only to isolated test databases, and created no development storage directory. No production database was reset or migrated by this task.

HTML results: ../build/reports/tests/test/index.html.

| Test suite | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| com.qlink.AuthIntegrationTest | 11 | 0 | 0 |
| com.qlink.ChatIntegrationTest | 16 | 0 | 0 |
| com.qlink.ChatRulesTest | 2 | 0 | 0 |
| com.qlink.GroupChatMigrationTest | 1 | 0 | 0 |
| com.qlink.SearchKeywordTest | 2 | 0 | 0 |
| com.qlink.security.oauth.DotEnvConfigurationTest | 3 | 0 | 0 |
| com.qlink.security.oauth.OAuthConfigurationTest | 4 | 0 | 0 |
| com.qlink.security.oauth.OidcProviderTokenVerifierTest | 28 | 0 | 0 |
| com.qlink.SocketSecurityTest | 2 | 0 | 0 |
| com.qlink.storage.FileContentPolicyTest | 6 | 0 | 0 |
| com.qlink.storage.LocalFileStorageProviderTest | 8 | 0 | 0 |
| com.qlink.storage.StorageCommitFailureTest | 1 | 0 | 0 |
| com.qlink.storage.StorageConfigurationTest | 4 | 0 | 0 |
| com.qlink.StorageIntegrationTest | 10 | 0 | 0 |
| com.qlink.StorageMigrationTest | 1 | 0 | 0 |

All requested backend implementation requirements are complete. Deployment-specific persistent-volume permissions, backup/reconciliation procedures, and production capacity testing remain operational verification, not additional Phase 2.4 work.

Tests cover authenticated upload, safe metadata/auditing, random keys and checksum, content/category/extension validation, exact and excessive multipart sizes, owner restrictions, deleted and missing files, retryable deletion, filesystem errors, database flush and commit failure cleanup, traversal and symlinks, environment profiles, temporary-directory isolation, and additive migration constraints. Native HTTP multipart requests exercise servlet limits separately from MockMvc. Existing authentication/chat/WebSocket regression tests remain part of the full suite.

## File inventory

No Git metadata exists in this workspace. This list records intentional source/configuration/test/document edits, not a Git diff. Generated Gradle outputs are omitted.

New files:

- src/main/java/com/qlink/features/storage/config/StorageMultipartConfiguration.java
- src/main/java/com/qlink/features/storage/config/StorageProperties.java
- src/main/java/com/qlink/features/storage/controller/StorageController.java
- src/main/java/com/qlink/features/storage/dto/StoredFileResponse.java
- src/main/java/com/qlink/features/storage/entity/StoredFile.java
- src/main/java/com/qlink/features/storage/enums/FileCategory.java
- src/main/java/com/qlink/features/storage/enums/StoredFileStatus.java
- src/main/java/com/qlink/features/storage/mapper/StoredFileMapper.java
- src/main/java/com/qlink/features/storage/provider/LocalFileStorageProvider.java
- src/main/java/com/qlink/features/storage/repository/StoredFileRepository.java
- src/main/java/com/qlink/features/storage/service/FileContentPolicy.java
- src/main/java/com/qlink/features/storage/service/FileStorageProvider.java
- src/main/java/com/qlink/features/storage/service/StorageAccessPolicy.java
- src/main/java/com/qlink/features/storage/service/StorageCleanupService.java
- src/main/java/com/qlink/features/storage/service/StorageErrors.java
- src/main/java/com/qlink/features/storage/service/StorageService.java
- src/main/resources/db/migration/V5__stored_files.sql
- src/main/resources/application-prod.yml
- src/main/resources/application-test.yml
- src/test/java/com/qlink/StorageIntegrationTest.java
- src/test/java/com/qlink/StorageMigrationTest.java
- src/test/java/com/qlink/storage/FileContentPolicyTest.java
- src/test/java/com/qlink/storage/LocalFileStorageProviderTest.java
- src/test/java/com/qlink/storage/StorageCommitFailureTest.java
- src/test/java/com/qlink/storage/StorageConfigurationTest.java
- docs/STORAGE_MANAGEMENT.md

Modified existing files:

- build.gradle
- .env.example
- .gitignore
- src/main/resources/application.yml
- src/main/java/com/qlink/config/SecurityConfig.java
- src/main/java/com/qlink/common/exception/GlobalExceptionHandler.java
- src/test/java/com/qlink/AuthIntegrationTest.java
- src/test/java/com/qlink/ChatIntegrationTest.java
- src/test/java/com/qlink/GroupChatMigrationTest.java

AuthIntegrationTest and ChatIntegrationTest only gained isolated storage roots. GroupChatMigrationTest now explicitly targets V4 so its existing upgrade assertions remain scoped to that migration; StorageMigrationTest verifies V4-to-V5 separately. SecurityConfig only adds DELETE to allowed CORS methods. GlobalExceptionHandler adds consistent multipart errors.

Dependency references: [Apache Tika supported releases](https://tika.apache.org/download.html) and [Tika core usage](https://tika.apache.org/3.3.3/gettingstarted.html). Only the core detection library was added, not parser bundles.


## Phase 2.5 attachments

`GET /api/v1/storage/files/policy` exposes the configured maximum size and extension/MIME allowlist to authenticated clients. Office files are additionally inspected by container content (Apache POI for legacy OLE files; bounded ZIP/XML inspection for OOXML), and the detected subtype must match the extension. This is type validation, not malware scanning.

Messages can reference an uploaded file with type `IMAGE` or `FILE` and `fileId`. Existing sender ownership, unique attachment, active membership and group history visibility checks apply. Attached files cannot be deleted independently. Message responses carry an `attachment` object with the authoritative original name, content type and size. See `../../PHASE_2_5.md` for the migration, Flutter workflow and validation notes.
