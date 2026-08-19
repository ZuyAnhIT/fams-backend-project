# Kịch bản test thủ công — #136/#137/#138 Audit Log Viewer (Danh sách / Diff / Trace request_id)

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN cho cả 3 mục — ghi nhận `AuditLogService.record()` không
được gọi ở đâu, mask thiếu field nhạy cảm, endpoint trace không có dữ liệu.

## 🔍 Phát hiện: audit gốc đã lỗi thời — cả 3 tính năng thực ra đã hoạt động đầy đủ
Điều tra lại (2026-08-19) phát hiện `.record()` hiện được gọi ~48 lần trên 25+ service khác nhau
(auth/rbac/tenant/subscription/employee/checkin/attendance/geofence/randomcheck/report/shift/
site/violation/workspace) — không còn là CRUD rỗng như audit cũ ghi nhận. `MaskingUtils.PII_KEYS`
đã có sẵn `totpSecret`/`backupCodes`/`nationalId`/`identityNumber`/`idNumber`. **Không có thay đổi
code nào cho 3 mục này** — chỉ audit lại và xác nhận qua test live.

## A. Test trên Backend (API thật)

### #136 — Danh sách audit log, filter, phân trang, RBAC scope
- `tests/audit/test_audit_logs.sh`: PASS 14/14 — tạo tenant/nhân viên thật, sinh nhiều audit
  action, list/filter theo tenant/actor/action/entityType trả đúng dữ liệu; phân trang đúng.

### #137 — Diff old/new value
- `GET /audit-logs/{id}` trả đầy đủ `oldValue`/`newValue` JSONB.
- Đã xác nhận qua UI (xem mục B) với 1 case thật: PATCH tenant status active→inactive.

### #138 — Trace theo request_id
- `AuditLogService.findByRequestId` (tenant-scoped) hoạt động — 1 request thật (VD: tạo
  employee/site) sinh nhiều dòng audit cùng `requestId`, trace trả đúng toàn bộ timeline.
- **🟡 Gap nhỏ còn lại, KHÔNG vá đợt này**: AC nhắc "show metadata endpoint/status nếu có" —
  entity `AuditLog` không có cột `endpoint`/`httpStatus`, chỉ có `requestId`/`ipAddress`/
  `userAgent`. Chữ "nếu có" trong AC đọc là tùy chọn; thêm 2 cột mới cần migration + populate lại
  ~48 call site, quy mô lớn hơn 1 gap nhỏ — không tự ý mở rộng. Ghi nhận làm follow-up riêng nếu
  team vận hành thực sự cần.

## B. Test trên Web Admin (Playwright, trình duyệt thật)

### 1. ✅ Trang danh sách audit log (`/admin/audit-logs`)
- Login `admin@fams.com` → vào "Audit toàn hệ thống".
- **Kết quả xác nhận qua ảnh chụp thật**: bộ lọc đầy đủ (công ty, người thao tác, loại đối tượng,
  entity ID, hành động, request ID, khoảng ngày), bảng hiển thị **12,409 dòng thật, 621 trang**,
  cột Request ID, nút "Xem" chi tiết từng dòng.

### 2. ✅ Xem chi tiết / diff (#137)
- Click "Xem" trên 1 dòng UPDATE (Employee).
- **Kết quả xác nhận qua ảnh chụp thật**: modal "Chi tiết thay đổi" hiển thị người thao tác, hành
  động, đối tượng, thời gian, Request ID, IP, User agent, và bảng so sánh "Trước thay đổi / Sau
  thay đổi / Kết quả" — case thực tế: `status: active → inactive`, đánh dấu "Thay đổi" rõ ràng,
  dễ đọc — đúng AC "diff JSON dễ đọc".

### 3. ✅ Trace theo request_id (#138)
- Lấy 1 `requestId` thật từ bảng (VD: `req-7aa1874b192`), điền vào ô "Request ID để trace" và tìm
  kiếm → trả đúng các dòng cùng request.

## Ghi chú
Không có regression — không sửa code cho 3 mục này trong đợt này, chỉ audit lại + xác nhận qua
test API thật (`test_audit_logs.sh` 14/14) và test UI thật qua Playwright (ảnh chụp màn hình lưu
tại session scratchpad).
