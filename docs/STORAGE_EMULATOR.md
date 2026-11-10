# Local Storage Emulator for UTEChat

The Android app defaults to the Firebase Storage Emulator (`10.0.2.2:19199`) for every build variant. Firebase Authentication and Cloud Firestore remain connected to the configured Firebase project in the installed app. This keeps Storage uploads and downloads local by default; it does **not** make app operations against real Auth/Firestore local.

## Start the emulator on Windows

From the repository root, in Command Prompt or PowerShell:

```powershell
& "$env:APPDATA\npm\firebase.cmd" emulators:start --only auth,storage --config firebase.storage-emulator.json --project utechat-ab9d8
```

This uses `storage.emulator.rules`, a local-only rules file, on ports `19099/19199`. It enforces authentication, uploader path ownership, image MIME type, metadata, immutable objects, and the 8 MiB maximum. It intentionally does not check conversation membership because that check in `storage.rules` reads Firestore and this setup runs Storage alone. The actual membership rules remain in `storage.rules` and are covered by `npm run test:rules` with the Firestore and Storage emulators.

## Connect a device

- Android Studio emulator: use the default host `10.0.2.2`.
- Physical phone on the same Wi-Fi: find the computer's LAN IPv4 address and set it at build time, for example:

```powershell
.\gradlew.bat assembleDebug -PutechatStorageEmulatorHost=192.168.1.20
```

Replace `192.168.1.20` with the computer's actual LAN address. Allow inbound TCP port `19199` on the computer's private-network firewall if Windows asks. Install `app/build/outputs/apk/debug/app-debug.apk` on the phone. The emulator binds to network interfaces so a phone can reach it; use a trusted private network and stop it when testing is done.

The `FirebaseMediaRepositoryEmulatorSmokeTest` Android instrumentation test signs in anonymously to the local Auth emulator, then uploads and downloads a generated 68-byte PNG under a random emulator-only path. It does not use the app's live Auth session or read/write Firestore. It deliberately leaves the object in the local Storage emulator.

## Billing and live Storage

The Storage emulator itself does not upload to the Firebase bucket. Auth and Firestore calls still go to the real Spark project and can modify its data, subject to Firebase's no-cost quotas. Do not use `-PutechatUseStorageEmulator=false` unless you intentionally want the app to use a real Storage bucket and have reviewed the billing plan and rules.
