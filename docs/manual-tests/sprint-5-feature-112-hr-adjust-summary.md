# Kịch bản test thủ công — #112 HR chỉnh attendance summary

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "không ghi audit". Đã xác nhận lại qua code hiện tại —
**GAP ĐÃ ĐƯỢC VÁ TỪ TRƯỚC — chính là 1 phần của công việc đã hoàn thành SỚM HƠN TRONG PHIÊN LÀM VIỆC
NÀY (đợt #80-84, tính năng #84 "Phát hiện thiếu checkout" đã thêm audit `attendance_summary_adjusted`
cho đúng endpoint này). Chỉ cần XÁC NHẬN LẠI qua test live, không cần sửa thêm:**

- **✅ Cập nhật đúng các field:** `totalWorkMinutes, status, late, lateMinutes, earlyLeave,
  earlyLeaveMinutes, otMinutes, missingCheckout` — tất cả optional, chỉ field nào truyền mới đổi.
- **✅ `reason` BẮT BUỘC** (`@NotBlank`) — đúng AC "bắt buộc adjustment_note" (tên field khác
  nhưng cùng ý nghĩa).
- **✅ ĐÃ CÓ AUDIT LOG:** `auditLogService.record(...)` action `attendance_summary_adjusted`, ghi
  đầy đủ 8 field trước/sau + reason.
- **✅ Set `adjustmentReason`** trên entity — đây CHÍNH LÀ cờ "đã điều chỉnh thủ công" (tương đương
  ý nghĩa `manual_adjusted` của AC, dùng non-null làm sentinel thay vì boolean riêng — cùng pattern
  với nhiều field khác trong hệ thống) — VÀ đã xác nhận từ trước: bản ghi có `adjustmentReason` sẽ
  được BẢO VỆ khỏi mọi lần tính lại tự động sau đó (checkin/checkout mới, job đêm...) cho tới khi HR
  chủ động "mở khóa" qua `unlock-and-recompute`.

---

## A. Test trên Backend

### 1. ✅ Chỉnh sửa 1 phần field (VD chỉ `totalWorkMinutes`)
- **Kỳ vọng:** chỉ field truyền vào thay đổi, các field khác giữ nguyên.

### 2. ✅ Thiếu `reason` — bị từ chối
- **Kỳ vọng:** 400.

### 3. ✅ Sau khi điều chỉnh, bản ghi được khóa khỏi tính lại tự động
- Điều chỉnh xong, trigger 1 sự kiện có thể gây tính lại (VD checkin mới cùng ngày).
- **Kỳ vọng:** summary KHÔNG bị ghi đè bởi tính toán tự động, vẫn giữ giá trị HR đã chỉnh.

### 4. ✅ Xác nhận CÓ ghi audit log
- Điều chỉnh 1 summary, kiểm tra `audit_logs`.
- **Kỳ vọng:** có bản ghi `attendance_summary_adjusted` với đủ before/after 8 field + reason.

---

## ✅ PASS — ĐÃ KHÓA (2026-08-18)

Xác nhận lại qua regression suite thật (`test_adjust_attendance_summary.sh`, PASS trong đợt
31/31): audit log `attendance_summary_adjusted` ghi đủ before/after 8 field + reason, bản ghi có
`adjustmentReason` được bảo vệ khỏi tính lại tự động đúng như thiết kế.

## Ghi chú
**Không có gap cần vá — gap của audit gốc đã tự động được giải quyết nhờ công việc trước đó trong
cùng phiên làm việc (đợt #84).**
