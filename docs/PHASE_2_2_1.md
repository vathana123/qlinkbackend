# Phase 2.2.1 — Group chat database and entities

Only backend entity definitions, a new migration, tests and this report were changed. No controllers, services, APIs, group-creation logic, authentication or Flutter code were changed.

## Existing model assessment

ChatRoom, ChatRoomMember and ChatMessage inherit UUID IDs, timestamps, audit actors and soft-delete fields from BaseEntity, and use SQLRestriction for normal active-row reads. Relationships are UUID columns backed by database foreign keys. ChatRoom previously used RoomType.PERSONAL and a mandatory canonical pair key. Memberships already have joinedAt for future history filtering. ChatMessage already identifies room and sender and has sender-scoped client-message idempotency; it needs no change for this schema phase. User also needs no change.

## Entity changes

- Rename the existing RoomType enum to the requested ChatRoomType, retaining the stored PERSONAL value and adding GROUP_PRIVATE and GROUP_PUBLIC. Only ChatRoom referenced the old Java enum.
- Add MessageHistoryVisibility: VIEW_ALL_MESSAGES and VIEW_AFTER_JOIN.
- Add ChatRoom.messageHistoryVisibility, default VIEW_ALL_MESSAGES; add nullable groupName (255 characters). Make personalPairKey nullable for groups.
- Add ChatRoomMemberRole: OWNER and MEMBER. Add ChatRoomMember.role, default MEMBER.
- All enums are persisted as strings. Existing UUID, audit, soft-delete and creator fields are unchanged.

## Migration and compatibility

V4__group_chat_entities.sql applies after the unchanged V1–V3 migrations.

chat_rooms changes:

- New non-null message_history_visibility VARCHAR(30), with database default VIEW_ALL_MESSAGES.
- New nullable group_name VARCHAR(255), default NULL.
- Expand the room-type check to PERSONAL, GROUP_PRIVATE and GROUP_PUBLIC.
- Allow NULL personal_pair_key for groups; a check still requires non-null keys for PERSONAL and forbids group pair keys.
- Rebuild the generated active_pair and its unique constraint in the same ALTER statement. Only active PERSONAL rooms contribute a non-null key. Multiple group rows and historical personal tombstones can coexist without weakening active personal-pair uniqueness.
- Constrain history visibility to the two supported enum values.

chat_room_members changes:

- New non-null role VARCHAR(20), database default MEMBER, with an OWNER/MEMBER check.
- Existing active-membership uniqueness, joined times and foreign keys remain unchanged.

Both active and deleted historical rows receive the defaults. Existing personal creators remain in creator_id; the migration deliberately does not reinterpret personal creators as group owners. Old personal insert statements remain valid because the new non-null columns have database defaults. Existing data, IDs, messages, audit timestamps and actors are preserved. The migration uses normal MySQL ALTER operations and may acquire table locks while indexes are rebuilt; it does not reset or clean the database.

## Scope boundary

These are persistence definitions only. VIEW_AFTER_JOIN is not yet enforced by message queries, and OWNER does not yet grant permissions. Group creation, required-owner validation, membership management and group-specific API behavior belong to later tasks. There is no application path to create a group in this phase.

## Verification

`gradlew.bat compileJava test --console=plain` compiled successfully and ran 61 tests: 60 passed, with the new migration test initially failing on exception-reporting assumptions. After correcting those test-only assertions, `gradlew.bat test --tests com.qlink.GroupChatMigrationTest --console=plain` passed. All 61 tests are therefore covered by passing runs; production code was unchanged between runs. The migration and constraints themselves worked as intended throughout. No tests were skipped.

The new MySQL migration test starts at V3, inserts active/historical personal rooms, memberships and a message, then migrates to V4. It checks preservation, defaults, both group types, invalid enum values, pair and membership constraints, and release of the personal pair on soft deletion. It also validates Flyway checksums and a second no-op migration run. An entity integration test checks JPA persistence/reloading and soft-delete filtering for both group types. Existing personal-chat, concurrency, idempotency, discovery and authentication tests remain in the suite.

## Changed files (relative to qlinkbackend)

- src/main/java/com/qlink/features/chat/enums/RoomType.java — removed/renamed.
- src/main/java/com/qlink/features/chat/enums/ChatRoomType.java — added.
- src/main/java/com/qlink/features/chat/enums/MessageHistoryVisibility.java — added.
- src/main/java/com/qlink/features/chat/enums/ChatRoomMemberRole.java — added.
- src/main/java/com/qlink/features/chat/entity/ChatRoom.java — updated.
- src/main/java/com/qlink/features/chat/entity/ChatRoomMember.java — updated.
- src/main/resources/db/migration/V4__group_chat_entities.sql — added.
- src/test/java/com/qlink/GroupChatMigrationTest.java — added.
- src/test/java/com/qlink/ChatIntegrationTest.java — entity persistence test added.
- docs/PHASE_2_2_1.md — added.
