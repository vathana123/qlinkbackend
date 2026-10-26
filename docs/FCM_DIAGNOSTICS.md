# Local Android push verification

Firebase project: `qlink-b1257`; Android package: `com.example.qlinkfrontend`.

The Android Firebase configuration does not configure Firebase Admin. The backend
defaults to `PUSH_ENABLED=false`, which skips creating push delivery jobs even
when chat messages and device registration succeed. Messages sent with push
disabled are not retroactively enqueued. Send a new test message after enabling it.

## Backend setup

Keep Firebase Admin credentials outside the repository. Use existing Application
Default Credentials authorized to send FCM messages to `qlink-b1257`, or an
externally stored service-account file. Enable the Firebase Cloud Messaging API
(HTTP v1) for the project and grant the sending identity FCM send permission.
Do not use `google-services.json` as an Admin credential.

From `D:\Vathana\qlink\qlinkbackend`, in the shell that starts Spring Boot:

```powershell
$env:PUSH_ENABLED = 'true'
$env:FIREBASE_PROJECT_ID = 'qlink-b1257'
$env:GOOGLE_APPLICATION_CREDENTIALS = 'C:\outside-repository\firebase-admin.json'
# Replace the placeholder with your existing credential path.
$env:LOGGING_LEVEL_COM_QLINK_FEATURES_PUSH = 'DEBUG'
.\gradlew.bat bootRun --console=plain
```

Stop the existing backend instance before starting this one. If launching from
an IDE, set these environment variables in that run configuration and restart.
`GOOGLE_APPLICATION_CREDENTIALS` must be a process environment variable for the
Google SDK; adding it only to Spring's imported `.env` does not configure ADC.
The development launcher `scripts/run-dev.ps1` now imports this one path from
the ignored `.env` into its process environment when no environment override is
already set. You can use `.\scripts\run-dev.ps1` with the configured local `.env`.
Do not run Flyway repair or alter migrations for push setup.

Expected startup: `Firebase Admin initialized for project=qlink-b1257`.
Initialization alone does not prove FCM authorization; check dispatch results.

## Two-emulator test (one build)

From `D:\Vathana\qlink\qlinkfrontend`, stop active Flutter builds first:

```powershell
flutter build apk --debug --dart-define-from-file=env/development.json --dart-define=QLINK_FCM_ENABLED=true
$adb = "$env:LOCALAPPDATA\Android\sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 install -r build\app\outputs\flutter-apk\app-debug.apk
& $adb -s emulator-5556 install -r build\app\outputs\flutter-apk\app-debug.apk
& $adb -s emulator-5554 shell am start -n com.example.qlinkfrontend/.MainActivity
& $adb -s emulator-5556 shell am start -n com.example.qlinkfrontend/.MainActivity
```

Alternatively select **qLink Development (FCM)** in VS Code and run on one device
at a time. The other development profiles do not implicitly enable FCM.

1. Sign into A on 5554 and B on 5556. Allow notifications on both.
2. Check Flutter logs for Firebase project, permission, token availability and
   successful device registration. Check backend registration account IDs match.
   Neither diagnostic path prints tokens or credentials.
3. Ensure B has not muted the conversation. Press Home on B (do not force-stop):
   `& $adb -s emulator-5556 shell input keyevent KEYCODE_HOME`.
4. Send a **new** message from A. Backend DEBUG logs should show enqueue with at
   least one device, then `FCM accepted`. This confirms provider acceptance,
   not Android display. Verify B's notification shade contains one generic qLink
   notification, and tapping it opens the correct conversation.
5. Bring B to the foreground and send another new message. The existing design
   intentionally shows no system banner: foreground FCM acknowledges/synchronizes
   chat. If B already viewed it, the backend may skip dispatch with `reason=seen`.
6. Mute the conversation on B and repeat: no push should be queued for B. Unmute
   afterward. Logout must unregister the device; token refresh must update it.

Android currently uses FCM's fallback notification channel, created on the first
background notification. Absence before the first delivery is expected. There is
no custom local notification path, avoiding duplicate foreground notifications.

## Read the diagnostics

- `Push disabled`: fix backend flags/credentials and restart.
- Flutter `QLINK_FCM_ENABLED=false`: rebuild using the FCM profile/define.
- Permission denied or unavailable token: resolve device notification/Play
  Services/network configuration before expecting registration.
- Registration failure: HTTP status and error type are logged, never request data.
- Enqueue `devices=0`: check recipient registrations and mute preferences.
- Dispatch skipped: reason identifies session, membership, seen, mute, hidden,
  removed/reassigned device or history-boundary filtering.
- FCM rejection: messaging error code is logged. Check project/credential/token
  consistency and API permissions. `UNREGISTERED` removes the invalid device.
- FCM accepted but no Android notification: check B is backgrounded, Android
  notification settings, network/Play Services and notification shade.

Keep push DEBUG logging temporary. Logs contain routing/account UUIDs but no
message text, FCM tokens, OAuth tokens or credentials.

## Verified local result (2026-10-25)

- Before configuration, an emulator-5554 message persisted with zero push jobs
  and no Android notification on backgrounded emulator-5556.
- Enabled Firebase Admin using the externally stored credential, restarted the
  backend, and validated all 15 existing Flyway migrations without changing them.
- Built one FCM-enabled APK and installed it sequentially on both emulators.
  Both logged project `qlink-b1257`, authorized permission, available tokens and
  successful account-scoped registration. Cached tokens matched the correct users.
- Background test: Android enqueued one notification at 02:37:38; the recorded
  tap at 02:37:55 opened the correct conversation. The fallback channel worked.
- Foreground test: message received at 02:38:34 with intentional banner
  suppression; no additional system notification was enqueued.
- Two stale registrations returned `UNREGISTERED` and were removed by existing
  cleanup. Remaining rows had distinct tokens and installations; repeat
  registration reused the current installation, with no duplicate token rows.
- 36 chat integration tests and 2 Firebase gateway tests passed. Flutter analysis
  passed on changed Dart files; all 3 push-session tests and the APK build passed.

Credential path/blob were absent from all available Git history and the index;
the original file was ignored under `secrets/`. It was moved intact outside the
repository. No credentials were printed or committed. No Firebase console setup
blocker remained for this Android test.
