# Kịch bản test thủ công — #70 Check-in có liveness

**Nền tảng: Backend, Mobile App, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code hiện tại — **đúng tinh thần AC, có 2 cơ
chế liveness song song cần phân biệt rõ khi test, và field `invalid_reason` không tồn tại literal:**

- **Cơ chế 1 — Liveness challenge chủ động** (dùng khi policy `gps_face_liveness`): App phải hoàn
  thành 1 challenge riêng (chuỗi cử động đầu/chớp mắt) TRƯỚC, challenge phải: đã pass, còn tươi
  (≤2 phút kể từ lúc pass — `CHECKIN_CHALLENGE_FRESHNESS_MINUTES`), đúng site, đúng mục đích, và
  CHƯA từng được dùng (tiêu thụ 1 lần duy nhất, atomic trong cùng transaction check-in). Ảnh chụp
  đơn thuần không thay thế được — thiếu challenge hợp lệ thì bị từ chối.
- **Cơ chế 2 — Liveness thụ động 1 khung hình** (dùng ở luồng ảnh optional/offline, cờ
  `requiresLiveness`): AI worker tự kiểm tra "ảnh có phải người thật" trước khi so khớp khuôn mặt;
  fail → `liveness_failed`, `faceVerified=false`.
- **`invalid_reason`: KHÔNG TỒN TẠI như 1 cột riêng trên bảng `checkins`** — cách hệ thống thực sự
  báo lý do là qua `violations.violation_type` (`liveness_fail` khác `face_fail`, phân biệt theo
  `livenessVerified==false`) kết hợp message hiển thị cho người dùng, không phải 1 enum lý do gắn
  trực tiếp vào bản ghi check-in.

---

## A. Test trên Mobile App

### 1. Check-in tại site yêu cầu `gps_face_liveness` — hoàn thành đúng challenge, khuôn mặt đúng
- Hoàn thành liveness challenge (cử động theo hướng dẫn) rồi check-in.
- **Kỳ vọng:** thành công, status cuối `valid`.

### 2. Check-in tại site `gps_face_liveness` nhưng KHÔNG làm challenge (chỉ gửi ảnh thường)
- Bỏ qua bước challenge, thử check-in chỉ với ảnh chụp thường.
- **Kỳ vọng theo code hiện tại:** bị từ chối — ảnh đơn thuần không thay thế được challenge đã pass.

### 3. Challenge đã hết hạn "tươi" (quá 2 phút từ lúc pass)
- Hoàn thành challenge, đợi hơn 2 phút, rồi mới gửi check-in.
- **Kỳ vọng:** bị từ chối do challenge không còn tươi, phải làm lại.

### 4. Dùng lại 1 challenge đã dùng cho lần check-in trước
- Sau khi đã check-in thành công bằng 1 challenge, thử dùng LẠI đúng challenge đó cho 1 lần
  check-in khác (nếu kỹ thuật cho phép gửi lại request).
- **Kỳ vọng:** bị từ chối — challenge đã tiêu thụ (dùng 1 lần duy nhất).

### 5. Liveness fail ở challenge (không phải người thật/không đúng cử động)
- Cố tình làm sai/giả challenge (ảnh tĩnh, video quay lại...).
- **Kỳ vọng:** challenge không pass, không hoàn thành được bước xác thực, không thể tiếp tục
  check-in.

### 6. Liveness fail ở cơ chế thụ động (luồng ảnh optional)
- Với site chỉ yêu cầu `gps_face` (không bắt buộc liveness challenge) nhưng vẫn gửi ảnh không phải
  người thật qua đường ảnh thường.
- **Kỳ vọng:** AI worker phát hiện `liveness_failed`, `faceVerified=false`, tạo violation
  `liveness_fail`, status `pending_review`.

### 7. Xác nhận KHÔNG có field `invalid_reason` riêng trên check-in
- Sau case 6, kiểm tra dữ liệu check-in trực tiếp (DB hoặc response API đầy đủ).
- **Kỳ vọng theo code hiện tại:** không có cột/field `invalid_reason` — lý do chỉ nằm ở bảng
  `violations` (`violation_type=liveness_fail`) và message hiển thị, khớp đúng phát hiện đã ghi
  nhận, không phải thiếu sót cần vá.

---

## Ghi chú
Trọng tâm khi test: case 2-4 (cơ chế challenge — tươi, dùng 1 lần, không thể thay bằng ảnh thường —
là phần logic phức tạp và bảo mật nhất, cần test kỹ) và case 6 (phân biệt đúng với #69 case 2 — 2
loại fail khác nhau: face_fail vs liveness_fail, phải tạo đúng loại violation tương ứng).

**ĐÃ TEST (2026-08-17):** không phát hiện gap thêm ngoài phần đã vá ở #69 (liveness thụ động cho
nhánh `gps_face`, nay áp dụng cả case 6 ở đây). Script `test_checkin_liveness.sh` 2/2 pass; phần
E2E cần enrollment thật qua liveness-challenge (đòi hỏi camera thiết bị thật) SKIP có chủ đích
trong script, không tính là fail. Đã đóng — ĐÃ KHÓA.
