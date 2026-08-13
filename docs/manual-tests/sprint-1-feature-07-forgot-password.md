# Kịch bản test thủ công — #7 Quên mật khẩu

Áp dụng cho cả 2 giao diện. Case happy-path cơ bản đã test nhanh ở kịch bản #1 (mục D, case 13) —
file này đào sâu thêm các case bảo mật/biên mà #1 chưa cover.

---

## A. Test trên cả 2 giao diện

### 1. Email không tồn tại — không lộ thông tin
- Vào "Quên mật khẩu", nhập 1 email chắc chắn không tồn tại (VD: `khong-ton-tai-12345@fams.com`).
- **Kỳ vọng:** vẫn hiện thông báo thành công giống hệt case email có tồn tại ("Nếu email tồn tại,
  chúng tôi đã gửi hướng dẫn...") — **không** được báo "Email không tồn tại" (rò rỉ thông tin tài
  khoản nào có đăng ký).

### 2. Email tồn tại — happy path
- Nhập `admin@fams.com`, bấm gửi.
- **Kỳ vọng:** thông báo thành công (giống case 1), phản hồi nhanh. Lấy token thật từ Redis để
  test tiếp (dùng cho kịch bản #8):
  ```bash
  docker exec fams-redis redis-cli KEYS "pwd:reset:token:*"
  # lấy value tương ứng để xác nhận đúng user:
  docker exec fams-redis redis-cli GET "pwd:reset:token:<token>"
  ```

### 3. Giới hạn tần suất (rate limit)
- Gửi yêu cầu quên mật khẩu cho cùng 1 email liên tục hơn 3 lần trong 10 phút.
- **Kỳ vọng:** UI vẫn hiện thông báo thành công **cho tất cả các lần** (không lộ rate-limit ra
  ngoài, đúng thiết kế "không tiết lộ" — xem code `PasswordResetService.forgotPassword`), nhưng
  thực tế từ lần thứ 4 backend sẽ **không** tạo token mới/gửi email mới nữa. Xác nhận bằng cách
  kiểm tra không có token Redis mới được tạo ở lần gọi thứ 4-5:
  ```bash
  docker exec fams-redis redis-cli KEYS "pwd:reset:token:*" | wc -l
  # gọi lại forgot-password lần nữa, đếm lại — số lượng không tăng nếu đã vượt rate limit
  ```

### 4. Token hết hạn (1 giờ)
- Không cần chờ thật 1 giờ — chỉnh TTL token bằng tay để mô phỏng hết hạn:
  ```bash
  docker exec fams-redis redis-cli EXPIRE "pwd:reset:token:<token lấy ở case 2>" 1
  ```
  đợi 2 giây rồi thử dùng token đó ở màn "Đặt lại mật khẩu" (xem kịch bản #8, case 1) → phải báo
  lỗi "token không hợp lệ hoặc đã hết hạn".

### 5. Số điện thoại (nếu form hỗ trợ quên mật khẩu bằng SĐT)
- Kiểm tra xem form "Quên mật khẩu" có tùy chọn nhập SĐT thay email không.
- **Kỳ vọng:** nếu UI hiện tùy chọn SĐT nhưng backend chỉ có field `email` trong
  `ForgotPasswordRequest` (xác nhận: `grep -n "class ForgotPasswordRequest" -A10` không thấy field
  phone) → đây là **bug UI/BE lệch nhau**, báo lại ngay nếu thấy tùy chọn SĐT tồn tại trên giao
  diện.

---

## Ghi chú
Case 1-4 mô tả đúng theo code hiện tại (`PasswordResetService.forgotPassword`), chưa được tôi tự
test qua Playwright. Case 5 là kiểm tra khớp hợp đồng API giữa 2 frontend và backend — quan trọng
vì Acceptance Criteria gốc có nhắc "email/phone" nhưng backend hiện chỉ nhận email.
