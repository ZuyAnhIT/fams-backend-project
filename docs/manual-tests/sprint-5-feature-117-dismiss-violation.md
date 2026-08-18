# Kịch bản test thủ công — #117 Bỏ qua vi phạm

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "không gửi notification nhân viên; không ghi audit". Đã xác
nhận lại qua code hiện tại — **CẢ 2 điểm đều ĐÚNG, KHÔNG lỗi thời, và phát hiện thêm 1 gap MỚI:**

- **✅ `hrNote`/lý do BẮT BUỘC** (`DismissViolationRequest.reason` có `@NotBlank`) — Web Admin cũng
  disable nút xác nhận cho tới khi nhập lý do — đúng AC cả 2 tầng.
- **✅ Refresh attendance_summary** (giống #116, cùng cơ chế `recomputeIfSummaryExists`).
- **❌ GAP thật #1 (xác nhận đúng): KHÔNG gửi notification cho nhân viên** — xác nhận qua toàn bộ
  module: không có lệnh gọi `NotificationService` nào. Nhân viên bị gắn violation rồi được HR bỏ
  qua nhưng KHÔNG hề biết — có thể gây hiểu lầm nếu nhân viên đã tự lo lắng/thắc mắc về vi phạm.
- **❌ GAP thật #2 (chung với #116/#118): KHÔNG ghi audit log.**
- **❌ GAP thật #3, MỚI phát hiện (không có trong audit gốc): "affectsAttendance=false nếu policy"
  KHÔNG ĐƯỢC CÀI ĐẶT** — `dismissViolation` chỉ set `resolved/resolution/resolutionReason/
  resolvedAt/resolvedBy`, KHÔNG hề đụng tới field `affectsAttendance`. Nghĩa là: dù HR đã bỏ qua 1
  vi phạm, nếu vi phạm đó trước đó có `affectsAttendance=true`, nó VẪN TIẾP TỤC ảnh hưởng tới bảng
  công (VD vẫn tính vào `hasRandomCheckFailure`) — có thể gây SAI LỆCH bảng công dù HR đã quyết
  định "vi phạm này không có thật/không đáng kể".

---

## A. Test trên Backend

### 1. ✅ Bỏ qua vi phạm kèm lý do — thành công
- **Kỳ vọng:** `resolved=true`, `resolution=dismissed`, `resolutionReason` đúng lý do đã nhập.

### 2. ✅ Thiếu lý do — bị từ chối
- **Kỳ vọng:** 400.

### 3. ❌ Xác nhận gap "không gửi notification nhân viên"
- Bỏ qua 1 violation, kiểm tra hộp thư nhân viên liên quan.
- **Kỳ vọng theo code hiện tại:** KHÔNG có notification — xác nhận đúng gap.

### 4. ❌ Xác nhận gap "không ghi audit log"
- Tương tự #116 case 3.

### 5. ❌❌ (Case quan trọng nhất — ảnh hưởng thực tế tới bảng công) Xác nhận gap MỚI: bỏ qua vi
   phạm KHÔNG tự tắt `affectsAttendance`
- Bỏ qua 1 violation đang có `affectsAttendance=true` VÀ đang ảnh hưởng tới `attendance_summary`
  (VD `hasRandomCheckFailure=true`).
- **Kỳ vọng theo code hiện tại:** SAU KHI BỎ QUA, `affectsAttendance` VẪN GIỮ NGUYÊN `true`, và
  summary VẪN phản ánh vi phạm này như đang ảnh hưởng — xác nhận đúng gap mới phát hiện, đây là gap
  CÓ TÁC ĐỘNG THỰC TẾ tới độ chính xác bảng công/lương, không chỉ thiếu thông báo/audit.

---

## ✅ ĐÃ VÁ (2026-08-18) — ĐÃ KHÓA

Đã vá cả 3 gap, ưu tiên case 5 (tác động thực tế lớn nhất) như khuyến nghị:

- **Case 5 (quan trọng nhất) — `dismissViolation` giờ tự động set `affectsAttendance=false` +
  `attendanceImpactReviewed=true`** ngay khi bỏ qua — bảng công không còn coi vi phạm đã bỏ qua là
  đang ảnh hưởng nữa, đúng ý định của HR khi bấm "bỏ qua". `attendanceImpactReviewed=true` để
  quyết định này không bị 1 lần tính lại tự động sau đó ghi đè.
- **Case 3 — notification nhân viên**: thêm `ViolationNotificationService.notifyViolationDismissed()`
  — gửi `VIOLATION_DISMISSED_EMPLOYEE` kèm loại vi phạm + lý do bỏ qua.
- **Case 4 — audit log**: dùng chung `recordViolationAudit()` với #116/#118, action
  `violation_dismissed`, ghi cả `resolutionReason` và `affectsAttendance=false`.
- `ViolationActionResponse` thêm field `affectsAttendance` để FE thấy ngay giá trị mới không cần
  gọi lại API riêng.
- Web Admin (`ViolationResolutionModal.tsx`): cập nhật mô tả cảnh báo khi bỏ qua, nói rõ bảng công
  sẽ tự động hết ảnh hưởng và nhân viên sẽ được báo.

### Test live — ✅ PASS (2026-08-18)
Bỏ qua 1 violation đang `affects_attendance=true` qua API thật: response trả đúng
`affectsAttendance=false`; DB xác nhận `affects_attendance=f`, `attendance_impact_reviewed=t`;
nhân viên nhận đúng notification "Vi phạm ... đã được bỏ qua. Lý do: ..."; audit log ghi đúng
action `violation_dismissed`.

## Ghi chú
Regression: 34/34 (bao gồm `test_hr_dismiss_violation.sh`).
