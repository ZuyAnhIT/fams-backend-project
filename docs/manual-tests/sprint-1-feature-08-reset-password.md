# Kịch bản test thủ công — #8 Đặt lại mật khẩu

Áp dụng cho cả 2 giao diện. Cần chạy sau/song song với kịch bản #7 (Quên mật khẩu) để có token
thật — màn "Đặt lại mật khẩu" chỉ vào được qua link trong email (hoặc dán token thủ công vào URL
khi test do quota email dev có thể đang hết).

⚠️ **Gap đã biết** (xác nhận lại khi viết kịch bản này): `PasswordResetService.resetPassword()`
**không** gọi `auditLogService.record(...)` — Acceptance Criteria yêu cầu "ghi audit" nhưng chưa
có. Case 6 dưới đây xác nhận lại gap này.

---

## A. Test trên cả 2 giao diện

### 1. Token không hợp lệ/đã hết hạn
- Mở màn Đặt lại mật khẩu với 1 token giả (`?token=token-khong-ton-tai`), nhập mật khẩu mới, gửi.
- **Kỳ vọng:** lỗi "Token không hợp lệ hoặc đã hết hạn" (tiếng Việt), không đổi được mật khẩu.

### 2. Mật khẩu mới không đạt policy
- Dùng token hợp lệ (lấy theo hướng dẫn kịch bản #7 case 2), nhập mật khẩu mới `123456` (không
  đạt policy).
- **Kỳ vọng:** lỗi validate rõ ràng, không gọi API hoặc API trả 400 với message đúng.

### 3. Đặt lại mật khẩu thành công (happy path)
- Dùng token hợp lệ, nhập mật khẩu mới đạt policy (VD: `NewP@ss99`), gửi.
- **Kỳ vọng:** thành công, chuyển về màn đăng nhập kèm thông báo. Đăng nhập lại bằng mật khẩu MỚI
  phải vào được; đăng nhập bằng mật khẩu CŨ phải bị từ chối.

### 4. Token bị dùng lại (replay) — chỉ dùng được 1 lần
- Ngay sau case 3, thử dùng lại **đúng token đó** một lần nữa với 1 mật khẩu khác.
- **Kỳ vọng:** lỗi "Token không hợp lệ hoặc đã hết hạn" — token đã bị xóa khỏi Redis sau lần dùng
  đầu tiên (`redis.delete(key)` trong code).

### 5. Mọi phiên đăng nhập cũ bị revoke sau khi reset
- Trước case 3, đăng nhập sẵn trên 1 thiết bị khác (lưu lại access/refresh token của thiết bị đó).
- Sau khi reset mật khẩu thành công (case 3), thử dùng refresh token của thiết bị kia:
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/auth/refresh-token \
    -H "Content-Type: application/json" -d '{"refreshToken":"<token thiết bị kia>"}' -w "\nHTTP:%{http_code}\n"
  ```
- **Kỳ vọng:** 401 — tất cả phiên cũ bị revoke, đúng theo thiết kế bảo mật (ai đó chiếm được mật
  khẩu cũ và đang có phiên đăng nhập sẽ bị đá ra khi chủ tài khoản reset lại).

### 6. Tài khoản đang bị khóa (lockout) — reset có tự mở khóa không
- Làm tài khoản test bị khóa trước (sai mật khẩu 5 lần liên tiếp, xem kịch bản #1 case 4).
- Dùng chức năng Quên mật khẩu + Đặt lại mật khẩu cho đúng tài khoản đó.
- **Kỳ vọng:** sau khi đặt lại mật khẩu thành công, tài khoản được **mở khóa ngay lập tức** (không
  cần đợi hết thời gian khóa) — đăng nhập lại bằng mật khẩu mới phải vào được ngay.

### 7. Kiểm tra audit log (xác nhận gap đã nêu ở đầu file)
- Sau case 3, gọi (đăng nhập lại lấy token mới trước):
  ```bash
  curl -s "http://localhost:8080/api/v1/audit-logs?userId=<id user vừa reset>&action=RESET_PASSWORD" \
    -H "Authorization: Bearer <token>"
  ```
- **Kỳ vọng theo code hiện tại:** trả về rỗng — xác nhận đúng gap. Báo lại nếu bạn muốn bổ sung
  audit log cho hành động này (khuyến nghị, vì đổi mật khẩu là hành động bảo mật quan trọng).

---

## Ghi chú
Toàn bộ case ở file này **chưa** được tôi tự test qua Playwright — cần chạy nối tiếp với kịch bản
#7 để có token thật. Case 5/6 đặc biệt quan trọng vì test đúng 2 tác dụng phụ bảo mật quan trọng
nhất của tính năng (revoke session cũ + tự mở khóa), không chỉ mỗi "đổi được mật khẩu".
