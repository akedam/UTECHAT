# Giai đoạn 8: Notifications và presence

## Đã triển khai trong ứng dụng

- Android 13+ xin `POST_NOTIFICATIONS` bằng lời giải thích trước; channel tin nhắn được tạo sẵn.
- `FirebaseMessagingService` nhận **data-only** event `eventType=message`, bỏ qua sự kiện trùng theo `conversationId:messageId`, ẩn notification nếu người dùng đang ở đúng chat, và mở `MainActivity` để điều hướng tới đúng `ChatActivity` khi nhấn.
- Preview nội dung thông báo mặc định tắt; công tắc trong Hồ sơ bật/tắt preview cục bộ. Notification mặc định chỉ nói có tin nhắn mới và dùng visibility private trên màn hình khóa.
- FCM token được ghi vào `users/{uid}/deviceTokens/{sha256(token)}`; người dùng không thể đọc token từ client, chỉ chủ tài khoản được ghi/xóa. Token và hash không ghi vào log.
- Presence dùng một node kết nối riêng mỗi process trong Realtime Database. `onDisconnect` xóa node và ghi `lastSeen` theo server timestamp; khi app chuyển nền, client cũng gỡ kết nối. Không heartbeat định kỳ.
- Presence mặc định ẩn. Khi bật, mọi tài khoản UTEChat đã đăng nhập có thể đọc trạng thái; khi tắt, chỉ chính chủ đọc được. Phạm vi hiện tại chưa giới hạn riêng theo quan hệ bạn bè.

## Giới hạn gửi push

Repo trước giai đoạn này chỉ có rules test, chưa có `functions/`, Cloud Functions SDK hoặc server sender. Vì vậy G8 chỉ hoàn thiện client nhận và đăng ký token; ứng dụng **chưa tự gửi push khi có message mới**. Không gửi FCM từ Android client vì sẽ để lộ quyền server.

Sender về sau cần là trusted server/Cloud Function trigger khi tạo message. Contract data-only tối thiểu:

```text
eventType=message
recipientUid=<target user UID>
messageId=<message document ID>
conversationId=<conversation ID>
conversationType=direct|group
conversationTitle=<display title>
senderName=<display name>
preview=<optional message text; client hides by default>
```

Sender phải bỏ qua chính người gửi, lấy token của thành viên hiện tại, dọn token đã bị FCM thu hồi, và không gửi notification payload vì Firebase/Android có thể tự hiển thị payload đó khi app nền, bỏ qua bộ lọc preview của ứng dụng. Không đặt service-account key/server key trong APK hoặc repo. Firebase yêu cầu Blaze để deploy Cloud Functions; do repo chưa có functions infrastructure và mục tiêu đang dùng Spark, server sender chưa được thêm.

## Cấu hình Firebase Console

### FCM

1. Xác nhận Android app `com.example.utechat` đã đăng ký trong project và `app/google-services.json` còn đúng.
2. Cloud Messaging client/token không cần server key trong app. Khi có sender tin cậy, bật/kiểm tra FCM HTTP v1 API và cấu hình sender credentials trong môi trường server.
3. Để gửi push tự động từ Firebase Functions, cần bổ sung Functions project và chuyển billing sang Blaze. Chưa cần chuyển gói để build hoặc dùng client notification handlers.

### Realtime Database presence

1. Trong Firebase Console → Realtime Database, tạo database cho project; chọn khu vực phù hợp (có thể chọn Singapore) và không bật public/test rules.
2. Tải lại `google-services.json` sau khi tạo database, thay file local đã bị Git ignore tại `app/google-services.json`; kiểm tra file mới có database URL.
3. Deploy rule đã kiểm thử:

```powershell
& "$env:APPDATA\npm\firebase.cmd" deploy --only firestore:rules,database --project utechat-ab9d8
```

4. Rebuild APK. Nếu chưa có URL, công tắc presence trong Hồ sơ bị vô hiệu hóa thay vì giả vờ hoạt động.

Realtime Database có hạn mức Spark miễn phí; presence chỉ tạo/xóa connection khi trạng thái kết nối hoặc vòng đời app đổi, không ghi theo chu kỳ. Quyền đọc opt-in hiện cho mọi tài khoản đã đăng nhập, không chỉ bạn bè.

## Kiểm tra

Chạy rules tests cục bộ:

```powershell
& "$env:APPDATA\npm\firebase.cmd" emulators:exec --only database,firestore,storage --config firebase.rules-test.json --project demo-utechat 'node --test tests/firebase-rules.test.mjs'
```

Để kiểm notification deeplink bằng thiết bị Android, cần một sender data-only hoặc gửi FCM test data có đủ contract fields tới token của thiết bị; sau đó thử foreground, background và process đã đóng. Android 13+ cũng cần chấp nhận quyền. Kiểm thử thiết bị chưa chạy trong lượt triển khai này vì không có Android emulator/điện thoại kết nối.
