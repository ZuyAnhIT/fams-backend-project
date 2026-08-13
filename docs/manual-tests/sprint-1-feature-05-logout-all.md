# Kịch bản test thủ công — #5 Đăng xuất khỏi tất cả thiết bị

Áp dụng cho cả 2 giao diện: Web Admin và Mobile App.

⚠️ **Gap đã biết** (từ audit `docs/BACKLOG.md`, đã xác nhận lại còn tồn tại khi viết kịch bản
này): `LogoutService.logoutAll()` revoke token đúng nhưng **không** gọi
`auditLogService.record(...)` — Acceptance Criteria yêu cầu rõ "giữ lại audit" cho hành động này
(khác #4, vì đây là hành động nhạy cảm hơn — dùng khi nghi ngờ lộ tài khoản, cần có dấu vết).
Case 4 dưới đây sẽ cho thấy rõ: `GET /audit-logs` sẽ **không** có bản ghi `LOGOUT_ALL` nào — xác
nhận lại đúng vậy rồi báo tôi để bổ sung.

---

## A. Test trên cả 2 giao diện

### 1. Đăng xuất tất cả thiết bị — happy path
- Đăng nhập cùng `admin@fams.com` trên **ít nhất 2 thiết bị/trình duyệt** (VD: Chrome thường +
  Chrome ẩn danh, hoặc Web + Mobile App).
- Ở thiết bị A, vào Cài đặt bảo mật → tìm nút "Đăng xuất khỏi tất cả thiết bị" (khác nút đăng xuất
  thường).
- **Kỳ vọng:** thiết bị A quay về màn login ngay (giống đăng xuất thường).

### 2. Thiết bị khác bị đá ra thật
- Ngay sau case 1, quay lại thiết bị B (vẫn đang mở, chưa reload) và thử thao tác bất kỳ cần gọi
  API (VD: refresh danh sách, chuyển trang).
- **Kỳ vọng:** thiết bị B bị đá về màn login trong vòng vài giây (do access token bị vô hiệu qua
  cơ chế `jwt:user_revoke:` timestamp, không cần đợi hết hạn tự nhiên), kèm thông báo phù hợp
  ("Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại") thay vì lỗi trắng/treo màn hình.

### 3. Refresh token của thiết bị khác cũng bị revoke
- Trước case 1, lưu lại refresh token của thiết bị B.
- Sau khi đăng xuất tất cả ở thiết bị A, gọi thử API refresh bằng token của thiết bị B:
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/auth/refresh-token \
    -H "Content-Type: application/json" -d '{"refreshToken":"<token thiết bị B>"}' -w "\nHTTP:%{http_code}\n"
  ```
- **Kỳ vọng:** 401, không cấp access token mới.

### 4. Kiểm tra audit log (case xác nhận gap đã nêu ở đầu file)
- Sau case 1, gọi (bằng tài khoản admin, hoặc đăng nhập lại rồi xem qua giao diện Audit Log nếu
  có màn hình đó):
  ```bash
  # cần access token mới sau khi đăng nhập lại
  curl -s http://localhost:8080/api/v1/audit-logs?userId=<id của admin>&action=LOGOUT_ALL \
    -H "Authorization: Bearer <token>"
  ```
- **Kỳ vọng theo code hiện tại:** trả về rỗng — xác nhận đúng gap đã nêu. Nếu bạn muốn có audit
  trail cho hành động này (khuyến nghị, vì đây là hành động bảo mật nhạy cảm), báo tôi để bổ sung
  `auditLogService.record(...)` vào `LogoutService.logoutAll()`.

---

## Ghi chú
Toàn bộ case ở file này **chưa** được tôi tự test qua Playwright — cần môi trường đa thiết bị
thật để test case 2/3 (2 phiên đăng nhập song song), tôi không mô phỏng được trong 1 trình duyệt
headless đơn giản mà không có công cụ multi-context. Case 4 quan trọng nhất — xác nhận đúng gap
rồi báo lại để quyết định có sửa không.
