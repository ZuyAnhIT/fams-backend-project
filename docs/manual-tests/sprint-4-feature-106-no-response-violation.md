# Kịch bản test thủ công — #106 Tạo violation khi không phản hồi

**Nền tảng: Backend, Web Admin, Mobile App, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "không gửi notification cho HR/nhân viên". Đã xác nhận lại
qua code hiện tại — **ĐÚNG, KHÔNG lỗi thời:**

- **`NoResponseViolationService.processChecks()`** — quét đúng các check `status=sent` đã quá
  `expiresAt`, set `status=no_response`, tạo violation `type=no_response`, có guard chống trùng lặp
  (idempotent, chạy lại job không tạo violation thừa).
- **❌ GAP thật: KHÔNG gửi notification cho HR/nhân viên** — xác nhận qua toàn bộ
  `NoResponseViolationService`/`NoResponseViolationJob`: không có lệnh gọi tạo notification nào.
  Trong CẢ module Random Check, CHỈ `RandomCheckDispatchService` gửi notification (lúc gửi yêu cầu
  kiểm tra ban đầu) — không có notification nào cho các bước SAU đó (vi phạm, kết quả).

---

## A. Test trên Backend

### 1. ✅ Tạo đúng violation `no_response` khi check hết hạn không phản hồi
- Để 1 check `status=sent` quá `expiresAt` không ai phản hồi, chờ job quét (mỗi 2 phút).
- **Kỳ vọng:** `status` chuyển `no_response`, có violation `type=no_response`.

### 2. ✅ Idempotent — job chạy lại không tạo trùng violation
- Chạy job quét 2 lần liên tiếp cho cùng 1 check đã xử lý.
- **Kỳ vọng:** vẫn chỉ 1 violation duy nhất.

### 3. ❌ Xác nhận gap "không gửi notification"
- Sau khi violation `no_response` được tạo, kiểm tra hộp thư HR lẫn nhân viên liên quan.
- **Kỳ vọng theo code hiện tại:** KHÔNG có notification nào được gửi — xác nhận đúng gap, cả 2 phía
  đều phải tự vào Web Admin/App tra cứu mới biết có vi phạm không phản hồi.

---

## ✅ ĐÃ VÁ (2026-08-18) — ĐÃ KHÓA

Đã vá gap notification, dùng chung 1 service với #107 (cùng gốc rễ):
- **`ViolationNotificationService`** (mới, `com.fams.modules.violation.service`) — gửi
  `RANDOM_CHECK_VIOLATION_EMPLOYEE` cho chính nhân viên (nếu có `userId`) và
  `RANDOM_CHECK_VIOLATION_HR` cho MỌI user giữ quyền `violations:list` trong tenant (dùng lại đúng
  pattern `findDistinctActiveHolderIdsOfPermissionInTenant` đã có sẵn từ missing-checkout #84).
  Best-effort (bọc try/catch, lỗi gửi thông báo không rollback violation).
- Đăng ký 2 eventType mới vào `NotificationEventTypeCatalog` (bắt buộc để hiện trong
  `GET /notification-event-types` và settings người dùng).
- `NoResponseViolationService.processChecks()` gọi `notifyRandomCheckViolation(...)` ngay sau khi
  lưu violation `no_response`.

### Test live — ✅ PASS (2026-08-18)
Insert 1 scheduled_check `status=sent` đã hết hạn, gọi `POST .../process-expired` qua API thật:
- `violations` có đúng 1 row `violation_type=no_response`.
- `notifications` có đúng 2 row: 1 gửi cho nhân viên ("Bạn vừa bị ghi nhận vi phạm: không phản hồi
  kiểm tra ngẫu nhiên tại ..."), 1 gửi cho HR/Admin ("... vừa bị ghi nhận vi phạm ...").

## Ghi chú
Regression: 26/26 (`tests/randomcheck/*.sh` + `tests/face-id/*.sh`), không ảnh hưởng gì khác.
`test_no_response_violation.sh` đã phủ phần backend cốt lõi (case 1-2), không cần sửa thêm.
