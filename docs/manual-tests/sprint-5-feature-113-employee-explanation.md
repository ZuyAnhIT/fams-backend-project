# Kịch bản test thủ công — #113 Nhân viên gửi giải trình check-in lỗi

**Nền tảng: Backend, Web Admin, Mobile App.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "không gửi notification cho HR". Đã xác nhận lại qua code
hiện tại — **ĐÚNG, không lỗi thời, VÀ phát hiện thêm 1 gap MỚI chưa từng được audit gốc ghi nhận:**

- **`CheckinService.explainCheckin` / `explainCheckinWithPhoto`** — nhân viên chỉ được gửi giải
  trình cho ĐÚNG check-in của MÌNH (`record.getEmployeeId().equals(employee.getId())`, nếu không
  → 403). Ghi `employeeNote` + (tùy chọn) ảnh bằng chứng qua multipart upload
  (`evidenceStorageService.store`). Field `photoUrl` cũ (URL công khai tùy ý) đã bị chặn cố ý,
  bắt buộc dùng multipart upload — an toàn hơn (tránh SSRF/URL độc hại).
- **❌ GAP thật (xác nhận đúng, KHÔNG lỗi thời): KHÔNG gửi notification cho HR** — xác nhận qua
  toàn bộ 2 method: không có lệnh gọi tạo notification nào sau khi lưu giải trình. HR muốn biết có
  giải trình mới phải tự vào tra cứu, không được báo chủ động.
- **❌ GAP MỚI phát hiện, KHÔNG có trong audit gốc: KHÔNG có cách liên kết giải trình với 1
  `violation` cụ thể** — dù AC và cả `DB Entities` liệt kê rõ bảng `violations` liên quan, nhưng
  `SubmitExplanationRequest` chỉ có 2 field `note`/`photoUrl` (đã deprecated), KHÔNG có
  `violationId` nào. Giải trình CHỈ gắn được vào 1 `checkin`, không gắn được trực tiếp vào 1
  `violation` — nếu nhân viên muốn giải trình cho 1 vi phạm cụ thể (VD `no_response` từ random
  check) thay vì check-in thường, tính năng hiện tại không hỗ trợ đường liên kết đó.

---

## A. Test trên Backend

### 1. ✅ Gửi giải trình (chỉ text) thành công
- **Kỳ vọng:** `employeeNote` lưu đúng.

### 2. ✅ Gửi giải trình kèm ảnh (multipart) thành công
- **Kỳ vọng:** ảnh lưu qua `evidenceStorageService`, trả về URL xem được.

### 3. ✅ Không giải trình được cho check-in của NGƯỜI KHÁC
- **Kỳ vọng:** 403.

### 4. ✅ Field `photoUrl` cũ (URL tùy ý) bị từ chối
- Gửi kèm `photoUrl` (không phải multipart).
- **Kỳ vọng:** bị từ chối, yêu cầu dùng multipart/form-data.

### 5. ❌ Xác nhận gap "không gửi notification cho HR"
- Gửi giải trình, kiểm tra hộp thư HR liên quan.
- **Kỳ vọng theo code hiện tại:** KHÔNG có notification nào — xác nhận đúng gap.

### 6. ❌ Xác nhận gap MỚI: không liên kết được với `violation`
- Thử tìm cách gửi giải trình gắn với 1 violation cụ thể (thay vì checkin).
- **Kỳ vọng theo code hiện tại:** không có field/endpoint nào hỗ trợ — xác nhận đúng gap mới phát
  hiện, hẹp hơn phạm vi AC mô tả (AC bao gồm cả check-in LẪN violation, thực tế chỉ có check-in).

---

## B. Test trên Mobile App / Web Admin
- App: màn gửi giải trình cho check-in lỗi — xác nhận UI chỉ cho chọn ảnh từ thiết bị (không nhập
  URL), khớp đúng backend.
- Web Admin: HR xem giải trình đã gửi trong chi tiết check-in (nếu có UI hiển thị).

---

## ✅ ĐÃ VÁ (2026-08-18) — ĐÃ KHÓA

### Case 5 — gap notification: ĐÃ VÁ
- `CheckinService.explainCheckin`/`explainCheckinWithPhoto` giờ gọi `notifyExplanationSubmitted(...)`
  (helper mới, dùng `NotificationService`) — gửi `CHECKIN_EXPLANATION_SUBMITTED_HR` cho mọi user
  giữ quyền `checkins:list` trong tenant.
- `ViolationService.explainViolation`/`explainViolationWithPhoto` giờ gọi
  `violationNotificationService.notifyExplanationSubmitted(...)` — gửi
  `VIOLATION_EXPLANATION_SUBMITTED_HR` cho mọi user giữ quyền `violations:list`.
- Đăng ký 2 eventType mới vào `NotificationEventTypeCatalog`.

### Case 6 — gap "không liên kết được với violation": ĐÃ CẢI CHÍNH, KHÔNG PHẢI GAP THẬT
Khi rà lại code để implement notification, phát hiện **`ViolationService` đã có sẵn 2 method
`explainViolation`/`explainViolationWithPhoto`, controller đã wire sẵn
`POST .../violations/{violationId}/explain`** (cả JSON và multipart) — nghĩa là nhân viên ĐÃ CÓ
THỂ gửi giải trình gắn trực tiếp vào 1 violation cụ thể (kể cả violation từ random check như
`no_response`/`location_fail`/`face_fail`/`liveness_fail`), tách biệt hoàn toàn với đường giải
trình gắn vào checkin. Audit "gap MỚI" trước đó là SAI/lỗi thời — endpoint đã tồn tại từ trước, chỉ
là chưa có notification (đã vá ở case 5 cho cả 2 đường).

### Test live — ✅ PASS (2026-08-18)
Qua API+DB thật: nhân viên gửi giải trình cho 1 checkin → HR nhận đúng
`CHECKIN_EXPLANATION_SUBMITTED_HR`; nhân viên gửi giải trình trực tiếp cho 1 violation → HR nhận
đúng `VIOLATION_EXPLANATION_SUBMITTED_HR`.

## Ghi chú
Regression: 31/31 (bao gồm `test_employee_explanation.sh`). Case 1-4 rủi ro fail thấp, đã có
`test_employee_explanation.sh` phủ phần cốt lõi.
