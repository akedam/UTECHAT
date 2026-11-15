# UTEChat Web

Ứng dụng web dùng chung Firebase Authentication, Firestore, Realtime Database và backend gọi điện với UTEChat Android. Firebase Hosting phục vụ giao diện tĩnh. Render Free cấp token Agora, gửi thông báo và xử lý tệp riêng tư.

## Chạy trên Windows

```powershell
cd web
npm.cmd ci
npm.cmd run dev
```

Mở địa chỉ do Vite hiển thị. Vite chuyển tiếp yêu cầu API tới backend Render. Để dùng một backend cục bộ, đặt `UTECHAT_DEV_BACKEND` thành địa chỉ của backend trước khi chạy Vite.

Để thử luồng đăng ký và chat với dữ liệu cục bộ, chạy `firebase.cmd emulators:start --only auth,firestore,database --project demo-utechat` từ thư mục gốc. Sau đó đặt `VITE_USE_EMULATORS=1` rồi khởi động Vite. Các yêu cầu ảnh, thoại và Agora cần một backend riêng kết nối cùng bộ Emulator.

## Build và cập nhật

```powershell
npm.cmd --prefix web ci
npm.cmd --prefix web run build
firebase.cmd deploy --only hosting --project utechat-ab9d8
```

`src/firebase-config.json` gồm thông tin công khai cho Firebase SDK. Dùng `VITE_FIREBASE_WEB_PUSH_KEY` nếu cấu hình khóa **công khai** từ Firebase Console, mục **Cloud Messaging → Web Push certificates**. Firebase Admin JSON, Agora Certificate và khóa lưu trữ chỉ thuộc backend, tuyệt đối không đưa vào các biến `VITE_*`.

Backend Render chỉ chấp nhận truy cập bằng trình duyệt từ hai miền Firebase Hosting của dự án. Nếu đổi miền, cập nhật `WEB_ORIGINS` trong `server/call-token/backend.js`. Truy cập từ APK tiếp tục dùng API và Firebase ID token như trước.

## Dùng trên iPhone

Mở UTEChat bằng Safari, chọn **Chia sẻ → Thêm vào Màn hình chính**. Để nhận Web Push, mở từ biểu tượng đã thêm và bật thông báo ở mục **Hồ sơ** trên iOS/iPadOS 16.4 trở lên. Khi có thông báo cuộc gọi, chạm để mở UTEChat và trả lời. Gọi thoại và video cần quyền micro/camera và hoạt động khi UTEChat đang mở. Tin nhắn thoại dùng AAC trong tệp MP4 để tương thích với Android.

Bản nháp, nền chat và ghim hội thoại là cài đặt trong trình duyệt hiện tại. Tin nhắn, ghim tin nhắn, nhóm và hồ sơ đồng bộ qua Firebase.
