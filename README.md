# UTEChat

Ứng dụng nhắn tin Android viết bằng Java, dùng XML và Material Components cho giao diện. UTEChat được phát triển làm đồ án môn học; backend chính sử dụng Firebase. Một số dịch vụ cần cấu hình riêng trước khi có thể kiểm thử đầy đủ.

## Chức năng

- Đăng ký, đăng nhập, đăng xuất và chỉnh sửa hồ sơ.
- Danh sách hội thoại, nhắn tin văn bản theo thời gian thực.
- Tìm người dùng, lời mời kết bạn và quản lý nhóm theo vai trò.
- Tạo bình chọn trong chat với 2–6 lựa chọn; thành viên có thể đổi lựa chọn của mình.
- Gửi ảnh trong chat qua Firebase Storage; giới hạn 8 MiB và hỗ trợ JPEG, PNG, WebP.
- Thông báo tin nhắn nhận qua Firebase Cloud Messaging; mặc định ẩn nội dung xem trước.
- Trạng thái hiện diện qua Firebase Realtime Database khi người dùng bật.
- Khung gọi thoại/video 1–1 bằng Agora SDK, cần token server được cấu hình riêng.

## Công nghệ

- Java 11, Android Gradle Plugin 9.5.0, Gradle 9.6.0.
- Android `minSdk 26`, `targetSdk 37`.
- Firebase Authentication, Cloud Firestore, Realtime Database, Cloud Messaging và Storage.
- AndroidX, Material Components, Glide và Agora RTC.
- Kiến trúc phân lớp: UI, ViewModel, repository và model; giao diện dùng XML.

## Mở và chạy ứng dụng

1. Cài Android Studio phiên bản tương thích với Android Gradle Plugin 9.5.0 và JDK 25 (Gradle Wrapper có thể tự tải JDK theo cấu hình dự án).
2. Mở thư mục gốc này trong Android Studio và chờ Gradle Sync hoàn tất.
3. Để dùng Firebase thật, đăng ký ứng dụng Android với application ID `com.example.utechat` trong Firebase Console, tải `google-services.json` và đặt tại `app/google-services.json`. Tệp này bị Git ignore.
4. Bật Email/Password trong Firebase Authentication. Tạo Firestore và triển khai rules cùng index từ repo. Nếu sử dụng presence, cần tạo Realtime Database và cấu hình database URL trong Firebase app configuration.
5. Chọn emulator hoặc thiết bị Android API 26 trở lên rồi chạy cấu hình `app`.

Nếu thiếu Firebase app configuration, đăng nhập, hội thoại và tin nhắn có thể dùng repository mock cục bộ để trình diễn; dữ liệu mock không đồng bộ với Firebase và không đại diện cho tài khoản thật. Tính năng bạn bè/nhóm, thông báo, presence, Storage và gọi cần dịch vụ/cấu hình tương ứng.

## Build và kiểm tra

Từ PowerShell ở thư mục gốc:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

APK debug được tạo trong `app/build/outputs/apk/debug/`. Dự án bật APK splits cho `arm64-v8a` và `x86_64`, nên chọn APK phù hợp với thiết bị/emulator.

Các kiểm tra Firebase Rules hiện có dùng Firebase Emulator Suite:

```powershell
npm install
& "$env:APPDATA\npm\firebase.cmd" emulators:exec --only database,firestore,storage --config firebase.rules-test.json --project demo-utechat "node --test tests/firebase-rules.test.mjs"
```

Lệnh trên dành cho kiểm thử cục bộ và dùng project ID giả `demo-utechat`; không deploy rules lên Firebase production. Có thể cần cài Node.js, Firebase CLI và Java để chạy emulator. Xem [firebase.json](firebase.json), [tests/firebase-rules.test.mjs](tests/firebase-rules.test.mjs) và [docs/STORAGE_EMULATOR.md](docs/STORAGE_EMULATOR.md).

## Cấu hình dịch vụ

### Firestore và Storage

- Rules: [firestore.rules](firestore.rules), [storage.rules](storage.rules).
- Indexes: [firestore.indexes.json](firestore.indexes.json).
- Deploy lên project Firebase đã chọn (thay project ID nếu cần):

  ```powershell
  & "$env:APPDATA\npm\firebase.cmd" deploy --only firestore:rules,firestore:indexes,storage,database --project utechat-ab9d8
  ```

- Bản debug mặc định trỏ Storage tới emulator `10.0.2.2:19199`, không tự tải ảnh lên bucket thật. Xem [hướng dẫn Storage Emulator](docs/STORAGE_EMULATOR.md). Bản release không trỏ tới emulator.

### Presence và thông báo

- Presence cần Realtime Database đã tạo và rules từ [database.rules.json](database.rules.json). Xem [tài liệu Giai đoạn 8](docs/NOTIFICATIONS_PRESENCE_G8.md).
- Client nhận FCM và lưu token thiết bị; repo chưa có trusted sender tự gửi thông báo khi có tin nhắn mới. Không đặt FCM server credential trong ứng dụng.

### Gọi thoại/video

- Android cần `utechatAgoraAppId` và `utechatAgoraTokenServerUrl` trong Gradle properties. Server token cần biến môi trường `FIREBASE_PROJECT_ID`, `AGORA_APP_ID`, `AGORA_APP_CERTIFICATE` và Firebase Admin Application Default Credentials. Không đưa Agora App Certificate hoặc service account key vào repo/APK.
- Hướng dẫn cài server: [server/call-token/README.md](server/call-token/README.md). Cuộc gọi đến hiện được phát hiện qua listener khi ứng dụng đang mở; chưa có trusted push sender để bảo đảm báo cuộc gọi khi app nền hoặc đã đóng.

## Giới hạn hiện tại

- Chưa có backend trusted gửi push cho tin nhắn hoặc cuộc gọi; FCM client mới nhận event theo contract.
- Upload ảnh cần Storage Emulator trong cấu hình debug hiện tại; muốn kiểm thử bucket thật cần chủ động đổi cấu hình và kiểm tra gói Firebase/rules trước.
- Tính năng gọi cần Agora project và token server chạy được, HTTPS khi dùng điện thoại thật. Chưa hỗ trợ gọi nhóm hoặc chia sẻ màn hình.
- Presence chỉ là trạng thái cơ bản, không có heartbeat định kỳ; quyền đọc hiện được giới hạn theo rules đang triển khai.
- Build và luồng trên thiết bị phụ thuộc Android SDK/JDK/Firebase credentials của môi trường phát triển.

## Tài liệu dự án

- [Tài liệu thiết kế kỹ thuật](docs/DESIGN.md)
- [Thông báo và presence](docs/NOTIFICATIONS_PRESENCE_G8.md)
- [Firebase Storage Emulator](docs/STORAGE_EMULATOR.md)
- [Agora token server](server/call-token/README.md)
- [Nhận cuộc gọi và lịch sử cuộc gọi](docs/CALLS_FIX.md)
