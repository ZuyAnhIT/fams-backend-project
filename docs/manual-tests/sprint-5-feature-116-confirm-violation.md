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

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
**Đã cải chính 1 phát hiện sai trong audit gốc (case 2).** Trọng tâm khi test: case 3 (gap audit log
— cần xử lý ĐỒNG THỜI cho cả 3 method confirm/dismiss/updateAttendanceImpact trong module violation,
không vá riêng lẻ từng cái, vì cùng 1 nguyên nhân — module này chưa từng tích hợp AuditLogService).
Case 1-2 rủi ro fail thấp, đã có `test_hr_confirm_violation.sh` phủ.
