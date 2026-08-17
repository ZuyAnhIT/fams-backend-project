# Kịch bản test thủ công — #79 HR xem chi tiết check-in

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận + vá (2026-08-17) — **đúng phần cốt lõi (GPS/accuracy/
face-score/device/source); gap mới phát hiện (endpoint HR override thiếu audit log) ĐÃ VÁ:**

- **KHÔNG có `selfie_url`/ảnh chụp lúc check-in** — chỉ có `employeePhotoUrl` (ảnh giải trình của
  nhân viên, không phải ảnh xác thực). HR xem `faceVerifyScore`/`faceVerified`/`livenessVerified`
  thay thế, đúng như AC gốc đã dự phòng phương án thay thế. Không sửa.
- **KHÔNG có "distance" (khoảng cách mét)** — chỉ có boolean `insideGeofence` + `gpsRiskScore` +
  tọa độ thô. Không sửa.
- **"Pair check-in/out": cả 2 phía nằm trên CÙNG 1 bản ghi** (không phải 2 dòng nối khóa ngoại).
- **Gap mới phát hiện: endpoint HR override (`PATCH .../override`) KHÔNG ghi audit log — ĐÃ VÁ.**
  Thêm action `checkin_overridden`, dùng chung helper `recordAudit` đã có sẵn từ đợt vá
  submitCheckin/submitCheckout, snapshot gồm `oldStatus`/`newStatus`/`reason`.

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. Xem chi tiết check-in — happy path
- Mở chi tiết 1 check-in đã hoàn chỉnh (có cả check-in lẫn check-out).
- **Kỳ vọng:** hiện đầy đủ GPS, accuracy, trong/ngoài geofence, risk score, thông tin ca/site/nhân
  viên, cả phần check-in lẫn check-out trên cùng 1 màn. (Không đổi, không hồi quy.)

### 2. Xác nhận KHÔNG có ảnh selfie xác thực khuôn mặt
- Quan sát toàn bộ màn chi tiết, tìm ảnh chụp lúc check-in.
- **Kỳ vọng theo code hiện tại:** không có — chỉ có điểm số face.

### 3. Xác nhận KHÔNG có số liệu khoảng cách (mét) tới geofence
- Với 1 check-in ngoài geofence, xem chi tiết.
- **Kỳ vọng theo code hiện tại:** chỉ thấy "ngoài vùng" (boolean) + risk score.

### 4. Xem chi tiết check-in ngoài phạm vi site-scope
- Đăng nhập tài khoản site-scoped khác, thử xem chi tiết 1 check-in thuộc site không được gán.
- **Kỳ vọng:** 403.

### 5. ✅ HR override kết quả check-in — ĐÃ VÁ, TEST LIVE
- Check-in ngoài geofence (tạo `pending_review`) qua API thật, sau đó HR override sang `valid` kèm
  lý do "Xác nhận qua camera an ninh".
- **Kết quả thật (2026-08-17):** override thành công, đồng thời `audit_logs` có đúng bản ghi
  `checkin_overridden` với `new_value` chứa `{"oldStatus": "pending_review", "newStatus": "valid",
  "reason": "Xac nhan qua camera an ninh"}` — xác nhận gap đã vá đúng, khớp với các hành động ghi
  khác trong cùng module (submitCheckin/submitCheckout).

---

## Ghi chú
Toàn bộ case đã test live: script tự động `test_override_checkin.sh` (14/14) và
`test_hr_checkin_detail.sh` (10/10) pass, không hồi quy + test tay qua API/DB xác nhận đúng gap
audit đã vá (case 5). Case 2-3 chỉ xác nhận lại các điểm đã biết từ trước (không phải gap, không
sửa). Đã đóng — ĐÃ KHÓA.
