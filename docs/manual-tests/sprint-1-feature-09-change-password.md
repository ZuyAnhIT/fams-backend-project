# Kịch bản test thủ công — #9 Đổi mật khẩu

Áp dụng cho cả 2 giao diện. Khác #8 (đặt lại mật khẩu qua token khi quên) — đây là đổi mật khẩu
**khi đang đăng nhập**, cần nhập đúng mật khẩu cũ.

⚠️ **Gap đã biết** (xác nhận lại khi viết kịch bản này): `ChangePasswordService.changePassword()`
**không** gọi `auditLogService.record(...)` — Acceptance Criteria yêu cầu "ghi audit". Case 6 xác
nhận lại gap này.

---

## A. Test trên cả 2 giao diện

### 1. Mật khẩu cũ sai
- Đăng nhập `admin@fams.com`, vào Cài đặt → Đổi mật khẩu, nhập sai mật khẩu hiện tại.
- **Kỳ vọng:** lỗi "Mật khẩu hiện tại không đúng" (tiếng Việt), không đổi được.

### 2. Mật khẩu mới không đạt policy
- Nhập đúng mật khẩu cũ, mật khẩu mới không đạt policy (VD: `12345678`).
- **Kỳ vọng:** lỗi validate rõ ràng.

### 3. Đổi mật khẩu thành công (happy path)
- Nhập đúng mật khẩu cũ + mật khẩu mới hợp lệ, xác nhận.
- **Kỳ vọng:** thành công, có thông báo rõ ràng. Đăng nhập lại bằng mật khẩu MỚI phải vào được;
  mật khẩu CŨ phải bị từ chối.

### 4. Request hiện tại vẫn dùng được sau khi đổi (không tự đăng xuất giữa chừng)
- Ngay sau khi đổi mật khẩu thành công ở case 3 (chưa reload trang/app), thử thao tác 1 hành động
  bất kỳ cần gọi API (VD: mở lại trang hồ sơ).
- **Kỳ vọng theo code hiện tại:** access token của chính request vừa đổi mật khẩu **bị blacklist
  ngay** (xem `ChangePasswordService` — tự hủy token của chính nó) → hành động tiếp theo phải bị
  đá về màn đăng nhập lại, **không** được coi là lỗi/bug — đây là chủ đích bảo mật (đổi mật khẩu
  xong phải đăng nhập lại bằng mật khẩu mới). Xác nhận UI xử lý mượt (chuyển màn login kèm thông
  báo phù hợp), không phải màn trắng/lỗi network chung chung.

### 5. Các thiết bị khác cũng bị đăng xuất
- Trước case 3, đăng nhập sẵn `admin@fams.com` trên 1 thiết bị/trình duyệt khác, lưu lại refresh
  token của thiết bị đó.
- Sau khi đổi mật khẩu ở case 3, thử dùng refresh token của thiết bị kia:
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/auth/refresh-token \
    -H "Content-Type: application/json" -d '{"refreshToken":"<token thiết bị kia>"}' -w "\nHTTP:%{http_code}\n"
  ```
- **Kỳ vọng:** 401 — mọi phiên khác bị revoke ngay khi đổi mật khẩu (đúng thiết kế bảo mật, giống
  #8 case 5).

### 6. Kiểm tra audit log (xác nhận gap đã nêu ở đầu file)
- Sau case 3, đăng nhập lại lấy token mới, gọi:
  ```bash
  curl -s "http://localhost:8080/api/v1/audit-logs?userId=<id user>&action=CHANGE_PASSWORD" \
    -H "Authorization: Bearer <token>"
  ```
- **Kỳ vọng theo code hiện tại:** rỗng — xác nhận đúng gap, báo lại nếu muốn bổ sung.

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright. Case 4/5 dễ bị nhầm là "bug" nếu không đọc kỹ
— đọc rõ phần "Kỳ vọng" trước khi báo lỗi.
