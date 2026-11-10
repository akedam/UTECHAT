# UTEChat — Tài liệu Thiết kế Kỹ thuật

> **Phiên bản:** 1.0 — Giai đoạn 1 (Kiểm kê & Chốt kiến trúc)
> **Cập nhật:** 2026-11-09
> **Trạng thái:** Đã xác nhận — sẵn sàng thực thi từ Giai đoạn 2

---

## 1. Kiểm kê dự án hiện tại

### 1.1 Môi trường build

| Thông số | Giá trị | Ghi chú |
|----------|---------|---------|
| Android Gradle Plugin | 9.5.0 | Rất mới; giữ nguyên |
| Gradle Wrapper | 9.6.0 | Khớp với AGP 9.x |
| Gradle JVM Toolchain | 25 (JDK 25) | Tự động tải qua foojay resolver |
| `compileSdk` | 37 (API 37 / Android 16) | `release(37)` syntax AGP 9+ |
| `targetSdk` | 37 | |
| `minSdk` | **26** (hạ từ 37) | Android 8.0+; ~95% thiết bị active |
| Java source/target | Java 11 | Giữ nguyên |
| Configuration Cache | Bật | Tăng tốc build lặp |
| Gradle JVM heap | 2 GB | Đủ cho dự án quy mô này |

### 1.2 Dependencies hiện có

| Thư viện | Phiên bản | Vai trò |
|----------|-----------|---------|
| `androidx.appcompat` | 1.6.1 | Base Activity/Fragment |
| `com.google.android.material` | 1.10.0 | Material 3 UI components |
| `androidx.constraintlayout` | 2.1.4 | Layouts |
| `androidx.activity:activity-ktx` | 1.13.0 | Activity result API |
| `junit` | 4.13.2 | Unit test |
| `androidx.test.espresso` | 3.7.0 | UI test |
| `androidx.test.ext:junit` | 1.3.0 | Android test runner |

### 1.3 Mã hiện có

| File | Trạng thái | Hành động |
|------|-----------|-----------|
| `MainActivity.java` | Template trống, edge-to-edge | Thay thế ở G2 |
| `activity_main.xml` | "Hello World" | Thay thế ở G2 |
| `AndroidManifest.xml` | 1 activity, không có permission | Mở rộng từng giai đoạn |
| `themes.xml` | Material3 DayNight NoActionBar | Mở rộng màu UTEChat ở G1 |
| `colors.xml` | Chỉ có black/white | Mở rộng ở G1 |
| `strings.xml` | "My Application" | Đổi thành "UTEChat" ở G1 |

### 1.4 Trạng thái build

```
✅ :app:assembleDebug — BUILD SUCCESSFUL (kiểm tra 2026-11-09)
```

---

## 2. Phạm vi sản phẩm

### 2.1 MVP (Must-have)

| # | Tính năng | Màn hình chính |
|---|-----------|---------------|
| 1 | Đăng ký / Đăng nhập | Splash → Login → Register |
| 2 | Hồ sơ cá nhân | Profile, chỉnh sửa, ảnh đại diện |
| 3 | Danh sách bạn bè | Tìm kiếm, gửi/chấp nhận kết bạn |
| 4 | Chat riêng (1-1) | Danh sách hội thoại, màn hình chat |
| 5 | Chat nhóm | Tạo nhóm, thêm thành viên, chat |
| 6 | Gửi ảnh/tệp | Trong màn hình chat |
| 7 | Thông báo đẩy | Tin nhắn mới, yêu cầu kết bạn |

### 2.2 Mở rộng (nếu còn thời gian)

| # | Tính năng | Mức độ phức tạp |
|---|-----------|----------------|
| 8 | Gọi thoại | Cao (Agora SDK) |
| 9 | Gọi video | Rất cao |
| 10 | Trạng thái online/offline | Trung bình |
| 11 | Đã đọc / Đã gửi | Trung bình |

---

## 3. Màn hình và luồng người dùng

```
[Khởi động]
    └── SplashActivity
            ├── Chưa đăng nhập ──→ LoginActivity
            │       └── [Chưa có tài khoản] ──→ RegisterActivity
            │               └── [Thành công] ──→ MainActivity
            └── Đã đăng nhập ──→ MainActivity

[MainActivity — Bottom Navigation 3 tab]
    ├── Tab 1: ConversationsFragment
    │       └── [Nhấn hội thoại] ──→ ChatActivity
    │               ├── Nhắn tin văn bản
    │               ├── Gửi ảnh (picker → Storage → Firestore)
    │               └── [Header] ──→ ProfileDetailActivity / GroupDetailActivity
    ├── Tab 2: FriendsFragment
    │       ├── Danh sách bạn bè
    │       ├── Yêu cầu kết bạn đến/đi
    │       └── Tìm kiếm người dùng
    └── Tab 3: ProfileFragment
            ├── Xem hồ sơ
            └── Chỉnh sửa ──→ ProfileEditActivity
```

---

## 4. Mô hình dữ liệu Firestore

### `users/{userId}`
```json
{
  "uid": "string",
  "displayName": "string",
  "email": "string",
  "photoUrl": "string | null",
  "bio": "string",
  "createdAt": "epochMillis (optional for legacy profiles)",
  "updatedAt": "epochMillis",
  "lastSeen": "epochMillis",
  "fcmToken": "string"
}
```

### `conversations/{conversationId}`
```json
{
  "type": "direct | group",
  "participantIds": ["uid1", "uid2"],
  "groupName": "string | null",
  "groupPhotoUrl": "string | null",
  "lastMessage": "string",
  "lastMessageId": "message document ID | null",
  "participantSummaries": {
    "uid1": { "displayName": "string", "photoUrl": "string | null" },
    "uid2": { "displayName": "string", "photoUrl": "string | null" }
  },
  "lastMessageType": "text | image | poll",
  "lastMessageAt": "epochMillis",
  "lastMessageSenderId": "string",
  "createdBy": "string",
  "createdAt": "epochMillis",
  "updatedAt": "epochMillis"
}
```

- `conversationId` là document ID ổn định. Chat riêng dùng `direct_` + SHA-256 của hai UID đã sắp xếp; nhóm dùng ID sinh một lần và không đổi. `Group.id` trùng conversation ID.
- `participantSummaries` lưu snapshot tên/ảnh để danh sách chat riêng hiển thị được đối phương mà không cần đọc hồ sơ người khác. Khi hồ sơ đổi, snapshot liên quan cần được đồng bộ ở giai đoạn backend.

### `conversations/{conversationId}/members/{uid}`
```json
{
  "userId": "uid",
  "role": "owner | admin | member",
  "joinedAt": "epochMillis",
  "lastReadAt": "epochMillis",
  "unreadCount": 0
}
```

Member document ID là UID ổn định. `unreadCount` và `lastReadAt` thuộc từng thành viên, không ghi chung vào conversation.

### `conversations/{conversationId}/messages/{messageId}`
```json
{
  "senderId": "string",
  "type": "text | image | poll",
  "content": "string",
  "mediaUrl": "string | null",
  "fileName": "string | null",
  "fileSize": "number | null",
  "sentAt": "epochMillis",
  "createdAt": "epochMillis (optional; defaults to sentAt for old messages)",
  "updatedAt": "epochMillis (optional)",
  "readBy": ["uid1"],
  "pollQuestion": "string (poll only)",
  "pollOptions": ["string (2–6 choices; poll only)"],
  "pollVotes": { "uid1": 0 }
}
```

Tin nhắn `poll` dùng `pollQuestion` và `pollOptions` bất biến sau khi tạo. `pollVotes` ánh xạ mỗi UID thành chỉ số một lựa chọn; thành viên có thể đổi lựa chọn của chính mình, không thể sửa phiếu người khác. Firestore Rules kiểm tra thành viên, số lựa chọn, độ dài và chỉ cho sửa key ứng với UID đang đăng nhập.

### `friendships/{friendshipId}`
```json
{
  "fromUid": "string",
  "toUid": "string",
  "status": "pending | accepted | blocked",
  "createdAt": "epochMillis",
  "updatedAt": "epochMillis"
}
```

G6 dùng ID canonical `{smallerUid}_{largerUid}` để một cặp chỉ có một quan hệ/lời mời, kể cả khi hai bên gửi gần như đồng thời. `publicProfiles/{uid}` là danh bạ tối thiểu chỉ gồm `uid`, `displayName`, `searchName`, `photoUrl`, `updatedAt`; email và bio vẫn nằm trong `users/{uid}` chỉ chủ tài khoản đọc. Tìm kiếm theo tiền tố tên hiển thị, tối đa 20 kết quả. Tài khoản cũ được backfill public profile khi đăng nhập sau khi rules G6 được deploy.

G6 tạo nhóm trong `conversations/{groupId}` với `type: group`, `createdBy`, `groupName`, `participantIds`, `participantSummaries`, thời gian và preview; mỗi thành viên có document ID bằng UID tại `members/{uid}` với vai trò `owner`, `admin` hoặc `member`. Tạo/thêm thành viên yêu cầu quan hệ bạn bè đã được chấp nhận. Thao tác thêm/xóa thành viên cập nhật danh sách participant và member document trong một transaction. Chủ nhóm có thể chuyển quyền; phải chuyển quyền trước khi rời nhóm. Rules kiểm tra quyền thành viên và cho phép chat nhóm qua collection messages như chat riêng.

**Tương thích G3:** hồ sơ hiện ghi `updatedAt` dạng epoch milliseconds và chưa ghi `createdAt`; G4 giữ nguyên `updatedAt` và coi `createdAt` thiếu là `0`. Mapper cũng chấp nhận Number, Firestore `Timestamp` và `Date` để đọc bản ghi cũ. Tất cả thời gian mới trong schema dùng epoch milliseconds.

**Trạng thái G4:** danh sách Firestore truy vấn `participantIds array-contains uid`, sắp `lastMessageAt desc`, lô 20 và cursor document. Khi không có Firebase config, app mới dùng `MockConversationRepository` được gắn nhãn; dữ liệu mẫu không trộn với dữ liệu thật.

**G5:** tin nhắn văn bản nằm ở `conversations/{conversationId}/messages/{messageId}`. `messageId` UUID được tạo một lần và dùng lại khi thử gửi lại. Batch ghi tin nhắn và cập nhật bản xem trước hội thoại cùng lúc; rules kiểm tra hai thay đổi khớp nhau. `sentAt`/`createdAt` giữ epoch milliseconds như G4. Trạng thái `SENDING`/`FAILED` là trạng thái UI; `hasPendingWrites` của Firestore phân biệt đang đồng bộ với đã gửi. Không có trạng thái delivered/read từ server trong G5.

---

## 5. Kiến trúc kỹ thuật

### 5.1 Quyết định đã chốt

| Quyết định | Lựa chọn | Lý do |
|-----------|---------|-------|
| Ngôn ngữ | Java | Yêu cầu dự án |
| Backend | Firebase suite | Xem §5.2 |
| UI framework | XML layouts + Material 3 | Nhất quán với codebase |
| Kiến trúc | MVVM (ViewModel + LiveData) | Vòng đời Android, tương thích Java |
| DI | Manual injection | Giảm phức tạp cho đồ án |
| Navigation | Intent-based Activity + Fragment | Đủ cho quy mô này |
| Image loading | Glide | Hỗ trợ Firebase Storage URL tốt |
| Real-time | Firestore listeners | Built-in, không cần Socket riêng |

### 5.2 So sánh backend (tóm tắt)

| Tiêu chí | Firebase | Supabase | Socket.IO tự dựng |
|----------|---------|----------|------------------|
| Real-time | ✅ Tích hợp sẵn | ✅ Postgres Realtime | ⚠️ Tự quản lý |
| Auth | ✅ Firebase Auth | ✅ Tích hợp | ⚠️ Tự cài JWT |
| Storage | ✅ Firebase Storage | ✅ Supabase Storage | ⚠️ Tự cài |
| FCM | ✅ Tích hợp | ⚠️ Thủ công | ⚠️ Tự cài |
| Java SDK | ✅ Chính thức | ⚠️ Chủ yếu Kotlin/JS | ⚠️ Tự viết client |
| Độ khó | **Thấp** | Trung bình | Cao |

**→ Firebase đã được chốt.**

### 5.3 Thư viện dự kiến thêm theo giai đoạn

| Thư viện | Mục đích | Giai đoạn |
|----------|---------|-----------|
| `firebase-bom` + `firebase-auth` | Xác thực | G2 |
| `firebase-firestore` | Database | G2 |
| `lifecycle-viewmodel`, `lifecycle-livedata` | MVVM | G2 |
| `fragment` | Fragment support | G2 |
| `glide` | Load ảnh | G3 |
| `recyclerview`, `swiperefreshlayout` | Danh sách | G3 |
| `firebase-storage` | Lưu ảnh/tệp/avatar | G6 |
| `firebase-messaging` | Push notification | G8 |
| `firebase-database` | Presence với onDisconnect | G8 |

---

## 6. Cấu trúc package đề xuất

```
app/src/main/java/com/example/utechat/
├── app/
│   └── UteChatApplication.java          # Application class, khởi tạo Firebase
│
├── core/
│   ├── common/
│   │   ├── Constants.java               # Firestore collection names, keys
│   │   ├── Resource.java                # Wrapper: Loading/Success/Error<T>
│   │   └── BaseViewModel.java           # ViewModel base
│   └── utils/
│       ├── DateUtils.java               # Format timestamp
│       ├── ImageUtils.java              # Uri, bitmap helpers
│       └── ValidationUtils.java         # Validate email, password, name
│
├── domain/
│   ├── model/
│   │   ├── User.java
│   │   ├── Message.java
│   │   ├── Conversation.java
│   │   └── Friendship.java
│   └── repository/
│       ├── AuthRepository.java          # Interface
│       ├── UserRepository.java          # Interface
│       ├── ConversationRepository.java  # Interface
│       ├── MessageRepository.java       # Interface
│       └── FriendRepository.java        # Interface
│
├── data/
│   ├── auth/
│   │   └── FirebaseAuthRepository.java
│   ├── user/
│   │   └── FirebaseUserRepository.java
│   ├── chat/
│   │   ├── FirebaseConversationRepository.java
│   │   └── FirebaseMessageRepository.java
│   ├── friends/
│   │   └── FirebaseFriendRepository.java
│   └── media/
│       └── FirebaseStorageRepository.java
│
└── feature/
    ├── auth/
    │   ├── ui/
    │   │   ├── SplashActivity.java
    │   │   ├── LoginActivity.java
    │   │   └── RegisterActivity.java
    │   └── viewmodel/
    │       └── AuthViewModel.java
    ├── main/
    │   └── ui/
    │       └── MainActivity.java
    ├── conversations/
    │   ├── ui/
    │   │   └── ConversationsFragment.java
    │   ├── adapter/
    │   │   └── ConversationsAdapter.java
    │   └── viewmodel/
    │       └── ConversationsViewModel.java
    ├── chat/
    │   ├── ui/
    │   │   └── ChatActivity.java
    │   ├── adapter/
    │   │   └── MessagesAdapter.java
    │   └── viewmodel/
    │       └── ChatViewModel.java
    ├── friends/
    │   ├── ui/
    │   │   └── FriendsFragment.java
    │   ├── adapter/
    │   │   └── FriendsAdapter.java
    │   └── viewmodel/
    │       └── FriendsViewModel.java
    ├── profile/
    │   ├── ui/
    │   │   ├── ProfileFragment.java
    │   │   └── ProfileEditActivity.java
    │   └── viewmodel/
    │       └── ProfileViewModel.java
    └── groups/
        ├── ui/
        │   ├── GroupDetailActivity.java
        │   └── CreateGroupActivity.java
        ├── adapter/
        │   └── MembersAdapter.java
        └── viewmodel/
            └── GroupViewModel.java
```

---

## 7. Luồng dữ liệu

```
UI (Activity / Fragment)
    │  observe LiveData
    ▼
ViewModel
    │  calls Repository interface
    ▼
Repository Interface (domain layer)
    │  implemented by
    ▼
FirebaseXxxRepository (data layer)
    │  uses
    ▼
Firebase SDK (Auth / Firestore / Storage / FCM)
```

**Quy tắc nghiêm ngặt:**
- UI không bao giờ gọi Firebase trực tiếp.
- ViewModel không import Firebase SDK.
- Repository trả về `LiveData<Resource<T>>` — không để raw exception lên UI.

---

## 8. Rủi ro & giảm thiểu

| Rủi ro | Khả năng | Tác động | Giảm thiểu |
|--------|---------|---------|-----------|
| Chưa có `google-services.json` | Cao | Auth chạy mock, không xác thực thật | Thêm cấu hình Firebase trước khi demo tài khoản thật |
| Firestore Security Rules sai → data leak | Trung bình | Cao | Viết rules theo giai đoạn, test console |
| FCM token hết hạn | Trung bình | Trung bình | Refresh khi login, lưu Firestore |
| Upload ảnh lớn chặn UI | Trung bình | Trung bình | Background thread + ProgressDialog |
| ViewModel hủy giữa chừng request | Thấp | Thấp | ViewModel tồn tại qua config change |
| Gọi video/thoại quá phức tạp | Cao | Thấp | Để cuối, tùy chọn |

---

## 9. Lộ trình giai đoạn

| Giai đoạn | Nội dung | Điều kiện |
|-----------|---------|-----------|
| **G1** ✅ | Kiểm kê, kiến trúc, đổi tên/package, theme UTEChat | Hiện tại |
| **G2** ✅ | Khung UI, Splash/Login/Register, điều hướng, màn hình chính và chat mock | Hoàn tất; chưa có backend |
| **G3** ✅ | Firebase Auth, hồ sơ tên/bio, đăng xuất, session gate | Firebase app config + bật Email/Password và Firestore |
| **G4** ✅ | Model dữ liệu chat và danh sách hội thoại có cursor | Firestore query theo participant, composite index |
| **G5** | Chat riêng văn bản thời gian thực, lịch sử phân trang, retry cùng ID | Mã đã build; publish `firestore.rules` và `firestore.indexes.json` phiên bản hiện tại để dùng Firebase thật |
| **G6** ✅ | Tìm bạn, lời mời, danh sách bạn, tạo/quản lý nhóm, chat văn bản nhóm | Cần deploy rules G6; dữ liệu bạn bè/nhóm thật dùng Firestore |
| **G7** ✅ | Gửi ảnh trong chat | Cần bật Firebase Storage, deploy `storage.rules` và `firestore.rules`; bucket Storage yêu cầu gói Blaze |
| **G8** ✅ (client/presence) | Nhận FCM, mở chat từ notification, RTDB presence opt-in | Cần tạo RTDB + deploy rules; cần trusted FCM sender để gửi push thật |
| **G9** 🚧 | Gọi thoại/video 1–1 | Agora RTC Android 4.7.0; Firestore signaling; cần Agora App ID, App Certificate chỉ trên token server, và endpoint HTTPS. Nhận lời gọi khi app nền/đã đóng cần trusted push sender. |
| **G10** ✅ | Rà backend kết bạn, chat, nhóm; bình chọn trong hội thoại | Firestore Rules và bộ kiểm tra emulator; cần cài lại APK mới. Cuộc gọi vẫn cần Agora token server đã cấu hình. |

### 9.1 G3 account and profile setup

- Firebase Authentication and Cloud Firestore are selected; Android uses the Firebase BoM.
- Put the Firebase Android app configuration at `app/google-services.json`. The file is ignored by Git and is not created in this repository.
- Register Android application ID `com.example.utechat` in the Firebase project, enable Email/Password under Authentication, and create the Firestore database.
- Deploy the repository's `firestore.rules` before using Firestore (`firebase deploy --only firestore:rules --project YOUR_PROJECT_ID`). The rules only allow an authenticated user to read or update their own `users/{uid}` profile.
- Without `google-services.json`, the app deliberately runs a local mock repository. Mock credentials are never persisted; the mock session stores only a demo identity and is not real authentication.
- Avatar upload is not included. This phase can retain/read a nullable `photoUrl` field only.

### 9.2 G6 friend and group behavior

- Friend search returns public display-name cards only; it does not search email or reveal `users/{uid}` documents.
- Friendship requests are pending/accepted; reject and cancel remove the pending record. Accepted friendship removal deletes the relationship, while prior direct conversation access remains limited to its two participants.
- Group membership is stored in both `participantIds` and `members/{uid}`. Member reads and group messages require both records to authorize the signed-in UID. Owners can transfer ownership; admins can add/remove ordinary members; owners cannot be removed by admins or leave before transferring ownership.
- The Android client offers initial selection of up to eight friends per new group to stay within Firestore transaction/rules document-access limits. More members can be added after creation.
- No Cloud Function/Admin SDK cleanup exists. Deleting an Auth account does not cascade Firestore documents; stale public profiles/member rows may remain until a trusted cleanup process removes them. The UI skips missing friend profiles and can show a UID for unresolved group members.

### 9.3 G7 image messages

- Android uses the system Photo Picker (`PickVisualMedia`) and requests no broad media/storage permission. Only JPEG, PNG, and WebP are accepted; client, Firestore and Storage limits now agree at 8 MiB. Images are also limited to 12,000 pixels on either dimension and 40 megapixels total to reduce decompression-memory risks.
- The client stages selected content in app-private cache, checks its byte signature, and uploads to `conversations/{conversationId}/{uid}/{messageId}`. Firestore stores this protected Storage path, MIME type, and byte size; it does not store a public download-token URL. Storage read rules require conversation membership. Objects are immutable after creation.
- Image uploads are sent before the Firestore message batch. Reusing the same message ID makes retries idempotent if the object already uploaded. A Firestore failure after a successful upload can leave an orphaned Storage object; no trusted cleanup function is included.
- Images download to app-private cache with `getFile`; Glide loads a 560×560 downsampled preview. Download tasks and image requests are canceled when rows are recycled. Upload/download failures can be retried while the message/source remains available.
- Rules validate metadata, declared MIME type, size, path, and membership, but do not inspect image bytes or scan for malicious payloads. No end-to-end encryption is implemented.
- Firebase Storage is not yet provisioned by this repository. The project owner must create/enable the bucket, deploy `storage.rules` and the updated `firestore.rules`, and enable Blaze billing before real Storage operations; no service configuration or billing change is performed by the app.
- Emulator Rules tests use the isolated `demo-utechat` project and alternate ports so they do not modify production data or collide with the normal emulator ports: run `npm install`, then `firebase emulators:exec --config firebase.rules-test.json --project demo-utechat --only database,firestore,storage "node --test tests/firebase-rules.test.mjs"` from the repository root.

### 9.4 G8 notifications and presence

- Client receives FCM data-only payloads, requests Android 13+ notification permission, creates a channel, deduplicates by conversation/message ID, hides preview by default and opens the correct chat from a notification. FCM tokens are stored in a private per-user subcollection; clients can write/delete only their own token documents and cannot read tokens.
- This repository has no Cloud Functions/server sender. It cannot send a push when a Firestore message is created until a trusted sender is deployed. Production Cloud Functions deployment requires Blaze; no server key is included in the APK.
- Presence uses Realtime Database `/presence/{uid}` with one `connections/{pushId}` for the app process. `onDisconnect` removes connection entries and writes server `lastSeen`; visibility is opt-in and defaults off. When enabled, current rules let any signed-in UTEChat user read presence, not only friends.
- Realtime Database must be created in Firebase Console and its URL included in the Android Firebase config. The profile toggle is disabled if the URL is missing. Rules are in `database.rules.json`; Auth/Firestore schema remains unchanged.
- Presence updates with app foreground/connection lifecycle and has no periodic heartbeat. Notification preview preference is local to each device. A future sender must send data-only payloads with the target UID and message/conversation IDs.

### 9.5 G9 one-to-one calls

- Android uses Agora RTC `full-sdk` 4.7.0. Agora's current Android quickstart documents Java, Android API 21+, and the Agora Maven repository; this app uses minSdk 26. The app build has compiled against the selected artifact.
- Firestore `calls/{callId}` is signaling only. It references an existing direct conversation, two participant UIDs, an opaque Agora channel equal to the call document ID, media type, state, and timestamps. Rules permit participant reads and only caller/callee state transitions; clients cannot delete records.
- States include ringing, accepted, rejected, busy, ended, failed, and missed. Ringing calls expire after 45 seconds. Permission checks precede microphone/camera startup; the in-call foreground service keeps an accepted session alive when its Activity is backgrounded. Video is selected when starting a call; mid-call voice-to-video upgrade is not implemented because it needs a consent/signaling handshake.
- `server/call-token` verifies Firebase ID tokens and accepted-call membership, then issues a short-lived Agora token. The App Certificate and Firebase Admin credentials must remain on the server; Android obtains the Firebase ID token from the authenticated SDK session. The endpoint is not deployed by this repository. Use HTTPS outside local Android Emulator debug.
- Incoming call UI and busy/reject handling work while the app is foregrounded. This repository still has no trusted FCM sender for calls, so the app cannot promise incoming calls while backgrounded or killed. A process kill/crash can also leave an `accepted` signaling record stale; add trusted cleanup/heartbeat before production use. No group calling or screen sharing is included.
