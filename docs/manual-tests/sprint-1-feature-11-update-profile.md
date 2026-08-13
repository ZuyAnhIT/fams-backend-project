# Kịch bản test thủ công — #11 Cập nhật hồ sơ cá nhân

Áp dụng cho cả 2 giao diện.

⚠️ **Gap đã biết** (xác nhận lại khi viết kịch bản này — vẫn còn nguyên): `UserProfileService`
**không** gọi `auditLogService.record(...)` khi cập nhật hồ sơ. Case 6 xác nhận lại gap này.

---

## A. Test trên cả 2 giao diện

### 1. Cập nhật họ tên — happy path
- Vào Cài đặt hồ sơ, đổi họ tên, lưu.
- **Kỳ vọng:** thành công, tên mới hiển thị ngay lập tức (không cần reload) ở mọi nơi có hiển thị
  tên (header, avatar dropdown, v.v.).

### 2. Cập nhật avatar
- Upload 1 ảnh avatar mới.
- **Kỳ vọng:** upload thành công, ảnh hiển thị đúng ngay, không bị vỡ layout/tỉ lệ. Thử upload
  file không phải ảnh (VD: `.pdf`) → phải bị từ chối với thông báo rõ ràng, không crash.

### 3. Đổi số điện thoại — cần xác thực lại
- Đổi số điện thoại sang 1 số mới (định dạng E.164 hợp lệ).
- **Kỳ vọng:** không đổi ngay lập tức mà yêu cầu xác thực OTP trước (theo endpoint
  `/profile/phone/request-change` + `/profile/phone/confirm-change`) — nhập đúng OTP mới đổi
  thành công; `phone_verified` reset về chờ xác thực cho tới khi OTP đúng.

### 4. Đổi email — cần xác thực qua link
- Đổi email sang 1 email mới.
- **Kỳ vọng:** không đổi ngay — gửi link xác thực đến email MỚI (endpoint
  `/profile/email/request-change` + `/profile/email/confirm-change`), email cũ vẫn dùng để đăng
  nhập được cho tới khi bấm xác nhận trong email mới. Kiểm tra log nếu quota email dev đang hết:
  ```
  docker logs fams-api --tail 30 | grep -i "email change\|confirm-change"
  ```

### 5. Nhập số điện thoại/email trùng với tài khoản khác
- Thử đổi phone/email sang giá trị đã thuộc về 1 tài khoản khác đang tồn tại.
- **Kỳ vọng:** bị từ chối, lỗi rõ ràng "đã được sử dụng bởi tài khoản khác".

### 6. Kiểm tra audit log (xác nhận gap đã nêu ở đầu file)
- Sau case 1, gọi:
  ```bash
  curl -s "http://localhost:8080/api/v1/audit-logs?userId=<id user>&action=UPDATE_PROFILE" \
    -H "Authorization: Bearer <token>"
  ```
- **Kỳ vọng theo code hiện tại:** rỗng — xác nhận đúng gap, báo lại nếu muốn bổ sung.

---

## Ghi chú
Case 3/4 (đổi phone/email) dùng OTP/email riêng, không liên quan Firebase (#2) hay OTP đăng ký
(#6) — đây là 2 luồng OTP/email-verify riêng cho việc đổi thông tin khi đã đăng nhập. Toàn bộ case
chưa được tôi tự test qua Playwright.
