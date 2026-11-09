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
  "createdAt": "timestamp",
  "lastSeen": "timestamp",
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
  "lastMessageAt": "timestamp",
  "lastMessageSenderId": "string",
  "createdBy": "string",
  "createdAt": "timestamp"
}
```

### `conversations/{conversationId}/messages/{messageId}`
```json
{
  "senderId": "string",
  "type": "text | image | file",
  "content": "string",
  "mediaUrl": "string | null",
  "fileName": "string | null",
  "fileSize": "number | null",
  "sentAt": "timestamp",
  "readBy": ["uid1"]
}
```

### `friendships/{friendshipId}`
```json
{
  "fromUid": "string",
  "toUid": "string",
  "status": "pending | accepted | blocked",
  "createdAt": "timestamp",
  "updatedAt": "timestamp"
}
```

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
| `firebase-storage` | Lưu ảnh/tệp | G5 |
| `firebase-messaging` | Push notification | G6 |

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
| **G3** | Firebase Auth, hồ sơ tên/bio, đăng xuất, session gate | Firebase app config + bật Email/Password và Firestore |
| **G4** | Bạn bè, khám phá nhóm, ảnh đại diện | G3 xong; Firebase Storage cần cấu hình cho avatar |
| **G5** | Chat nhóm, gửi ảnh/tệp | G4 xong |
| **G6** | Push notification FCM | G5 xong |
| **G7** | Gọi thoại/video (tùy chọn) | G6 xong |

### 9.1 G3 account and profile setup

- Firebase Authentication and Cloud Firestore are selected; Android uses the Firebase BoM.
- Put the Firebase Android app configuration at `app/google-services.json`. The file is ignored by Git and is not created in this repository.
- Register Android application ID `com.example.utechat` in the Firebase project, enable Email/Password under Authentication, and create the Firestore database.
- Deploy the repository's `firestore.rules` before using Firestore (`firebase deploy --only firestore:rules --project YOUR_PROJECT_ID`). The rules only allow an authenticated user to read or update their own `users/{uid}` profile.
- Without `google-services.json`, the app deliberately runs a local mock repository. Mock credentials are never persisted; the mock session stores only a demo identity and is not real authentication.
- Avatar upload is not included: Firebase Storage and its rules remain for a later phase. This phase can retain/read a nullable `photoUrl` field only.
