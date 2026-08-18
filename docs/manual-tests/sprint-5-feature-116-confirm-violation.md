# Kịch bản test thủ công — #116 Xác nhận vi phạm

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "không refresh attendance_summaries; không ghi audit". Đã
xác nhận lại qua code hiện tại — **1 điểm đã SAI/lỗi thời, 1 điểm vẫn đúng:**

- **✅ CẢI CHÍNH: "không refresh attendance_summaries" — SAI, đã lỗi thời.**
  `confirmViolation` GỌI ĐÚNG `attendanceSummaryService.recomputeIfSummaryExists(...)` NGAY sau khi
  xác nhận (không điều kiện, luôn refresh) — đã được vá từ trước (comment trong code ghi rõ ngày
  sửa 2026-08-07).
- **✅ Set đúng `resolved=true`, `resolution="confirmed"`, `resolutionReason`, `resolvedBy`,
  `resolvedAt`** — đúng AC.
- **❌ GAP thật (xác nhận đúng, KHÔNG lỗi thời): KHÔNG ghi audit log** — xác nhận qua TOÀN BỘ module
  `violation`: KHÔNG CÓ 1 lệnh gọi `AuditLogService` nào, ở BẤT KỲ method nào (không riêng
  `confirmViolation`) — đây là gap DIỆN RỘNG ảnh hưởng đồng thời cả #116, #117, #118 (3 tính năng
  xử lý vi phạm), không phải lỗi riêng lẻ từng cái.

---

## A. Test trên Backend

### 1. ✅ Xác nhận vi phạm thành công
- **Kỳ vọng:** `resolved=true`, `resolution=confirmed`, `resolvedBy`/`resolvedAt` set đúng.

### 2. ✅ CẢI CHÍNH: xác nhận CÓ refresh attendance_summary liên quan (khác audit gốc)
- Xác nhận 1 violation có `affectsAttendance=true`, kiểm tra `attendance_summary` tương ứng ngay
  sau đó.
- **Kỳ vọng theo code hiện tại:** summary được tính lại NGAY, không cần đợi job đêm — khác với audit
  gốc ghi "không refresh", cần cải chính khi báo cáo.

### 3. ❌ Xác nhận gap "không ghi audit log"
- Xác nhận 1 violation, kiểm tra `audit_logs`.
- **Kỳ vọng theo code hiện tại:** KHÔNG có bản ghi nào — xác nhận đúng gap (chung cả module).

---

## ✅ ĐÃ VÁ (2026-08-18) — ĐÃ KHÓA

### Case 3 — gap audit log: ĐÃ VÁ
`ViolationService` thêm helper `recordViolationAudit()` dùng chung cho cả `confirmViolation`,
`dismissViolation`, `updateAttendanceImpact` — mỗi method giờ ghi 1 action riêng
(`violation_confirmed`/`violation_dismissed`/`violation_attendance_impact_updated`) qua
`AuditLogService`, best-effort (không rollback nếu ghi audit lỗi).

### Test live — ✅ PASS (2026-08-18)
Xác nhận 1 violation qua API thật: `audit_logs` có đúng 1 bản ghi `violation_confirmed` với
`resolutionReason` đúng nội dung đã nhập.

## Ghi chú
Regression: 34/34 (bao gồm `test_hr_confirm_violation.sh`).
