# UTEChat Agora token endpoint

This small Node endpoint is the trusted boundary between Firebase Authentication, Firestore call signaling, and Agora RTC. It does not send call invitations. Android clients must never contain `AGORA_APP_CERTIFICATE`.

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

Incoming-call UI in this build is delivered by a foreground Firestore listener while UTEChat is in the foreground. This repo has no trusted FCM call sender, so incoming calls are not guaranteed while the app is backgrounded or killed. It does not implement group calls or screen sharing.
