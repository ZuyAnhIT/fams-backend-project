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

## ✅ ĐÃ FIX (2026-08-18) — ĐÃ KHÓA

Test live phát hiện 2 vấn đề THẬT, không phải bug ở business logic của #103 mà ở môi trường/test:

1. **Container `fams-ai` (AI worker) bị treo do bind-mount host bị "gãy"** — `docker exec fams-ai
   ls /app/app` rỗng dù thư mục host có đủ file, khiến `uvicorn` không import được `app.main` và
   container restart-loop liên tục (unhealthy nhiều giờ). Đây là lỗi hạ tầng WSL2/Docker Desktop
   quen thuộc (bind mount không remount đúng sau khi máy sleep/hibernate), không phải lỗi code.
   **Fix:** `docker compose -f docker-compose.full.yml restart fams-ai` — remount lại đúng, healthy
   ngay, models load lại bình thường.
2. **`test_check_response_face.sh` lỗi thời so với 2 thay đổi nghiệp vụ đã có từ trước:**
   - Test gọi `POST /consent` bằng `ADMIN_TOKEN` — nhưng theo thiết kế hiện tại (đúng, có chủ đích:
     "biometric consent must come from the data subject"), endpoint này CHỈ chấp nhận chính nhân
     viên gọi, admin gọi bị từ chối 403. Test cũ gọi sai actor nên luôn fail ở bước enroll
     ("Consent not recorded").
   - Test thiếu hẳn bước `POST /approve` — enroll() hiện nay đưa hồ sơ vào trạng thái chờ HR duyệt
     (`reviewStatus=pending`), KHÔNG kích hoạt ngay `status=enrolled` như trước (tính năng
     HR-review đã được thêm sau khi test này được viết). Thiếu approve → face luôn ở trạng thái
     chưa enrolled → nhánh async không bao giờ chạy được.
   - Test 2's kỳ vọng cũ ("có ảnh nhưng chưa enroll → fail-open, outcome=pass ngay") sai với thiết
     kế thật: theo code + Javadoc `CheckResponseService`, chưa enroll thì fail NGAY dù có ảnh hay
     không — cùng nhánh với Test 1, không có khái niệm "fail-open" nào ở đây.
   **Fix:** sửa `tests/face-id/test_check_response_face.sh` — consent dùng `EMP_TOKEN`, thêm bước
   approve sau enroll, sửa lại assertion Test 2 khớp đúng hành vi thật.

Sau khi fix cả 2: **11/11 PASS**, bao gồm nhánh E2E thật qua AI worker (enroll → approve → respond
→ async callback → `face_verified=true` trong DB, `face_verify_score` có giá trị).

### Test trên Mobile App — ✅ PASS, test live qua Expo Web thật kết nối backend thật (2026-08-18)
- Case 6: panel phản hồi mode `location_face` cho nhân viên chưa enroll hiển thị đúng cảnh báo
  "Face ID chưa sẵn sàng" + nút "Mở đăng ký Face ID", không hiện nút gửi vị trí đơn thuần.

## Ghi chú
`test_check_response_face.sh` đã phủ phần backend cốt lõi (đã sửa, xem trên).
