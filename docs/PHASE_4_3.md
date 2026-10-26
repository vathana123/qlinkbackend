# Phase 4.3 — reliable messaging and push

Implemented sequentially on `phase_4/phase_4.3`: 4.3A.1 durable outbox, 4.3A.2 receipts, 4.3A.3 recovery, and 4.3B push integration.

## Messaging contracts

- Production injects `SecureOutboxStore` from `main.dart`. Draft metadata is stored with flutter_secure_storage under the authenticated account. Pending media is copied to application-support storage before network sends. Logout/account switching cancels workers and retains the original account’s pending records/files. Tests can inject an in-memory store.
- Text, image, file and voice sends retain `clientMessageId`. Uploaded references and preparation intent are persisted. Recoverable failures retry at 2, 4, 8, 16, 32 and then 64-second intervals; connection/resume also triggers retry. Invalid content/permission failures require explicit retry or discard. Revoked conversation drafts remain retained, without automatic sending.
- Uploads use an owner-scoped stable upload key. A retry first reconciles a committed message and existing upload. Expired upload references can be replaced using retained bytes and a newly persisted upload key. Media is removed after successful reconciliation or explicit discard.
- A confirmed message uses server `(createdAt, id)` ordering. REST and socket echoes merge by sender/client ID; stale revisions and lower receipt statuses cannot replace newer state.
- Each new message captures eligible recipients and their membership periods. Delivered means every captured recipient has acknowledged on at least one device. Seen means every captured recipient has acknowledged viewing. Later additions do not expand the set. Departures do not manufacture acknowledgements, so an unacknowledged departed recipient can prevent aggregate Seen. A read acknowledgement also acknowledges delivery; duplicate/out-of-order requests do not advance twice.
- Legacy receipt rows are backfilled from current visible memberships because historical recipient snapshots were not previously recorded. Newly joined/rejoined users cannot acknowledge a different membership period.
- Seen is sent only for messages intersecting the current foreground conversation viewport. Background push may acknowledge delivery using a valid access token; it never rotates refresh tokens in a second isolate. Expired access tokens wait for normal foreground recovery.
- Reconnect synchronization fetches at most ten 30-message pages per batch, yields, then continues. Initial synchronization loads one page rather than the full conversation. Failed committed realtime publications retry from durable event jobs, with stable event identities and bounded backoff. REST history remains the recovery source for disconnected devices.

## Migrations and endpoints

All 11 pre-existing applied checksums were verified against local MySQL before implementation. V1–V11 were not changed. New migrations were exercised against disposable MySQL databases; the development database has not been migrated by this phase.

| Migration | Purpose |
| --- | --- |
| V12 | Owner-scoped upload idempotency |
| V13 | Recipient snapshots and delivery/read receipt counters |
| V14 | Durable realtime event retry jobs |
| V15 | Device registrations, room mute preferences, push jobs |

- Existing `POST /api/v1/storage/files` accepts optional `uploadKey`.
- `GET /api/v1/storage/files/uploads/{key}` finds the authenticated owner’s upload.
- `GET /api/v1/chat/messages/client/{key}` reconciles the authenticated sender’s committed message, including edits/deletion/hiding.
- `POST /api/v1/chat/rooms/{roomId}/receipts`: `{messageIds: [up to 50 IDs], seen: boolean}`.
- `GET /api/v1/chat/rooms/{roomId}` retrieves an authorized conversation for notification navigation.
- `PUT /api/v1/push/devices/{installationId}`: `{token, platform: "ANDROID" | "IOS"}`; DELETE unregisters the current session’s installation.
- `GET/PUT /api/v1/chat/rooms/{roomId}/notifications` reads/writes `{muted: boolean}`. ChatScreen exposes the mute action.
- New WebSocket type: `MESSAGE_RECEIPT`. Message DTOs include `delivery`, `recipientCount`, `deliveredCount`, and `seenCount`; existing message events remain supported.

## Firebase setup required

Push is disabled by default. No Firebase project identifiers, service-account secrets, Android config or APNs credentials were invented. The two official Flutter packages and the Firebase Admin Java SDK are the only added direct dependencies.

1. Create/select the real Firebase project and register the actual Android application ID and iOS bundle ID. Run FlutterFire configuration for those applications. Ensure `android/app/google-services.json` is processed by the Google Services Gradle plugin and `ios/Runner/GoogleService-Info.plist` is included in the Runner target. Native default Firebase configuration is used by this implementation.
2. On iOS, enable Push Notifications and Background Modes → Remote notifications in Xcode, with the correct signed APNs entitlement/provisioning profile. Upload the real APNs authentication key/certificate in Firebase. The existing iOS minimum target is already 15.0. The UIScene notification delegate hook is included.
3. Enable the Firebase Cloud Messaging API. Set backend `PUSH_ENABLED=true`, `FIREBASE_PROJECT_ID` to the real project, and provide Application Default Credentials (for local development, `GOOGLE_APPLICATION_CREDENTIALS` points to a service-account file outside version control). The service account needs permission to send FCM messages.
4. Launch the Flutter app with `--dart-define=QLINK_FCM_ENABLED=true`. Permission is requested after authentication. Auto-initialization is disabled until configured/authenticated. Token refresh re-registers the installation; logout unregisters/deletes its token and backend session logout removes registrations.
5. Verify on configured Android/iOS devices: foreground synchronization without an extra alert; background/terminated generic alerts; tap to the authorized room; token refresh; muted rooms; invalid tokens; and logout/account switching.

Setup follows the [Firebase Admin documentation](https://firebase.google.com/docs/admin/setup) and [Flutter FCM receive/interaction documentation](https://firebase.google.com/docs/cloud-messaging/flutter/receive-messages).

Notifications contain only routing IDs and a generic qLink alert, never message text or file names. Push jobs are unique per message/device. Sender devices are excluded; permissions, membership period, read state, muting and session validity are checked before sending. Unregistered FCM tokens are removed. Stable Android tags and APNs collapse IDs reduce provider retry duplicates; FCM is not an exactly-once transport. Foreground presentation is disabled and the app does not generate a second local notification.

## Verification

- Backend complete suite: 134 tests passed, zero failures/errors, including MySQL migrations, upload idempotency, personal/group receipts, two-device WebSocket updates, reply/edit/delete regressions, durable recovery and push recipient/mute/token/logout policy.
- Flutter analysis: clean.
- Full Flutter suite: 163 passed and the three previously identified group-info workflow tests failed at `group_chat_test.dart:303` (off-screen Member expectation). No unrelated UI correction was made.
- Final focused Flutter suite: all 68 tests passed, including expired-upload recovery, account-token isolation, media/outbox handling, receipts, push lifecycle and bounded reconnect catch-up.
- FCM lifecycle is tested with an injected fake client. Real Android/iOS provider delivery was not run because configuration/credentials are missing and this host cannot build/sign iOS.

## Changed files

### qlinkbackend

- `build.gradle`
- `src/main/java/com/qlink/features/auth/service/AuthService.java`
- `src/main/java/com/qlink/features/chat/controller/ChatController.java`
- `src/main/java/com/qlink/features/chat/dto/ChatDtos.java`
- `src/main/java/com/qlink/features/chat/entity/ChatEventJob.java`
- `src/main/java/com/qlink/features/chat/entity/ChatMessage.java`
- `src/main/java/com/qlink/features/chat/entity/MessageReceipt.java`
- `src/main/java/com/qlink/features/chat/mapper/ChatMapper.java`
- `src/main/java/com/qlink/features/chat/realtime/ChatEventDelivery.java`
- `src/main/java/com/qlink/features/chat/realtime/ChatEventListener.java`
- `src/main/java/com/qlink/features/chat/realtime/ChatEventRecovery.java`
- `src/main/java/com/qlink/features/chat/realtime/MessageCommitted.java`
- `src/main/java/com/qlink/features/chat/repository/ChatEventJobRepository.java`
- `src/main/java/com/qlink/features/chat/repository/MessageReceiptRepository.java`
- `src/main/java/com/qlink/features/chat/service/ChatService.java`
- `src/main/java/com/qlink/features/push/FirebasePushGateway.java`
- `src/main/java/com/qlink/features/push/PushController.java`
- `src/main/java/com/qlink/features/push/PushDelivery.java`
- `src/main/java/com/qlink/features/push/PushDeliveryRepository.java`
- `src/main/java/com/qlink/features/push/PushDevice.java`
- `src/main/java/com/qlink/features/push/PushDeviceRepository.java`
- `src/main/java/com/qlink/features/push/PushGateway.java`
- `src/main/java/com/qlink/features/push/PushService.java`
- `src/main/java/com/qlink/features/push/RoomNotificationPreference.java`
- `src/main/java/com/qlink/features/push/RoomNotificationPreferenceRepository.java`
- `src/main/java/com/qlink/features/storage/controller/StorageController.java`
- `src/main/java/com/qlink/features/storage/entity/StoredFile.java`
- `src/main/java/com/qlink/features/storage/repository/StoredFileRepository.java`
- `src/main/java/com/qlink/features/storage/service/StorageService.java`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V12__idempotent_uploads.sql`
- `src/main/resources/db/migration/V13__message_receipts.sql`
- `src/main/resources/db/migration/V14__durable_chat_events.sql`
- `src/main/resources/db/migration/V15__push_notifications.sql`
- `src/test/java/com/qlink/ChatEventRecoveryTest.java`
- `src/test/java/com/qlink/ChatIntegrationTest.java`
- `src/test/java/com/qlink/FlywayV9CompatibilityTest.java`

### qlinkfrontend

- `android/app/src/main/AndroidManifest.xml`
- `ios/Runner/AppDelegate.swift`
- `ios/Runner/Info.plist`
- `lib/app/app.dart`
- `lib/core/network/auth_interceptor.dart`
- `lib/features/auth/data/repositories/auth_repository.dart`
- `lib/features/chat/data/datasources/chat_api.dart`
- `lib/features/chat/data/models/chat_models.dart`
- `lib/features/chat/data/realtime/chat_event.dart`
- `lib/features/chat/data/repositories/chat_repository.dart`
- `lib/features/chat/data/repositories/outbox_store.dart`
- `lib/features/chat/data/repositories/outgoing_queue.dart`
- `lib/features/chat/presentation/bloc/attachment_cubit.dart`
- `lib/features/chat/presentation/bloc/chat_cubit.dart`
- `lib/features/chat/presentation/bloc/rooms_cubit.dart`
- `lib/features/chat/presentation/screens/chat_screen.dart`
- `lib/features/push/push_client.dart`
- `lib/features/push/push_session.dart`
- `lib/main.dart`
- `macos/Flutter/GeneratedPluginRegistrant.swift`
- `pubspec.lock`
- `pubspec.yaml`
- `test/attachments_test.dart`
- `test/durable_outbox_test.dart`
- `test/outgoing_queue_test.dart`
- `test/push_session_test.dart`
- `test/realtime_test.dart`
- `test/receipt_test.dart`
- `windows/flutter/generated_plugin_registrant.cc`
- `windows/flutter/generated_plugins.cmake`
