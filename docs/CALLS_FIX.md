# Sửa nhận cuộc gọi và lịch sử

Lỗi quan sát được trên máy nhận: truy vấn `calls` trả về `FAILED_PRECONDITION`
vì chỉ mục tổng hợp chưa tồn tại. Trước đây lỗi listener bị bỏ qua.

Client vẫn ưu tiên truy vấn có chỉ mục trong `firestore.indexes.json`. Nếu chỉ mục
thiếu/đang xây dựng, client chuyển sang truy vấn một trường `participants array-contains`
và lọc người nhận, trạng thái, hạn đổ chuông trên thiết bị. Cách dự phòng giữ nguyên
quyền đọc chỉ cho người tham gia; không giới hạn kết quả trước khi lọc để tránh bỏ
sót cuộc gọi mới. Nó đọc cả lịch sử cuộc gọi của người dùng, nên production nên
triển khai chỉ mục để tránh tăng số lượt đọc.

Khi kết thúc/từ chối/bận/hết thời gian chờ/lỗi, transaction ghi cùng lúc:

- trạng thái cuối vào `calls/{callId}`;
- tin nhắn văn bản `conversations/{id}/messages/call_{callId}`;
- bản xem trước mới nhất của hội thoại.

ID cố định và kiểm tra trạng thái cuối trong transaction ngăn ghi trùng khi hai
bên kết thúc đồng thời hoặc thử lại. Nội dung phân biệt cuộc gọi thoại/video,
nhỡ, từ chối, lỗi và kết thúc. Thông báo dùng schema tin nhắn văn bản hiện có,
không cần nới Firestore Rules. Không tính thời lượng vì nhận cuộc gọi chưa đồng
nghĩa hai thiết bị đã kết nối RTC.

## Kiểm tra

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest
& "$env:APPDATA\npm\firebase.cmd" emulators:exec --only database,firestore,storage --config firebase.rules-test.json --project demo-utechat 'node --test --test-name-pattern=call tests/firebase-rules.test.mjs'
```

## Điều kiện còn cần

Giao diện gọi đã chuyển sang nền đen, avatar tròn, các nút micro/camera/loa/
đổi camera/kết thúc dạng tròn và khung video cục bộ ở góc. Camera xem trước dùng
Agora sau khi cấp quyền và cấu hình App ID; không tham gia kênh hoặc phát media
trước khi bên kia nhận. Tin lịch sử được hiển thị bằng thẻ tím, phân biệt thoại/
video và có nút gọi lại đúng loại, kể cả các bản ghi văn bản `call_…` trước đây.

Chuẩn bị cấu hình bằng `server/call-token/configure.ps1`. Script chỉ chạy khi có
App ID/Primary Certificate thật và file Firebase Admin hợp lệ. Nó tạo hai file
local đã được Git ignore; certificate không được đọc bởi Gradle hoặc đóng vào APK.
Không thể kích hoạt hoặc kiểm chứng truyền âm thanh/video trước khi chủ tài khoản
đăng nhập/tạo dự án Agora và cung cấp quyền Firebase Admin cho token server.

Kiểm tra thực tế trên hai emulator: máy nhận hiện tên người gọi và Trả lời/Từ chối;
kết thúc bên gọi tạo tin lịch sử trong chat và cập nhật bản xem trước bên nhận.
Hai kiểm thử Firebase cuộc gọi và bốn kiểm thử Java mới đều đạt. Lần chạy toàn bộ
Firebase suite còn lỗi ngoài phạm vi cuộc gọi ở `group image access requires a
current member document` (fixture hiện cấp sẵn quyền thành viên cho tài khoản bị
kỳ vọng từ chối).

Hai thiết bị cần đăng nhập, có mạng và đang mở ứng dụng để nhận bằng listener.
Nhận khi app bị đóng cần backend push FCM tin cậy; bản sửa này không cung cấp
dịch vụ push đó. Audio/video cần `utechatAgoraAppId` và
`utechatAgoraTokenServerUrl`; App Certificate chỉ ở server. Xem
[server token](../server/call-token/README.md). Nếu chưa cấu hình, nhận/từ chối/
kết thúc và lịch sử vẫn hoạt động, còn nhận cuộc gọi sẽ kết thúc với lỗi dịch vụ
chưa cấu hình khi bắt đầu truyền media.
