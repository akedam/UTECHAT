# Trạng thái chưa đọc và đã đọc

- Danh sách hội thoại dùng listener trang đầu để nhận thay đổi theo thời gian thực.
- Tên và nội dung xem trước cùng in đậm khi tin mới nhất đến từ người khác và chưa
  nằm trong mốc đọc của tài khoản hiện tại. Tin tự gửi không tự tạo trạng thái chưa đọc.
- Khi màn hình chat đang hoạt động và tin cuối thực sự hiển thị, app lưu mốc đọc.
  Tin mới đến khi đang xem phần lịch sử cũ không tự bị đánh dấu đã đọc.
- Cache được tách theo tài khoản trên thiết bị, giúp đổi kiểu chữ ngay cả khi mất
  mạng. Transaction đồng bộ `readStates.{uid}` khi Firebase cho phép; mốc cũ không
  được ghi đè mốc mới hơn.
- Firestore Rules mới chỉ cho thành viên sửa mốc của chính mình, đối chiếu ID và
  thời gian với tin thật trong cùng hội thoại, giữ nguyên mốc của người khác.

## Kiểm tra

15 kiểm thử Java đạt, gồm 4 kiểm thử trạng thái chưa đọc. Kiểm thử Firebase Emulator
cho mốc đọc và 2 kiểm thử cuộc gọi đều đạt. Đã quan sát trên emulator: tên và preview
in đậm trước khi mở chat; sau khi mở đọc và quay lại thì chữ trở về bình thường.

## Triển khai

Người dùng đã chấp thuận triển khai cụ thể sau bước duyệt ban đầu. Ngày 12/11/2026,
`firestore.rules` đã được Firebase compile và release thành công vào `utechat-ab9d8`.
Sau khi mở đọc hội thoại trên emulator, kiểm tra chỉ-đọc trường `readStates` qua
Firebase CLI đã xác nhận một mốc đọc hợp lệ được app đồng bộ lên Firestore.
Không thay đổi nội dung tin nhắn hoặc cấu hình các dịch vụ Firebase khác.
