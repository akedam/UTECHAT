# UTEChat Agora token endpoint

This Node backend connects Firebase Authentication, Firestore signaling, Agora RTC, FCM delivery and private Supabase images. Android clients must never contain `AGORA_APP_CERTIFICATE`, a Firebase Admin private key or a Supabase service-role key. See [free tester deployment](../../docs/FREE_TEST_BACKEND.md) and the root `render.yaml`.

## Local setup

1. Create an Agora project with App ID and App Certificate. Keep the certificate only on this server.
2. Install Node 22+, then run `npm ci` in this directory (dependencies are locked).
3. Configure Firebase Admin Application Default Credentials for project `utechat-ab9d8` (for example, `gcloud auth application-default login` with an authorized developer account). Do not put a service-account JSON in this repository.
4. From PowerShell in this directory, run `./configure.ps1`. Enter the App ID, Primary Certificate, and path to your Firebase Admin service-account JSON (store the JSON outside this repository). The script validates the project and creates ignored `.env` and root `agora.local.properties` files. The certificate prompt is masked and the certificate stays on the server. Alternatively, use `.env.example` with authorized Firebase ADC.
5. Run `npm start`. For Android Emulator set Gradle properties in `~/.gradle/gradle.properties` (Windows: `%USERPROFILE%\.gradle\gradle.properties`):

   ```properties
   utechatAgoraAppId=YOUR_PUBLIC_AGORA_APP_ID
   utechatAgoraTokenServerUrl=http://10.0.2.2:8787/api/rtc-token
   ```

   `configure.ps1` already writes these two public client settings to ignored `agora.local.properties`; no user-home changes are necessary. Explicit Gradle `-P` properties take precedence. Rebuild and install the APK on **both** devices after changing the settings. `GET http://localhost:8787/healthz` reports configuration loaded; an actual authenticated call is still needed to verify Firebase permissions and RTC media.

   The server is not started with placeholder credentials. A Firebase project file bundled in Android (`google-services.json`) does not grant Firebase Admin access to this server.

   Cleartext HTTP is accepted only in a debug build and only for `10.0.2.2`/`localhost`. For a physical phone use an HTTPS endpoint reachable from the phone. The App ID is not a secret; the App Certificate is.

The endpoint verifies Firebase ID tokens, checks the accepted Firestore call and authenticated membership, and only then issues a 30-minute publisher token. Protect it with HTTPS, network rate limits, monitoring, and a production hosting environment before public use. It uses Firebase Admin ADC; no private key or token is committed.

The Android sender queues `/api/call-invite` or `/api/message-notify` after its Firestore write. The backend verifies the durable record and sender, looks up current members and private device tokens, then sends data-only FCM with durable deduplication. Foreground calls open the existing call screen; background calls show a tappable incoming-call notification. Force-stop, missing Android notification permission and vendor battery restrictions can prevent delivery. Group calls and screen sharing are not implemented.

`PUT/GET /api/media?path=conversations/CONVERSATION/OWNER/MESSAGE` authenticate Firebase ID tokens, enforce current conversation membership, immutable image bytes, supported signatures and per-user daily upload quota. The private Supabase bucket is never exposed publicly. Existing Firebase Storage objects are not automatically migrated. `GET /healthz` checks loaded configuration only; live authenticated media/RTC checks are still needed.

Run `npm test` for isolated HTTP authorization, RTC membership, notification deduplication, call expiry and private image tests. Tests do not contact your Firebase project.
