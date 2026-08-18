# Kịch bản test thủ công — #99 Hủy scheduled check

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

## ✅ ĐÃ FIX (2026-08-18) — ĐÃ KHÓA

Cả 2 gap thật đã xác nhận trước đó ("không có field cancelledBy/At/Reason", "không ghi audit log")
đã được vá — đây là gap dữ liệu truy vết rõ ràng theo đúng AC, không phải đánh đổi thiết kế nên
không cần hỏi quyết định, tiến hành fix trực tiếp.

### Thay đổi
- **Migration V104**: thêm `scheduled_checks.cancelled_by/cancelled_at/cancelled_reason` (mới hoàn
  toàn, trước đó không tồn tại field nào).
- `ScheduledCheckCancelService.cancelCheck` — nhận thêm `callerId`, `reason` (optional, request
  body mới `CancelScheduledCheckRequest`, KHÔNG bắt buộc để giữ tương thích ngược với client cũ
  gọi không kèm body), set đủ `cancelledBy/At/Reason`, ghi audit log
  `scheduled_check_cancelled` (before/after status).
- `cancelPendingByAssignment` (hủy hàng loạt khi assignment bị hủy / employee bị terminate) — set
  `cancelledAt` + `cancelledReason` (`"Assignment cancelled"` hoặc `"Employee terminated"`).
- `ScheduledCheckDetailResponse` — bổ sung 3 field mới để #109/#110 cũng hưởng lợi.
- Web Admin: modal xác nhận hủy đổi từ `modal.confirm` đơn giản sang modal có ô nhập lý do
  (không bắt buộc), trang chi tiết hiển thị đủ "Lúc hủy / Người hủy / Lý do" khi
  `status=cancelled`.

### Bổ sung (2026-08-18, cùng ngày) — audit log cho nhánh hủy hàng loạt tự động
Ban đầu `cancelPendingByAssignment` chỉ set `cancelledBy=null` + log ứng dụng (`log.info`), không có
audit log — hỏi người dùng có cần thêm không (dù trigger là tự động, vẫn có 1 người thật gây ra
cascade: người hủy assignment / người terminate employee). Người dùng chọn **"Cần thêm"**. Đã sửa:
- `cancelPendingByAssignment(tenantId, assignmentId, callerId, reason)` — nhận thêm `callerId`,
  set `cancelledBy=callerId` (không còn null), ghi `audit_logs` action `scheduled_check_cancelled`
  cho từng check bị cascade-hủy (bọc try/catch, lỗi audit không phá transaction chính).
- 2 call site cập nhật: `AssignmentService.cancelAssignment` truyền
  `reason="Assignment cancelled"`; `EmployeeService.changeEmployeeStatus` (nhánh terminate) truyền
  `reason="Employee terminated"`.

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-18)

### 1. ✅ Hủy check đang `pending`/`sent` — thành công
### 2. ✅ Không hủy được check đã ở trạng thái cuối
### 3. ✅ Check đã hủy KHÔNG bị worker gửi nhầm (ZREM đúng)
### 4. ✅ Xác nhận ĐÃ CÓ `cancelledBy`/`cancelledAt`/`cancelledReason` (đã fix)
- Hủy 1 check kèm lý do qua API thật.
- **Kết quả thực tế:** `status='cancelled'`, `cancelled_by` đúng userId caller,
  `cancelled_at` có giá trị, `cancelled_reason` đúng chuỗi đã truyền — ĐÚNG.

### 5. ✅ Xác nhận ĐÃ CÓ audit log (đã fix)
- **Kết quả thực tế:** `audit_logs` có bản ghi `scheduled_check_cancelled`,
  `old_value={"status":"sent"}`, `new_value={"status":"cancelled","reason":"..."}` — ĐÚNG.

### 6. ✅ Tương thích ngược — hủy KHÔNG kèm body vẫn hoạt động
- Gọi `POST .../cancel` không có request body (client cũ).
- **Kết quả thực tế:** HTTP 200, hủy thành công, `cancelledReason=null` — không phá vỡ client cũ.

### 7. ✅ Hủy hàng loạt qua assignment-cancel — audit log đúng (bổ sung 2026-08-18)
- Insert trực tiếp 1 scheduled_check `status='pending'` gắn với 1 assignment, gọi
  `DELETE .../assignments/{id}` (hủy assignment) qua API thật.
- **Kết quả thực tế:** check chuyển `status='cancelled'`, `cancelled_by=<adminUserId>` (người hủy
  assignment), `cancelled_reason='Assignment cancelled'`; `audit_logs` có đúng 1 bản ghi
  `entity_type=ScheduledCheck`, `action=scheduled_check_cancelled`, `actor_id=<adminUserId>`,
  `old_value={"status":"pending"}`, `new_value` chứa `reason`+`assignmentId` — ĐÚNG.

### 8. ✅ Hủy hàng loạt qua employee-termination — audit log đúng (bổ sung 2026-08-18)
- Insert trực tiếp 1 scheduled_check `status='sent'` gắn với 1 assignment của employee, gọi
  `PATCH .../employees/{id}/status` với `status=terminated` qua API thật.
- **Kết quả thực tế:** check chuyển `status='cancelled'`, `cancelled_by=<adminUserId>` (người
  terminate employee), `cancelled_reason='Employee terminated'`; `audit_logs` có đúng 1 bản ghi
  tương ứng — ĐÚNG.

---

## B. Test trên Web Admin — ✅ PASS, đã test live qua UI thật kết nối backend thật (Playwright, 2026-08-18)
- Modal hủy: hiển thị đúng ô "Lý do hủy (không bắt buộc)", nhập lý do → bấm "Hủy lượt kiểm tra" →
  **kết quả thực tế:** `POST .../cancel` trả 200, thống kê "Đã hủy" tăng đúng ngay lập tức.
- Modal chi tiết: xác nhận hiển thị đúng alert "Lượt kiểm tra đã bị hủy" với đầy đủ "Lúc: ... ·
  Người hủy: ... · Lý do: ..." khớp chính xác dữ liệu đã nhập qua UI, và "Giờ gửi thực tế" hiển thị
  đúng thời điểm (xem thêm ở #100).

---

## Regression
Toàn bộ `tests/randomcheck/*.sh` (18 suite) — 100% PASS sau fix, không có regression.
Sau bổ sung audit log cho `cancelPendingByAssignment` (2026-08-18): chạy lại 18 suite trên +
`tests/site/test_cancel_assignment.sh` (9/9 PASS) + `tests/employee/test_change_employee_status.sh`
(8/8 PASS, 1 SKIP không liên quan) — không có regression.
