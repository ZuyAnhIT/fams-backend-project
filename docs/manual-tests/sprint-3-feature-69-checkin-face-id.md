# Kịch bản test thủ công — #69 Check-in có Face ID

**Nền tảng: Backend, Mobile App, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code (`submitCheckin` + AI worker) — **đúng
tinh thần AC, nhưng khác cách triển khai ở vài điểm quan trọng:**

- **`selfie_url`: KHÔNG TỒN TẠI trên bản ghi check-in** — AC ghi "lưu score, face_valid,
  selfie_url" nhưng thực tế chỉ có `faceVerified` (boolean) và `faceVerifyScore` (số). Ảnh AI
  worker lưu ra đĩa nhưng không có URL công khai gắn vào checkin record (khác với
  `employeePhotoUrl` — đó là ảnh giải trình riêng, không phải ảnh xác thực khuôn mặt lúc check-in).
- **Face ID là bắt buộc theo POLICY 3 CẤP** (không phải cờ boolean đơn giản như AC ngụ ý):
  `gps_only` / `gps_face` / `gps_face_liveness`, xác định theo `Site.checkinPolicy`, có thể bị ghi
  đè bởi `Shift.checkinPolicyOverride` (ca ghi đè site).
- **Xác thực chạy BẤT ĐỒNG BỘ** — `submitCheckin` trả về ngay (checkin đã tạo, status ban đầu dựa
  theo geofence), job xác thực khuôn mặt đẩy vào hàng đợi Redis, AI worker tính độ tương đồng
  cosine so với embedding đã đăng ký, ngưỡng **0.55** (`AI_FACE_SIMILARITY_THRESHOLD`, có thể cấu
  hình qua env), rồi gọi callback về backend cập nhật kết quả.
- **Fail (điểm <0.55 hoặc lỗi):** chỉ tạo violation (`face_fail`) và chuyển `pending_review` NẾU
  policy hiệu lực thực sự yêu cầu Face ID (`gps_face`/`gps_face_liveness`). Nếu chỉ là ảnh optional
  ở site `gps_only`, fail không có hậu quả gì.
- **Lỗi hạ tầng lúc đẩy job** (Redis down...) tại site bắt buộc Face ID → chuyển `pending_review`
  ngay lập tức (đồng bộ), KHÔNG tạo violation (coi là lỗi hạ tầng, không phải bằng chứng gian lận).
- **Chưa đăng ký Face ID (chưa được duyệt):** bị chặn NGAY LẬP TỨC, đồng bộ, với lỗi
  `FACE_ID_NOT_ENROLLED` — không tạo được check-in luôn, không phải fail sau khi tạo.

---

## A. Test trên Mobile App

### 1. Check-in kèm Face ID tại site yêu cầu `gps_face` — happy path (khuôn mặt đúng)
- Nhân viên đã đăng ký + được duyệt Face ID, check-in tại site yêu cầu `gps_face`, chụp đúng ảnh
  khuôn mặt mình.
- **Kỳ vọng:** check-in tạo ngay, sau vài giây trạng thái cập nhật `valid`/`faceVerified=true`.

### 2. Check-in Face ID sai người (khuôn mặt khác)
- Chụp ảnh khuôn mặt người khác (hoặc điểm tương đồng thấp).
- **Kỳ vọng:** `faceVerified=false`, tạo violation `face_fail`, status chuyển `pending_review`.

### 3. Check-in tại site `gps_only` nhưng vẫn có ảnh optional
- Nếu App vẫn cho chụp ảnh dù site không bắt buộc, thử chụp ảnh không rõ mặt/sai.
- **Kỳ vọng theo code hiện tại:** không có hậu quả gì (không tạo violation, không đổi status) — vì
  policy không yêu cầu Face ID tại site này.

### 4. Check-in khi CHƯA đăng ký Face ID, tại site bắt buộc `gps_face`
- Nhân viên chưa từng đăng ký hoặc chưa được duyệt Face ID, thử check-in tại site yêu cầu Face ID.
- **Kỳ vọng:** bị chặn NGAY (không tạo được check-in), lỗi rõ ràng liên quan "chưa đăng ký Face ID".

### 5. Xác nhận `Shift.checkinPolicyOverride` ghi đè đúng policy của site
- Với 1 site mặc định `gps_only` nhưng 1 ca cụ thể override sang `gps_face`, check-in đúng ca đó.
- **Kỳ vọng:** vẫn bị yêu cầu Face ID dù site mặc định không yêu cầu — xác nhận override hoạt động
  đúng ở cấp ca.

### 6. Mô phỏng lỗi hạ tầng khi đẩy job xác thực (nếu có thể tái hiện)
- Nếu có thể tạm dừng Redis hoặc AI worker, check-in tại site bắt buộc Face ID.
- **Kỳ vọng:** status chuyển `pending_review` NGAY (đồng bộ), KHÔNG tạo violation — khác với case 2
  (fail thật do sai khuôn mặt thì có violation). Nếu không tái hiện được trong môi trường test, ghi
  "không tái hiện được", không phải fail.

### 7. Xác nhận KHÔNG có field `selfie_url` trên response check-in
- Sau case 1, kiểm tra response API/dữ liệu trả về của check-in.
- **Kỳ vọng theo code hiện tại:** không có URL ảnh selfie nào trong response — khớp đúng phát hiện
  "implemented differently", không phải thiếu sót cần vá gấp (chỉ là khác tên/hình thức so với AC).

---

## Ghi chú
Trọng tâm khi test: case 6 (phân biệt lỗi hạ tầng vs fail thật — quan trọng để không oan nhân viên
khi hệ thống AI gặp sự cố) và case 4 (chặn đồng bộ khi chưa đăng ký — trải nghiệm quan trọng, cần
thông báo rõ ràng để nhân viên biết cần đăng ký Face ID trước). Case 7 chỉ là ghi nhận khác biệt so
với AC, không cần vá.

---

## ĐÃ TEST + VÁ (2026-08-17) — gap bảo mật quan trọng phát hiện thêm

**Câu hỏi trực tiếp từ chủ dự án dẫn tới phát hiện này:** "hiện tại lúc check-in đang chụp một ảnh
trên app, hệ thống đang để đăng ký Face ID và check-in dạng nào?"

- **Xác nhận qua đọc code (`FaceCheckinScreen.tsx:273`)**: App chỉ hiện màn liveness challenge chủ
  động (`FaceLivenessCamera`) khi policy = `gps_face_liveness` VÀ đang online. Mọi trường hợp khác
  (policy `gps_face`, hoặc offline) chỉ hiện chụp 1 ảnh tĩnh (`FacePhotoCapture`).
- **Gap phát hiện: nhánh chụp 1 ảnh gửi `requiresLiveness: false`** (`use-checkin-submit.ts:75`,
  `use-checkout-submit.ts:105`, cũ) — nghĩa là kể cả kiểm tra liveness THỤ ĐỘNG (AI tự soi ảnh có
  phải người thật) cũng KHÔNG chạy. Về mặt kỹ thuật, 1 ảnh tĩnh/in ra có thể vượt qua xác thực
  khuôn mặt tại site chỉ yêu cầu `gps_face` (không phải `gps_face_liveness`).
- **Đã hỏi ý kiến chủ dự án — quyết định: bật liveness thụ động cho cả nhánh `gps_face`.**
- **Đã vá:**
  - `use-checkin-submit.ts`, `use-checkout-submit.ts`: `requiresLiveness: !!(livenessChallengeId
    || employeePhotoBase64)` — gửi `true` bất cứ khi nào có ảnh nộp lên.
  - `OfflineSyncService.java` (backend): sửa `faceVerifyJobPublisher.publish(..., false)` thành
    `true` — cùng gap ở nhánh đồng bộ offline (xem #75).
- **Xác nhận qua log backend thật (2026-08-17)**: gọi API check-in với `requiresLiveness=true` +
  ảnh → log `FaceVerifyJobPublisher`: "Face verify job published: ... requiresLiveness=true" —
  xác nhận cờ truyền đúng từ request tới job gửi cho AI worker (`worker.py` dùng đúng cờ này để
  quyết định có chạy `check_liveness()` hay không, xác nhận qua đọc code AI service).
- **Chưa test được đầy đủ qua camera thật** (giả lập headless không tạo ảnh khuôn mặt thật, không
  kiểm chứng được AI có thực sự từ chối ảnh tĩnh hay không) — **cần bạn tự test lại trên thiết bị
  thật**: cầm 1 ảnh in/ảnh trên màn hình khác đưa trước camera khi check-in tại site `gps_face`,
  xác nhận bị từ chối (trước đây sẽ pass, giờ phải bị chặn).
- Script tự động `test_checkin_face.sh` (5/5) và `test_checkin_liveness.sh` (2/2) chạy lại pass,
  không hồi quy.
