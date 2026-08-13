# Kịch bản test thủ công — #10 Xem thông tin cá nhân

Áp dụng cho cả 2 giao diện.

⚠️ **Gap đã biết** (xác nhận lại khi viết kịch bản này — vẫn còn nguyên): `UserProfileResponse`
**không có field** thể hiện trạng thái 2FA (`totpEnabled`) và **không có field** tenant hiện tại
(`activeTenantId`/tên tenant), dù Acceptance Criteria yêu cầu rõ cả hai. Case 3 và 4 dưới đây xác
nhận lại đúng gap này qua UI thật.

---

## A. Test trên cả 2 giao diện

### 1. Xem hồ sơ — happy path
- Đăng nhập, vào màn "Hồ sơ cá nhân" / "Tài khoản của tôi".
- **Kỳ vọng:** hiển thị đầy đủ: họ tên, email, số điện thoại, avatar, ngày sinh, quê quán, giới
  tính, địa chỉ (nếu đã có dữ liệu) — không hiển thị `password_hash`, `totp_secret`, hoặc bất kỳ
  trường nhạy cảm nào ở dạng thô.

### 2. Trạng thái xác thực email/phone hiển thị đúng
- Với tài khoản `admin@fams.com` (email đã verify) và 1 tài khoản mới đăng ký chưa xác thực (tạo
  theo hướng dẫn ở kịch bản #6).
- **Kỳ vọng:** UI phân biệt rõ email đã verify / chưa verify (badge, icon, hoặc dòng chữ), không
  hiển thị giống hệt nhau.

### 3. Trạng thái 2FA — kiểm tra gap
- Với tài khoản đã bật TOTP (bật theo kịch bản #1 case 7 hoặc #12 sắp tới), mở màn hồ sơ.
- **Kỳ vọng theo code hiện tại:** màn Hồ sơ chính (không phải màn Cài đặt bảo mật riêng) **không
  có** thông tin "Đã bật 2FA" lấy từ chính API `/auth/me` — nếu UI vẫn hiển thị được trạng thái
  2FA ở đây, khả năng cao là đang gọi thêm 1 API khác (VD: gọi riêng endpoint TOTP status) để bù
  — xác nhận đúng cách nào đang xảy ra rồi báo lại, vì đây là điểm cần làm rõ trước khi quyết định
  có cần sửa `UserProfileResponse` không.

### 4. Tenant hiện tại — kiểm tra gap
- Với 1 tài khoản thuộc nhiều hơn 1 tenant (nếu có sẵn dữ liệu multi-tenant switching), mở màn hồ
  sơ.
- **Kỳ vọng theo code hiện tại:** API `/auth/me` không trả tenant hiện tại — UI có thể đang lấy
  thông tin này từ chỗ khác (context/state sau khi chọn tenant lúc đăng nhập). Xác nhận UI có
  hiển thị đúng tên tenant đang hoạt động ở đâu đó hợp lý hay không (không nhất thiết phải ở đúng
  màn Hồ sơ) — nếu **hoàn toàn không thấy** tenant hiện tại ở bất kỳ đâu trong app, đó là gap thật
  cần báo lại.

---

## Ghi chú
Case 3/4 không phải "phải fail" — mục đích là xác nhận UI có đang tự bù đắp gap của API hay không.
Nếu UI đã tự lấy dữ liệu qua đường khác và hiển thị đúng, coi như pass thực tế dù API `/auth/me`
gốc thiếu field — chỉ cần báo lại đúng những gì bạn quan sát được.
