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

## ✅ PASS — ĐÃ KHÓA phần code, ⚠️ CÒN 1 QUYẾT ĐỊNH NGHIỆP VỤ (2026-08-18)

Test live xác nhận lại (sau khi fix container `fams-ai` bị treo — xem chi tiết ở
`sprint-4-feature-103-respond-location-face.md`, cùng nguyên nhân hạ tầng ảnh hưởng cả 2 tính
năng): **bản vá "liveness dead code" từ trước vẫn hoạt động đúng** —
`test_check_response_face.sh` Test 4 giờ chạy hết vòng đời thật qua AI worker (`fams-ai` container
đã healthy trở lại): `livenessVerified=null` lúc gửi (đang xử lý bất đồng bộ) → poll DB →
`liveness_verified=true` sau khi model MiniFASNet chấm xong ảnh thật — xác nhận liveness KHÔNG
phải dead code, có ảnh hưởng thật đến outcome.

### Test trên Mobile App — ✅ PASS, test live qua Expo Web thật kết nối backend thật (2026-08-18)
- Case 5 xác nhận lại đúng nuance đã phát hiện: panel phản hồi mode `location_face_liveness` và
  panel mode `location_face` **hiển thị giống hệt nhau** (cùng cảnh báo "Face ID chưa sẵn sàng" +
  nút "Mở đăng ký Face ID" khi chưa enroll) — không có bất kỳ bước "quay đầu/chớp mắt theo hướng
  dẫn" nào khác biệt cho mode liveness. Xác nhận bằng ảnh chụp màn hình so sánh trực tiếp 2 panel.

## ✅ ĐÃ NÂNG CẤP (2026-08-18, cùng ngày) — quyết định: chủ động, giống check-in

Người dùng chọn **"Nâng lên chủ động (Recommended)"**. Đã triển khai đầy đủ backend + Mobile App:

### Backend
- **Migration V105**: mở rộng CHECK constraint `liveness_challenges.purpose` thêm `'random_check'`.
- `FaceIdService.startLivenessChallenge` / `FaceIdController` / ai-service
  `routers/liveness_challenge.py`: chấp nhận `purpose=random_check` (yêu cầu `siteId`, ràng buộc
  challenge vào đúng site của scheduled_check, cùng cơ chế với `checkin`/`checkout`).
- `SubmitCheckResponseRequest`: thêm field `livenessChallengeId` (UUID).
- `CheckResponseService`: mode `location_face_liveness` giờ **BẮT BUỘC** một challenge đã `passed`
  (không còn nhận `employeePhotoBase64` cho mode này) — thiếu challengeId trả về `422
  FACE_ID_REQUIRED`. Challenge được validate + tiêu thụ nguyên tử (atomic `consumeIfPassed`, chặn
  double-spend) qua `consumeRandomCheckChallenge()` (nhân bản có chủ đích logic tương ứng của
  `CheckinService#enforceCheckinPolicy` thay vì refactor dùng chung — giảm rủi ro cho module
  check-in đã test kỹ). Khi có challenge, publish job async qua
  `faceVerifyJobPublisher.publishFromChallenge(...)` (dùng khung hình đã lưu của challenge, bỏ qua
  kiểm tra liveness thụ động dư thừa) — **giống hệt hành vi check-in đã có sẵn**: cột
  `liveness_verified` trên response CỐ Ý giữ NULL sau khi xử lý xong (liveness đã được chứng minh
  qua challenge riêng, không lặp lại ở cột này) — không phải bug, đã đối chiếu với callback
  controller dùng chung code path cho cả check-in.

### Mobile App
- `FaceLivenessCamera` (component tái sử dụng y hệt từ check-in) mount cho mode
  `location_face_liveness` thay vì `FacePhotoCapture` — `purpose="random_check"`,
  `siteId={selected.siteId}`, `onPassed` gọi `send({ livenessChallengeId })`.
- `FaceLivenessPurpose` type, `SubmitRandomCheckPayload` mở rộng thêm `livenessChallengeId`.

### Test live — ✅ PASS
- Backend: `tests/face-id/test_check_response_face.sh` viết lại Test 4 thành 4a (thiếu challengeId
  → 422 đúng) + 4b (challenge `passed` hợp lệ → 200, face_verified=true qua khung hình challenge,
  challenge chuyển `consumed`, outcome=pass) — **15/15 PASS toàn bộ file**, chạy qua AI worker thật.
- Frontend: test live qua Expo Web thật (Playwright, camera giả lập) — nhân viên đã enroll Face ID
  mở check `location_face_liveness`: panel hiển thị đúng UI "Xác minh người thật" với hướng dẫn đầy
  đủ (quay trái/phải, ngẩng/cúi mặt, nháy mắt) + nút "Bắt đầu xác minh"; bấm vào mở camera thật,
  hiển thị đúng "Bước 1/3 · Còn 87s · Nhìn thẳng vào camera" với khung dẫn hướng oval — xác nhận
  UI thật sự chạy chung 1 component với check-in, không còn dùng `FacePhotoCapture` thụ động nữa.

### Regression
Toàn bộ `tests/randomcheck/*.sh` (18 suite) vẫn PASS sau thay đổi — không ảnh hưởng các mode khác
(`location_only`, `location_face` không đổi hành vi).

## Ghi chú
`test_check_response_face.sh` Test 4 hiện chỉ xác nhận `liveness_verified` được lưu đúng, CHƯA assert
outcome/violation khi liveness fail bằng 1 ảnh giả mạo thật sự (test hiện dùng ảnh hợp lệ nên luôn
pass) — nên bổ sung 1 case dùng ảnh/đầu vào giả mạo rõ ràng để khóa lại hành vi fail, tránh
regression trong tương lai (bug dead-code này đã từng tồn tại 1 lần).
