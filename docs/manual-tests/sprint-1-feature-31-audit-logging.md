# Kịch bản test thủ công — #31 Ghi audit cho hành động quan trọng

**Nền tảng: Backend, Queue/AI/Automation (chỉ đọc lại qua Web Admin — không có màn ghi trực tiếp).**

ℹ️ Audit gốc (07-22) ghi gap nghiêm trọng: "`AuditLogService.record()` KHÔNG được gọi ở BẤT KỲ
module nào — audit trail không hoạt động dù API đọc hoàn chỉnh". Đã xác nhận qua code hiện tại:
**gap này đã được sửa từ lâu, hiện tại sai hoàn toàn với thực tế** — `auditLogService.record(...)`
được gọi ở **17 module khác nhau** (RBAC, Tenant, Employee, Attendance, Site, Violation,
RandomCheck, IP Whitelist, v.v.), bao gồm toàn bộ các tính năng RBAC vừa test/xây ở các mục
#24-30 và đợt audit RBAC 2026-08-14 (transfer-owner, clone role, bulk assign, thu hồi role...).
- **`request_id`: đã có** — `RequestIdFilter` gắn request ID cho mọi request, lưu kèm audit log.
- **Masking dữ liệu nhạy cảm: đã có** — `MaskingUtils.maskAuditMap` áp dụng cho cả `oldValue` và
  `newValue` trước khi lưu.
- **Append-only: theo thiết kế** — không có API `PATCH`/`DELETE` nào cho `audit_logs`, chỉ có
  `POST` (nội bộ, qua `record()`) và `GET` (đọc).

Vì #31 không phải 1 tính năng có màn UI riêng để "thao tác" mà là hạ tầng nền chạy ngầm phía sau
mọi module khác, cách test hợp lý nhất là **xác nhận gián tiếp qua các tính năng đã test ở #24-30**
cộng với soi trực tiếp bảng `audit_logs`/màn Nhật ký audit.

---

## A. Xác nhận qua màn Nhật ký audit (Web Admin)

### 1. Xem danh sách audit log
- Đăng nhập Company Admin (có quyền `audit:list`) hoặc Platform Admin, vào màn Nhật ký audit.
- **Kỳ vọng:** thấy danh sách audit log gần đây, có actor, action, resource, thời gian.

### 2. Đối chiếu 1 hành động vừa làm ở #29/#30 xuất hiện đúng trong log
- Ngay sau khi gán hoặc thu hồi 1 role ở kịch bản #29/#30, quay lại màn Nhật ký audit (hoặc query
  trực tiếp DB nếu UI chưa kịp cập nhật/không có filter phù hợp):
  ```bash
  docker exec fams-postgres psql -U fams_user -d fams_db -c \
    "SELECT action, actor_id, resource_type, resource_id, created_at FROM audit_logs \
     WHERE action IN ('role_assigned','role_revoked') ORDER BY created_at DESC LIMIT 5;"
  ```
- **Kỳ vọng:** thấy đúng bản ghi vừa thao tác, `actor_id` khớp người thực hiện, thời gian khớp.

### 3. Lọc theo action/resource/actor
- Dùng bộ lọc trên màn Nhật ký audit (nếu có) để lọc riêng `role_assigned` hoặc theo 1 actor cụ
  thể.
- **Kỳ vọng:** kết quả lọc đúng, không lẫn action/tenant khác (đặc biệt với Company Admin — chỉ
  thấy audit log của tenant mình, không thấy tenant khác).

### 4. Kiểm tra masking dữ liệu nhạy cảm
- Tìm 1 audit log có liên quan tới dữ liệu nhạy cảm (VD: sửa `nationalId` của nhân viên, hoặc sửa
  thông tin có PII) trong `oldValue`/`newValue`.
- **Kỳ vọng:** giá trị nhạy cảm bị che (VD: `***1234` thay vì số đầy đủ), không lộ nguyên văn
  trong JSON audit.

### 5. Kiểm tra `request_id` có mặt
- Xem chi tiết 1 audit log bất kỳ (qua API `GET /audit-logs/{id}` hoặc field ẩn trong response).
- **Kỳ vọng:** có `requestId` không rỗng, có thể dùng để trace lại đúng request HTTP gốc.

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright/API. Vì đây là hạ tầng nền, không cần lo case
nào "fail hoàn toàn" (đã xác nhận hoạt động qua review code) — mục tiêu chính là xác nhận UI đọc
đúng dữ liệu thật và không có tenant nào lộ chéo dữ liệu audit của tenant khác (case 3).
