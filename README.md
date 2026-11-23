# UTEChat

UTEChat là ứng dụng trò chuyện cho Android và trình duyệt, phát triển cho đồ án môn học. Android dùng Java/XML và Material Components. Web dùng React/Vite. Dữ liệu đồng bộ qua Firebase, backend Node.js xử lý các thao tác tin cậy, Agora truyền âm thanh/video và Supabase lưu media riêng tư.

Tài liệu tổng hợp, cập nhật ngày 23/11/2026.

## Trạng thái triển khai

| Thành phần | Cấu hình |
| --- | --- |
| Web công khai | https://utechat-ab9d8.web.app/ |
| Firebase project | `utechat-ab9d8` |
| Backend HTTPS | https://utechat-test-backend.onrender.com |
| Render | `utechat-test-backend`, Free, Singapore |
| Kho tệp | Supabase, bucket riêng tư `utechat-media` |
| Media cuộc gọi | Agora RTC |

Bản web công khai đã chuyển sang số điện thoại và mật khẩu. Đăng ký, đổi và đặt lại mật khẩu dùng OTP. Firebase đã tắt Email/Password và Google. Gói Spark hiện phục vụ demo bằng số thử được cấu hình trong Firebase.

Backend xác thực điện thoại đã phát hành từ nhánh `codex/phone-auth-release`. Các thay đổi Android, web và tệp demo OTP còn ở workspace phát triển tại thời điểm tổng hợp tài liệu. Cần đồng bộ chúng trước khi tái tạo bản điện thoại hoàn chỉnh từ checkout mới của `main`. Commit dọn tài liệu chỉ cập nhật tài liệu.

## Chức năng

- Chat riêng/nhóm theo thời gian thực, lịch sử phân trang, trạng thái gửi/đọc và bản nháp theo hội thoại.
- Tên người gửi và nội dung xem trước in đậm khi chưa đọc, trở lại bình thường khi đã đọc.
- Trả lời trích dẫn, sao chép, chuyển tiếp, ghim, biểu cảm và xóa tin nhắn theo quyền.
- Tìm bạn, lời mời kết bạn, hồ sơ, avatar, chặn, báo cáo và QR cá nhân.
- Ảnh JPEG/PNG/WebP, tin nhắn thoại AAC/MP4 và bình chọn 2–6 lựa chọn.
- Gọi thoại/video riêng bằng Agora, tiếp nhận và ghi lịch sử cuộc gọi vào chat.
- Tab Tin có chụp/chọn ảnh, chú thích, bộ lọc, chọn người xem, lịch sử và trả lời qua chat.
- Thông báo tin nhắn/cuộc gọi qua FCM, tùy chọn xem trước và trạng thái hoạt động.

Workspace có thêm giao diện tiếng Việt/Anh trên Android và tệp đính kèm PDF, DOCX, TXT tối đa 8 MiB. Khi phát hành các phần này cần đồng bộ client, backend, rules và MIME của bucket.

Nhóm mới có 3–5 người, tính cả người tạo. Khi đủ 5 người, trưởng nhóm có thể nâng thành cộng đồng, tối đa 10 người. Vai trò gồm `owner` (trưởng nhóm), `admin` (phó nhóm), `member`. Nhóm đầy hiển thị yêu cầu nâng lên cộng đồng. Chỉ trưởng nhóm hoặc thành viên duy nhất còn lại được xóa nhóm. Trưởng nhóm cần chuyển quyền trước khi rời nhóm có nhiều người.

## Cấu trúc và công nghệ

| Đường dẫn | Nội dung |
| --- | --- |
| `app/src/main/java/com/example/utechat/` | Android, chia `feature`, `data`, `domain`, `core`, `app` |
| `app/src/main/res/` | XML, chuỗi hiển thị và tài nguyên Android |
| `web/src/` | React, Firebase client, chat, gọi, bạn bè và Tin |
| `server/call-token/` | Node.js, Firebase Admin, Agora token, FCM và media |
| `shared/` | Tài nguyên chung và bộ lọc ảnh |
| `tests/` | Kiểm thử Firebase Rules |
| `app/src/test/`, `app/src/androidTest/`, `web/test/` | Kiểm thử client |
| `scripts/` | Chuẩn bị và đóng gói bản Android thử nghiệm |

Android dùng Activity/Fragment, ViewModel, LiveData và repository. Firestore listeners đồng bộ chat và signaling cuộc gọi. Agora truyền media. Backend xác minh quyền trước khi cấp token hoặc truy cập kho tệp.

Android Gradle Plugin 9.5.0, Gradle Wrapper 9.6.0 và JDK 25 cho Gradle. Java source/target là Java 11. Android `minSdk 26`, `compileSdk/targetSdk 37`. Phiên bản dependency nằm trong [gradle/libs.versions.toml](gradle/libs.versions.toml), [app/build.gradle.kts](app/build.gradle.kts), [web/package.json](web/package.json) và [server/call-token/package.json](server/call-token/package.json).

## Chuẩn bị môi trường

- Android Studio tương thích cấu hình Gradle, Android SDK 37 và điện thoại Android 8 trở lên hoặc máy ảo.
- Node.js 22 trở lên, npm, Firebase CLI và Java để chạy Emulator Suite.
- Cấu hình Firebase, Agora và Supabase khi kiểm tra các dịch vụ thật.

Trên PowerShell, dùng `npm.cmd` và `firebase.cmd` nếu Windows chặn tệp `.ps1` tương ứng.

## Chạy Android

1. Mở thư mục gốc trong Android Studio và chờ Gradle Sync.
2. Đăng ký Firebase Android app `com.example.utechat`, đặt cấu hình tại `app/google-services.json`.
3. Với Phone Authentication, khai báo SHA-1 và SHA-256 của chứng thư ký app trong Firebase.
4. Cấu hình `agora.local.properties`, chọn thiết bị rồi dùng Run của `app`.

Ví dụ cấu hình client

```properties
utechatAgoraAppId=YOUR_AGORA_APP_ID
utechatAgoraTokenServerUrl=https://utechat-test-backend.onrender.com/api/rtc-token
utechatUseBackendMedia=true
```

Gradle đọc cấu hình này trong `app/build.gradle.kts`. Tham số `-P` được ưu tiên. Điện thoại thật có thể kết nối Android Studio qua USB debugging. Khi thiếu Firebase app configuration, repository mock hỗ trợ một số màn hình với dữ liệu cục bộ. Bạn bè, push, media và Agora cần dịch vụ tương ứng.

Biên dịch mã và chạy unit test trước khi đóng gói

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac :app:testDebugUnitTest
```

## Chạy web và dùng trên iPhone

Từ thư mục gốc

```powershell
npm.cmd --prefix web ci
npm.cmd --prefix web run dev
```

Mở địa chỉ Vite hiển thị. [web/src/firebase-config.json](web/src/firebase-config.json) chứa cấu hình Firebase client và khóa công khai Web Push. Có thể đặt `VITE_FIREBASE_WEB_PUSH_KEY` để thay khóa công khai.

Vite chuyển tiếp `/api` đến Render. Đặt `UTECHAT_DEV_BACKEND` để dùng backend cục bộ. Bản phát hành đọc `VITE_BACKEND_URL` hoặc dùng Render mặc định.

```powershell
$env:UTECHAT_DEV_BACKEND='http://127.0.0.1:8787'
npm.cmd --prefix web run dev
```

Trên iPhone, mở Safari rồi chọn Chia sẻ → Thêm vào Màn hình chính. Web Push cần iOS/iPadOS 16.4 trở lên, mở từ biểu tượng đã thêm và cấp quyền thông báo. Cuộc gọi cần micro/camera và web đang mở. Bản nháp, nền chat và một số tùy chọn hội thoại được lưu theo trình duyệt.

## Số điện thoại và OTP

Đăng nhập dùng số điện thoại và mật khẩu. Đăng ký, đổi hoặc đặt lại mật khẩu xác minh OTP trước khi lưu mật khẩu. Backend bật các endpoint với `UTECHAT_PHONE_AUTH_ENABLED=true`, kiểm tra chứng thực mới và tạo custom session cho UTEChat. Mật khẩu băm nằm trong collection riêng, quyền chat yêu cầu phiên được backend xác nhận.

| Chế độ | Nơi lấy mã | Phạm vi |
| --- | --- | --- |
| Firebase Authentication Emulator | Terminal đang chạy Emulator | Dữ liệu cục bộ |
| Firebase Phone numbers for testing | Mã 6 số đã cấu hình cho số thử | Demo trên bản công khai |

Quản lý số thử tại Firebase Console → Authentication → Sign-in method → Phone → Phone numbers for testing. Giữ mã riêng với nhóm demo. SMS thật cần billing và chịu hạn mức dịch vụ. Tham khảo [Phone Authentication](https://firebase.google.com/docs/auth/web/phone-auth) và [giới hạn Firebase](https://firebase.google.com/docs/auth/limits).

### Demo OTP cục bộ

Các lệnh dưới đây cần các tệp xác thực điện thoại trong workspace hoặc nhánh tương ứng. Cài dependency web/backend trước. Mở ba cửa sổ PowerShell tại thư mục gốc và chạy mỗi khối trong một cửa sổ.

```powershell
firebase.cmd emulators:start --only 'auth,firestore,database' --project demo-utechat
```

```powershell
node server/call-token/test/phone-demo.cjs
```

```powershell
$env:VITE_USE_EMULATORS='1'
$env:UTECHAT_DEV_BACKEND='http://127.0.0.1:8879'
npm.cmd --prefix web run dev
```

Đăng ký bằng số thử `+16505550101`, nhập mã hiện trong terminal Emulator. Backend demo hỗ trợ xác thực và chat cục bộ. Media và Agora cần backend tích hợp tương ứng.

## Cấu hình backend

```powershell
npm.cmd --prefix server/call-token ci
cd server/call-token
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\configure.ps1
npm.cmd start
```

Trình cấu hình hỏi App ID, App Certificate và đường dẫn Firebase Admin JSON rồi tạo `.env` và `agora.local.properties`. Có thể tạo `.env` từ [mẫu cấu hình](server/call-token/.env.example). Với phần điện thoại, bổ sung `UTECHAT_PHONE_AUTH_ENABLED=true`.

| Biến | Vai trò |
| --- | --- |
| `PORT` | Cổng Node.js, mặc định 8787 |
| `FIREBASE_PROJECT_ID` | Firebase project ID |
| `GOOGLE_APPLICATION_CREDENTIALS` | Đường dẫn Firebase Admin JSON trên server |
| `AGORA_APP_ID`, `AGORA_APP_CERTIFICATE` | Cấu hình cấp token Agora |
| `SUPABASE_URL` | URL Supabase |
| `SUPABASE_SECRET_KEY` hoặc `SUPABASE_SERVICE_ROLE_KEY` | Khóa lưu trữ riêng của backend |
| `SUPABASE_STORAGE_BUCKET` | Bucket, mặc định `utechat-media` |
| `UTECHAT_PHONE_AUTH_ENABLED` | Bật endpoint xác thực điện thoại |
| `UTECHAT_LEGACY_EMAIL_ACCESS` | Cờ chuyển đổi tạm thời, để tắt trong chế độ chỉ dùng điện thoại |

Firebase Admin JSON nằm ngoài repo. Backend điện thoại dùng service account để ký custom token. Khi báo `invalid_grant`, kiểm tra khóa, project ID và đồng hồ hệ thống. Điện thoại thật cần HTTPS. Android debug hỗ trợ token server local `http://10.0.2.2:8787/api/rtc-token`.

### API chính

| Endpoint | Công dụng |
| --- | --- |
| `GET /healthz` | Trạng thái cấu hình đã nạp |
| `GET /api/phone-auth/status` | Trạng thái xác thực điện thoại |
| `POST /api/phone-auth/login`, `/register`, `/reset` | Đăng nhập, đăng ký, đặt lại mật khẩu |
| `POST /api/rtc-token` | Token cho thành viên cuộc gọi đã được chấp nhận |
| `POST /api/call-invite`, `/api/message-notify` | Push từ bản ghi đã xác minh |
| `GET/PUT /api/media`, `/api/voice`, `/api/avatar` | Ảnh chat, thoại, avatar |
| `/api/moments`, `/api/moment`, `/api/moment-photo` | Đăng, xem và quản lý Tin |
| `POST /api/group-delete` | Xóa nhóm theo quyền hiện tại |
| `GET/PUT /api/attachment` | Tệp đính kèm khi đã phát hành phần multimedia |

Ảnh chat tối đa 8 MiB, quota upload mặc định 32 MiB/tài khoản/ngày. Tệp PDF, DOCX, TXT tối đa 8 MiB khi backend hỗ trợ. Tin dùng JPEG tối đa 512 KiB. Backend kiểm tra kiểu dữ liệu, quyền thành viên và chống ghi đè object.

## Triển khai Firebase và Render

### Firebase

Cấu hình Phone Authentication, domain hợp lệ và vùng của số thử. Đăng nhập Firebase CLI hoặc dùng Application Default Credentials trước khi triển khai.

```powershell
npm.cmd --prefix web run build
firebase.cmd deploy --only hosting --project utechat-ab9d8
```

Sau khi kiểm thử rules

```powershell
firebase.cmd deploy --only firestore:rules,firestore:indexes,database --project utechat-ab9d8
```

`storage.rules` dùng khi dự án có Firebase Storage bucket. Bản thử sử dụng media qua backend và Supabase. Kiểm tra bucket và gói dịch vụ trước khi triển khai Firebase Storage.

### Render và Supabase

Dùng [render.yaml](render.yaml) làm cấu hình tham chiếu. Dịch vụ Node, Singapore, Free, Root Directory `server/call-token`, Build `npm ci --omit=dev`, Start `npm start`, Health Check `/healthz`.

Lưu Firebase Admin JSON bằng Secret File `firebase-admin.json`, đặt `GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/firebase-admin.json` và các biến máy chủ nêu trên. Chọn đúng commit, đợi Live rồi kiểm tra `/healthz` và `/api/phone-auth/status`. Dịch vụ thử triển khai thủ công, commit đang chạy có thể khác đầu nhánh kết nối.

Supabase dùng bucket `utechat-media` riêng tư, giới hạn object và MIME phù hợp. Khi đổi domain web, cập nhật `WEB_ORIGINS` trong [backend.js](server/call-token/backend.js).

Giữ Firebase Admin JSON, Agora Certificate, khóa Supabase, `.env`, keystore và signing properties ngoài Git và các gói chia sẻ. [`.gitignore`](.gitignore) liệt kê các cấu hình local và thư mục sinh tự động.

## Dữ liệu và phân quyền

| Đường dẫn | Nội dung |
| --- | --- |
| `users/{uid}` | Hồ sơ riêng |
| `publicProfiles/{uid}` | Tên và avatar dùng cho danh bạ/tìm kiếm |
| `friendships/{id}` | Lời mời và quan hệ bạn bè |
| `conversations/{id}` | Thành viên, nhóm, preview và `readStates` |
| `conversations/{id}/members/{uid}` | Vai trò trong nhóm |
| `conversations/{id}/messages/{messageId}` | Nội dung, media, bình chọn, lịch sử gọi |
| `calls/{callId}` | Người tham gia, kênh Agora và trạng thái cuộc gọi |
| `users/{uid}/deviceTokens/{id}` | Token thiết bị, do server đọc |
| Realtime Database `presence/{uid}` | Kết nối, hiển thị hoạt động và `lastSeen` |
| Collection `backend*` | Mật khẩu băm, giới hạn thử, biên nhận và chống trùng, do server quản lý |

ID chat riêng ổn định theo hai UID. ID quan hệ bạn bè dùng cặp UID đã sắp xếp. Tin nhắn giữ cùng ID khi gửi lại. Batch/transaction đồng bộ tin và preview. Mapper Android xử lý thời gian cũ dạng Number, Timestamp hoặc Date ở các trường được hỗ trợ.

Quyền nhóm kiểm tra `participantIds` và `members/{uid}`. Người dùng chỉ sửa mốc đọc, phiếu bình chọn và biểu cảm của mình theo rules. Xóa tài khoản Firebase Authentication không tự xóa hồ sơ, bạn bè, tin nhắn hay media đã tạo. Cần xác định riêng phạm vi dọn từng loại dữ liệu.

## Thông báo, đã đọc và cuộc gọi

Backend gửi FCM sau khi xác minh tin/cuộc gọi và người nhận, chống gửi trùng và xử lý token hết hiệu lực. Event chứa `eventType`, người nhận và ID tin/hội thoại/cuộc gọi. Xem trước nội dung mặc định ẩn, bật trong Hồ sơ theo từng thiết bị.

Android 13 trở lên cần quyền thông báo. Quyền full-screen, force-stop, giới hạn pin và hệ điều hành ảnh hưởng nhận cuộc gọi nền. Web iPhone cần ứng dụng trên màn hình chính và quyền push. Kiểm tra trên thiết bị thật khi mở app, chạy nền và mất mạng.

Presence mặc định ẩn, cập nhật theo vòng đời và kết nối, sử dụng `onDisconnect` và `lastSeen`. Khi bật, rules cho tài khoản hợp lệ đọc trạng thái công khai. Phạm vi hiện tại chưa giới hạn theo quan hệ bạn bè.

Mốc `readStates.{uid}` tăng theo tin thật trong hội thoại, không ghi đè mốc mới hơn. Đang xem lịch sử cũ không tự đánh dấu mọi tin mới là đã đọc. Danh sách chat dựa vào mốc này để định dạng tên và preview.

Cuộc gọi dùng Firestore signaling và Agora media, đổ chuông 45 giây. Kết thúc/từ chối/bận/lỗi/hết hạn ghi transaction vào trạng thái cuộc gọi, tin `call_{callId}` và preview. ID cố định chống lịch sử trùng. Triển khai composite indexes để listener không phải đọc lịch sử rộng qua đường dự phòng.

## Kiểm thử

Cài dependency từ thư mục gốc

```powershell
npm.cmd ci
npm.cmd --prefix web ci
npm.cmd --prefix server/call-token ci
```

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac :app:testDebugUnitTest
npm.cmd --prefix server/call-token test
npm.cmd --prefix web test
npm.cmd --prefix web run build
```

Kiểm thử rules trên dự án demo

```powershell
firebase.cmd emulators:exec --only 'database,firestore,storage' --config firebase.rules-test.json --project demo-utechat 'node --test tests/firebase-rules.test.mjs'
```

Khi checkout có phần OTP, kiểm thử thêm đăng ký/đăng nhập/đặt lại mật khẩu

```powershell
firebase.cmd emulators:exec --only 'auth,firestore' --config firebase.rules-test.json --project demo-utechat 'node server/call-token/test/phone-auth-emulator.cjs'
```

`firebase.rules-test.json` dùng Auth 19099, Firestore 18080, Database 19000 và Storage 19199. Demo web trong `firebase.json` dùng Auth 9099, Firestore 8080, Database 9000.

### Storage Emulator

Đường Firebase Storage của Android debug mặc định dùng `10.0.2.2:19199` khi được chọn. Backend media dùng URL server đã cấu hình. Chọn đúng đường media trước khi thử ảnh.

```powershell
firebase.cmd emulators:start --only 'auth,storage' --config firebase.storage-emulator.json --project demo-utechat
```

`storage.emulator.rules` kiểm tra uploader, metadata, kiểu ảnh và kích thước cho Storage chạy độc lập. Quyền thành viên đầy đủ được kiểm bằng `storage.rules` cùng Firestore Emulator. Điện thoại cùng LAN có thể đặt host qua `utechatStorageEmulatorHost`. Chạy Storage Emulator không tự chuyển Auth/Firestore của app sang dữ liệu cục bộ.

## Đóng gói Android

Chạy các lệnh dưới đây khi cần APK. Dự án tách `arm64-v8a` và `x86_64`. Ký bằng cùng key để các bản cập nhật cài tiếp được.

```powershell
.\gradlew.bat assembleDebug
```

APK nằm trong `app/build/outputs/apk/debug/`, chọn kiến trúc phù hợp. Để tạo bản arm64 cho người thử khi backend sẵn sàng

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\build-tester.ps1 -BackendUrl https://utechat-test-backend.onrender.com
```

Script kiểm tra backend, build, chạy unit test rồi tạo `build/UTEChat-test.apk`. Sao lưu riêng `.private/utechat-tester.jks` và `tester-signing.local.properties`. iPhone truy cập bản web qua Safari.

## Giới hạn và xử lý lỗi

| Hiện tượng | Kiểm tra |
| --- | --- |
| PowerShell chặn npm | Dùng `npm.cmd` |
| OTP thử bị từ chối | Phone, số/mã thử và vùng số đã cấu hình |
| `invalid_grant` | Đồng hồ, project ID, trạng thái khóa Firebase Admin |
| Đăng ký trả 503 | Phiên bản backend, quyền Firebase và phương thức ký custom token |
| Health hoạt động nhưng gọi chưa kết nối | Token Agora, thành viên, micro/camera và mạng hai bên |
| Không thấy cuộc gọi đến | Quyền thông báo, token thiết bị, index và giới hạn nền |
| Không tải media | Bucket, MIME, quota, thành viên và đường backend/Storage |
| Render chậm sau thời gian nghỉ | Chờ dịch vụ Free khởi động lại |
| Giao diện web cũ | Tải lại trang hoặc mở lại ứng dụng web |

Render Free có thời gian nghỉ và giới hạn tài nguyên. Timer cuộc gọi được khôi phục khi server hoạt động lại, nên lịch sử hết hạn có thể ghi trễ. Outbox thông báo thử lại khi app mở nhưng không bảo đảm giao ngay nếu process bị dừng sau khi ghi tin.

Media cũ trên Firebase Storage hoặc Emulator chưa tự chuyển sang Supabase. Gọi nhóm, chia sẻ màn hình và mã hóa đầu cuối chưa triển khai. Micro/loa, camera, Bluetooth, mạng yếu và thông báo nền cần thử trên thiết bị thật. Theo dõi quota trên dashboard trước khi tăng số người thử.
