# Kịch bản test thủ công — #103 Phản hồi mode vị trí + Face ID

**Nền tảng: Backend, Mobile App, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG — "location_face path async". Đã xác nhận lại qua code hiện tại —
**ĐÚNG, không lỗi thời:**

- **Nhánh fail NGAY (đồng bộ):** chưa đăng ký Face ID (`FaceProfile.status != "enrolled"`) hoặc
  thiếu ảnh → `faceVerified=false` ngay lập tức + tạo violation `face_fail`, không cần chờ AI.
- **Nhánh xử lý bất đồng bộ (đã đăng ký Face ID + có ảnh):** publish job Redis
  (`faceVerifyJobPublisher.publish(..., livenessRequired=false)`) → AI worker chấm điểm khớp mặt →
  callback → `applyFaceResult()` set `faceVerified`, cập nhật `outcome`/`failureReason`, tạo
  violation `face_fail` nếu fail (có guard chống trùng lặp qua
  `existsByScheduledCheckIdAndViolationType`).
- **✅ Lưu `selfie_url`** đúng theo path `checkins/{tenantId}/{responseId}.jpg` (migration V82).

---

## A. Test trên Backend
### 1. ✅ Chưa đăng ký Face ID — fail ngay, không cần chờ AI
- **Kỳ vọng:** `faceVerified=false` ngay trong response đầu tiên (đồng bộ), có violation `face_fail`.

### 2. ✅ Đã đăng ký Face ID, ảnh khớp — `pass` (qua luồng bất đồng bộ)
- **Kỳ vọng:** response ban đầu `pending`/chờ xử lý, sau khi AI worker xử lý xong → `faceVerified=true`,
  `outcome=pass` (nếu vị trí cũng đạt).

### 3. ✅ Đã đăng ký Face ID, ảnh KHÔNG khớp — `fail_face`
- **Kỳ vọng:** `faceVerified=false`, `outcome=fail`, `failureReason` chứa `face`, có violation
  `face_fail`, KHÔNG bị tạo trùng nếu callback gọi lại (idempotency guard).

### 4. Vị trí fail NHƯNG face pass — vẫn tính fail (do vị trí)
- **Kỳ vọng:** `outcome=fail` với `failureReason` phản ánh đúng nguyên nhân (vị trí), không bị che
  lấp bởi kết quả face pass.

### 5. ✅ `selfie_url` lưu đúng path
- Kiểm tra `random_check_responses.selfie_url` sau khi phản hồi có ảnh.
- **Kỳ vọng:** đúng format `checkins/{tenantId}/{responseId}.jpg`.

## B. Test trên Mobile App
### 6. UI yêu cầu chụp ảnh selfie cho mode `location_face`
- **Kỳ vọng:** màn phản hồi hiển thị bước chụp ảnh (dùng `FacePhotoCapture` — chụp đơn giản, không
  phải thử thách chủ động).

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
Không có gap cần vá. `test_check_response_face.sh` đã phủ phần backend cốt lõi. Case 6 cần test tay
trên App thật.
