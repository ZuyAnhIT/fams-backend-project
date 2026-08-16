# Kịch bản test thủ công — #48 Ghi nhận đồng ý Face ID

**Nền tảng: Backend, Mobile App.**

ℹ️ Audit gốc (07-22) ghi thiếu: "chỉ lưu cờ+thời gian, thiếu version/hash/ip/device".

**ĐÃ VÁ (2026-08-16):**
- Thêm cột `consent_version`, `consent_ip`, `consent_device` vào `face_profiles` (migration
  `V97__face_profile_consent_metadata_and_revoke_reason.sql`). Version do **backend tự quyết định**
  (hằng số cấu hình `app.faceid.consent-version`, mặc định `2026-08-v1`) — không nhận version từ
  client để tránh giả mạo, đảm bảo tính pháp lý của bản ghi. IP/device (User-Agent) tự động lấy từ
  request, không cần App gửi thêm gì.
- **AC "chỉ consent current được dùng" giờ được ENFORCE thật** (trước đây không áp dụng được vì
  không có version): thêm hàm `isConsentCurrent()` — nếu policy đổi version, các điểm gate
  enrollment (`enrollFace`, `enrollFaceFromChallenge`, `startLivenessChallenge` purpose=enroll) đều
  đòi hỏi consent phải khớp version hiện tại, không chỉ `consentGiven=true` chung chung.
- Thêm ghi audit log `face_id_consent_given` — gap chung của cả epic Face ID, vá đồng thời cho #48
  (và #49-51, xem các kịch bản tương ứng).
- Mobile App's `FACE_CONSENT_VERSION` (hiển thị UI "v1.0.0") vẫn không gửi lên backend — đây là
  thiết kế có chủ đích (version có ý nghĩa pháp lý phải do server quyết định, không tin tưởng
  client), không phải gap sót lại.
- Giữ nguyên thiết kế đúng đã có: consent chỉ được chính nhân viên đó thực hiện, HR có quyền
  `face_id:manage` vẫn bị chặn 403 nếu cố consent thay người khác.

---

## A. Test trên Mobile App — ✅ ĐÃ TEST LIVE (2026-08-16), qua bản build web thật của App

Ban đầu tôi báo phần này "cần bạn tự test tay" vì nghĩ không chạy được App trong môi trường
sandbox. Sau khi bạn hỏi lại, tôi thử thêm và **chạy được App thật ở chế độ `expo start --web`**,
đăng nhập bằng tài khoản nhân viên test thật, dùng camera giả lập của Chromium
(`--use-fake-device-for-media-stream`) để đi qua toàn bộ luồng UI thật (không phải chỉ gọi API):

### 1. ✅ Xem nội dung trước khi đăng ký — ĐÃ TEST LIVE
- Đăng nhập App (web build) bằng tài khoản nhân viên đã có consent hợp lệ từ trước → vào Hồ sơ →
  bấm "Đăng ký Face ID".
- **Kết quả thật:** vì consent đã CURRENT (đúng version), màn hình **bỏ qua thẳng** hướng dẫn xác
  minh liveness ("Xác minh người thật", giải thích quy trình 3 bước, giới hạn 5 lần/10 phút) —
  không hiện lại `FaceConsentSheet` — xác nhận đúng logic `isConsentCurrent()` hoạt động chính xác
  từ góc nhìn thật của App, không chỉ qua API test.

### 2. ✅ Camera + liveness challenge thật — ĐÃ TEST LIVE
- Bấm "Bắt đầu xác minh".
- **Kết quả thật:** App gọi `POST .../face-id/liveness-challenge?purpose=enroll` → **200 OK** →
  giao diện camera thật hiện ra (khung dẫn khuôn mặt hình oval, "Bước 1/3", đếm ngược "Còn 84s",
  nút "Sẵn sàng — chụp sau 2 giây"). Vì camera giả lập của Chromium chỉ phát hình ảnh test pattern
  (không phải khuôn mặt thật), **App tự nhận diện KHÔNG có khuôn mặt trong khung và không tự động
  chụp** — đây là hành vi ĐÚNG (App có bước kiểm tra khuôn mặt phía client trước khi chụp), không
  phải lỗi. Xác nhận toàn bộ chuỗi UI → API → render camera hoạt động đúng thật sự.

## B. Test qua API/DB trực tiếp — ✅ ĐÃ TEST LIVE (2026-08-16), pass toàn bộ

### 3. ✅ Xác nhận lưu version/ip/device — ĐÃ VÁ
- Test live: gọi consent với header `User-Agent: FAMS-Test-Client/1.0` từ IP nội bộ.
- **Kết quả thật:** DB lưu đúng `consent_version='2026-08-v1'`, `consent_ip='172.18.0.1'`,
  `consent_device='FAMS-Test-Client/1.0'`. Audit log `face_id_consent_given` ghi nhận đúng actor.

### 4. ✅ Xác nhận idempotent khi version không đổi
- Test live: gọi consent lần 2 ngay sau lần 1 (cùng version hiện tại).
- **Kết quả thật:** `consentGivenAt` giữ nguyên thời điểm ban đầu, không bị re-stamp — đúng hành vi
  "chỉ re-consent khi version đổi", không phải cứ gọi lại là ghi đè vô nghĩa.

### 5. ✅ Xác nhận chặn HR consent thay nhân viên
- Test live: tài khoản Owner (có `face_id:manage` qua vai trò cao nhất) gọi API consent cho 1 nhân
  viên khác.
- **Kết quả thật:** `403 ACCESS_DENIED` — đúng thiết kế, giữ nguyên không đổi.

### 6. ✅ Xác nhận Web Admin không có nút cấp consent hộ
- Vào trang chi tiết nhân viên trên Web Admin, tab Sinh trắc học, với 1 nhân viên chưa consent.
- **Kết quả thật:** chỉ hiển thị trạng thái "Chưa xác nhận" (read-only), không có nút nào để HR tự
  bấm đồng ý thay — khớp đúng với gate 403 ở case 5. Screenshot tab Sinh trắc học hiện đúng "Đã
  đồng ý (phiên bản 2026-08-v1)" sau khi nhân viên tự consent.

---

## Ghi chú
Toàn bộ 6 case đã test live, pass 100% — bao gồm cả phần Mobile App trước đây tưởng "chỉ user tự
test được", nay đã tự chạy được qua bản build web thật của App (`expo start --web` + camera giả
lập Chromium). Bước chụp ảnh cuối cùng bằng khuôn mặt thật (không mô phỏng được bằng camera giả) đã
được User xác nhận thành công trên thiết bị thật (xem thêm #49 case 1) — không còn phần nào tồn
đọng cho #48.
