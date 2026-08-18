# Kịch bản test thủ công — #104 Phản hồi mode vị trí + Face ID + Liveness

**Nền tảng: Backend, Mobile App, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — **cáo buộc nghiêm trọng: "livenessScore được nhận/lưu nhưng
KHÔNG BAO GIỜ được đọc lại để quyết định pass/fail — dead code path"** (nghĩa là nếu đúng, 1 nhân
viên có thể phản hồi bằng ẢNH TĨNH/IN SẴN mà vẫn `pass` dù cấu hình yêu cầu chống giả mạo bằng
liveness). Đã điều tra kỹ theo TOÀN BỘ đường đi của dữ liệu — **CÁO BUỘC ĐÃ SAI/LỖI THỜI, bug này
THẬT SỰ TỒN TẠI TẠI THỜI ĐIỂM AUDIT GỐC (07-22) nhưng ĐÃ ĐƯỢC VÁ ở 1 commit sau đó (04-08, xác nhận
qua comment code ghi rõ ngày sửa) — audit gốc chỉ đơn giản là chưa cập nhật lại sau khi đã fix.**

- **Đường đi dữ liệu đầy đủ đã xác minh:**
  1. `SubmitCheckResponseRequest` nhận `livenessScore`.
  2. `CheckResponseService.submit()`: `livenessRequired = (checkMode == "location_face_liveness")`,
     publish job kèm `livenessRequired` — CÙNG CƠ CHẾ với luồng check-in liveness đã fix trước đó
     trong phiên làm việc này (`requiresLiveness` → Redis job → AI worker `requires_liveness`).
  3. AI worker (`worker.py`): nếu `requires_liveness=true`, chạy `check_liveness()` TRƯỚC, nếu fail
     thì trả kết quả `liveness_failed` ngay, KHÔNG cần chấm điểm khớp mặt nữa.
  4. **`CheckResponseService.applyFaceResult()` — nơi quyết định outcome cuối cùng — có comment
     ghi rõ: "Found via audit (2026-07-31): livenessVerified was persisted... but never inspected
     here... Now mirrors the faceVerified branch: liveness failure independently fails the check".**
     Code hiện tại: liveness fail → `outcome=fail`, `failureReason` cộng thêm `liveness_fail`, tạo
     violation `liveness_fail` riêng (có guard chống trùng lặp).
  5. `livenessScore`/`livenessVerified` được LƯU và ĐỌC LẠI đúng ở bước 4 — KHÔNG PHẢI dead code.
     Mobile App (`RandomCheckResult.tsx`) cũng đọc và hiển thị "Người thật: Đạt/Không đạt".
- **⚠️ NUANCE THẬT phát hiện MỚI (không phải cáo buộc cũ, không phải bug, là 1 lựa chọn thiết kế
  cần làm rõ với chủ dự án):** liveness ở luồng RANDOM CHECK là kiểm tra THỤ ĐỘNG (1 ảnh chụp tĩnh
  do AI chấm chống giả mạo, model MiniFASNet) — KHÁC với luồng CHECK-IN đã có cơ chế thử thách CHỦ
  ĐỘNG (`FaceLivenessCamera` — quay đầu/chớp mắt theo yêu cầu). Màn phản hồi random check
  (`RandomCheckScreen.tsx`) dùng `FacePhotoCapture` (chụp ảnh đơn giản) cho MỌI mode cần face, kể cả
  `location_face_liveness` — KHÔNG có thử thách chủ động. Đây là điểm dễ bị vượt qua bằng ảnh in/màn
  hình hiển thị hơn so với check-in, dù về mặt code không phải "dead code" — là lựa chọn thiết kế
  (thụ động vs chủ động) cần chủ dự án xác nhận có chấp nhận được không.

---

## A. Test trên Backend — Trọng tâm: xác nhận bug ĐÃ ĐƯỢC VÁ

### 1. ✅✅ (Case quan trọng nhất — bác bỏ cáo buộc "dead code") Liveness FAIL → outcome PHẢI fail
- Phản hồi với ảnh KHÔNG đạt liveness (mô phỏng ảnh tĩnh/không có tín hiệu sự sống thật).
- **Kỳ vọng theo code hiện tại (ĐÃ VÁ):** `livenessVerified=false`, `outcome=fail`,
  `failureReason` chứa `liveness_fail`, có violation `liveness_fail` được tạo — xác nhận liveness
  fail THẬT SỰ ảnh hưởng kết quả, không bị bỏ qua.

### 2. Liveness PASS + face PASS + vị trí PASS → `pass`
- **Kỳ vọng:** `outcome=pass`.

### 3. Liveness PASS nhưng face KHÔNG khớp → vẫn `fail` (do face)
- **Kỳ vọng:** `outcome=fail`, `failureReason` chứa `face`, không bị liveness pass che lấp.

### 4. Không tạo trùng violation khi callback gọi lại (idempotency)
- Mô phỏng callback AI gọi 2 lần cho cùng 1 response liveness fail.
- **Kỳ vọng:** chỉ có 1 violation `liveness_fail` duy nhất, không nhân đôi.

---

## B. Test trên Mobile App
### 5. ⚠️ Xác nhận nuance: chỉ chụp ảnh đơn giản, KHÔNG có thử thách chủ động cho mode liveness
- Vào màn phản hồi với check thuộc mode `location_face_liveness`.
- **Kỳ vọng theo code hiện tại:** chỉ yêu cầu chụp 1 ảnh selfie như mode `location_face` thường,
  KHÔNG có bước "quay đầu/chớp mắt theo hướng dẫn" như luồng check-in — xác nhận đúng nuance đã
  phát hiện, cần chủ dự án quyết định có chấp nhận mức độ chống giả mạo thụ ĐỘNG này cho random
  check hay cần nâng lên chủ động như check-in.

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
**Tin quan trọng: bug "dead code" nghiêm trọng trong audit gốc ĐÃ ĐƯỢC VÁ từ trước, không cần sửa
lại — chỉ cần test live để XÁC NHẬN bản vá vẫn hoạt động đúng (case 1 là trọng tâm).** Điểm cần
quyết định nghiệp vụ duy nhất: case 5 (liveness thụ động vs chủ động) — tương tự quyết định đã đưa
ra trước đây cho check-in (`gps_face_liveness`), cần xác nhận mức độ chống giả mạo hiện tại có đủ
cho random check hay cần nâng cấp lên thử thách chủ động giống check-in.
`test_check_response_face.sh` Test 4 hiện chỉ xác nhận `liveness_verified` được lưu đúng, CHƯA assert
outcome/violation khi liveness fail — cần bổ sung test tự động cho phần này để tránh regression
trong tương lai (bug này đã từng tồn tại 1 lần, dễ tái phát nếu không có test khóa lại).
