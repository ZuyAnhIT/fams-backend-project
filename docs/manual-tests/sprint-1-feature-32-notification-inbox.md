# Kịch bản test thủ công — #32 Tạo notification in-app cơ bản

**Nền tảng: Backend, Web Admin, Mobile App.**

ℹ️ Audit gốc (07-22) ghi ✅ ĐÃ XONG, không có gap. Đã xác nhận lại qua code hiện tại: API
`GET /tenants/{tenantId}/notifications` (danh sách), `PATCH .../notifications/{id}/read`,
`PATCH .../notifications/read` (đánh dấu nhiều), `PATCH .../notifications/read-all` đều hoạt
động. Module này **không ghi audit log** (không có gọi `AuditLogService` ở đâu trong
`NotificationService`) — nhưng đây chưa từng là gap được nêu cho #32 (không nằm trong Acceptance
Criteria gốc: "Tạo notification channel=in_app; user xem unread count; đánh dấu đã đọc; ghi
created_at/read_at" — không yêu cầu audit), nên không tính là fail nếu xác nhận đúng thực tế này.

---

## A. Test trên Web Admin

### 1. Xem chuông thông báo — happy path
- Đăng nhập bất kỳ tài khoản nào có ít nhất 1 notification chưa đọc (VD: vừa được gán role, hoặc
  vừa nhận invite).
- **Kỳ vọng:** icon chuông ở header hiện badge số lượng chưa đọc; bấm vào mở popover danh sách,
  item chưa đọc có nền màu khác (highlight) để phân biệt với đã đọc.

### 2. Đánh dấu đã đọc khi click 1 item
- Click vào 1 notification chưa đọc trong popover.
- **Kỳ vọng:** item chuyển sang trạng thái đã đọc ngay (hết highlight), badge số lượng giảm 1,
  và nếu notification có link liên quan (VD: link tới role/employee) thì điều hướng đúng tới đó.

### 3. Đánh dấu tất cả đã đọc
- Với còn ít nhất 1 item chưa đọc, bấm "Đánh dấu tất cả đã đọc".
- **Kỳ vọng:** toàn bộ item chuyển đã đọc, badge về 0.

### 4. Đánh dấu tất cả khi đã hết chưa đọc (idempotent)
- Bấm lại "Đánh dấu tất cả đã đọc" lần nữa khi không còn gì chưa đọc.
- **Kỳ vọng:** không lỗi, không có gì thay đổi thêm — xác nhận hành vi idempotent.

### 5. Phân trang danh sách thông báo (nếu có nhiều)
- Nếu tài khoản có nhiều notification, cuộn/chuyển trang trong popover hoặc màn danh sách đầy đủ
  (nếu có).
- **Kỳ vọng:** tải đúng, không trùng lặp, không mất item.

---

## B. Test trên Mobile App

### 6. Đồng bộ trạng thái đã đọc giữa Web và App
- Đánh dấu đã đọc 1 notification trên Web Admin, sau đó mở màn thông báo trên Mobile App (cùng
  tài khoản).
- **Kỳ vọng:** trạng thái đã đọc đồng bộ đúng (không hiện lại là chưa đọc).

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright/thiết bị thật. Đây là tính năng nền (đã có từ
Sprint 1, dùng chung cho các module sau như RBAC/invitation) — mục tiêu chính là xác nhận lại
không có hồi quy sau các đợt sửa RBAC gần đây, không phải tìm gap mới.
