# Kịch bản test thủ công — #3 Đăng nhập Google

Áp dụng cho cả 2 giao diện: **Web Admin** (`http://localhost:3000/login`) và **Mobile App**
(khuyến nghị test trên điện thoại thật hoặc simulator — Google Sign-In native trên Expo Web có
thể fallback sang popup web, xem ghi chú riêng ở mục C).

Cần **1 tài khoản Google thật** (Gmail cá nhân của bạn là đủ) để đăng nhập trọn vẹn.

⚠️ **Phát hiện khi rà lại code trước khi viết kịch bản này** (chưa sửa, cần bạn xác nhận có
đúng ý muốn nghiệp vụ không): Acceptance Criteria của #3 ghi "từ chối email chưa được mời nếu
tenant yêu cầu", nhưng `GoogleLoginService` hiện **không có bất kỳ kiểm tra invite-only nào** —
bất kỳ email Google nào chưa tồn tại trong hệ thống đều được **tự động tạo tài khoản mới** ngay
khi đăng nhập Google lần đầu (không cần được mời trước). Case 5 dưới đây sẽ cho thấy rõ hành vi
thật này — nếu bạn thấy đây là sai với nghiệp vụ mong muốn (VD: nhân viên lạ có thể tự "vào"
tenant bằng cách đăng nhập Google), báo lại để tôi thêm gate invite-only.

---

## A. Các trường hợp test trên cả 2 giao diện

### 1. Đăng nhập Google — tài khoản Google trùng email đã có sẵn (auto-link)
- Chuẩn bị: tài khoản Google thật của bạn phải trùng email với 1 user đã có trong hệ thống
  (hoặc đổi email `admin@fams.com` tạm thời bằng SQL để trùng với Gmail thật của bạn, nhớ đổi
  lại sau khi test xong).
- Bấm nút "Đăng nhập bằng Google" → chọn tài khoản Google → xác nhận quyền truy cập lần đầu.
- **Kỳ vọng:** đăng nhập thành công thẳng vào dashboard, không cần mật khẩu. Kiểm tra DB:
  ```
  docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT google_id, email_verified FROM users WHERE email='<email test>';"
  ```
  phải thấy `google_id` đã được gán và `email_verified = t` (dù trước đó có thể là `f`).
- **Đăng nhập lại lần 2** bằng cùng tài khoản Google → vẫn vào bình thường, không tạo tài khoản
  trùng lặp.

### 2. Huỷ popup / từ chối quyền truy cập
- Bấm "Đăng nhập bằng Google", khi popup Google hiện ra thì bấm nút đóng (X) hoặc "Hủy" thay vì
  chọn tài khoản.
- **Kỳ vọng:** quay lại màn hình đăng nhập bình thường, không có lỗi trắng màn hình/crash, không
  có thông báo lỗi gây hoang mang (im lặng quay lại là chấp nhận được).

### 3. Tenant bị tạm dừng (suspended)
- Cần 1 tài khoản Google trùng email với user thuộc tenant `suspended` (VD: seed sẵn công ty
  Đông Á — có thể tạm gán `google_id` bằng SQL cho `hanh.bach@donga.vn` nếu không có Gmail thật
  trùng, chỉ để test nhánh lỗi qua API/curl thay vì UI thật).
- **Kỳ vọng:** bị từ chối, thông báo tiếng Việt "Tài khoản doanh nghiệp đang bị tạm dừng..."
  giống hệt case tương ứng ở tính năng #1.

### 4. Tài khoản Google đã bật TOTP/2FA — có bị bỏ qua 2FA không?
- Bật TOTP cho tài khoản test (xem kịch bản #1, case 7), tài khoản này có `google_id` đã gán.
- Đăng nhập bằng Google với tài khoản đó.
- ⚠️ **Kỳ vọng theo code hiện tại:** vào thẳng dashboard, **KHÔNG** yêu cầu nhập mã TOTP (Google
  login được thiết kế bỏ qua 2FA vì OAuth được xem là yếu tố xác thực mạnh độc lập). Đây là hành
  vi **chủ đích** theo thiết kế hiện tại — nếu bạn cho rằng nên bắt buộc luôn cả 2FA kể cả khi
  đăng nhập Google (nhất quán với #1 và #2 đều có check 2FA), báo lại để tôi sửa thống nhất.

### 5. Email Google hoàn toàn mới (chưa tồn tại trong hệ thống) — tự tạo tài khoản
- Dùng 1 tài khoản Google thật khác (Gmail phụ, hoặc tạo mới) chưa từng đăng ký/được mời vào
  FAMS.
- Bấm "Đăng nhập bằng Google" → chọn tài khoản đó.
- **Kỳ vọng theo code hiện tại:** đăng nhập thành công ngay lập tức, hệ thống **tự tạo tài khoản
  mới** — không có màn hình "chờ duyệt" hay lỗi "email chưa được mời". Đây chính là điểm khác
  biệt so với Acceptance Criteria đã nêu ở đầu file — xác nhận đúng là hành vi bạn quan sát được,
  rồi cho tôi biết có cần sửa không.

---

## B. Chỉ áp dụng riêng cho Web Admin

### 6. Liên kết tài khoản Google từ trong Cài đặt (không qua màn login)
- Đăng nhập bằng email/mật khẩu bình thường (`admin@fams.com`) → vào Cài đặt tài khoản → mục
  liên kết Google → bấm "Liên kết tài khoản Google" → chọn 1 tài khoản Google thật **chưa** được
  liên kết với ai khác.
- **Kỳ vọng:** liên kết thành công, từ giờ có thể đăng nhập bằng cả mật khẩu lẫn Google cho tài
  khoản này.
- Thử liên kết tài khoản Google đó (đã liên kết ở trên) vào **một user khác** (đăng nhập user B,
  cũng bấm liên kết Google, chọn đúng tài khoản Google đã dùng ở trên).
- **Kỳ vọng:** bị từ chối, lỗi 409 kiểu "Tài khoản Google này đã được liên kết với người dùng
  khác."

### 7. Hủy liên kết Google
- Với tài khoản vừa liên kết ở case 6, vào Cài đặt → "Hủy liên kết Google".
- **Kỳ vọng:** nếu tài khoản **chưa từng đặt mật khẩu** (đăng ký lần đầu bằng Google) → bị chặn,
  báo lỗi "cần đặt mật khẩu trước khi hủy liên kết" (tránh khóa người dùng khỏi tài khoản của
  chính họ). Nếu tài khoản có mật khẩu rồi (như `admin@fams.com`) → hủy liên kết thành công, sau
  đó thử đăng nhập lại bằng Google phải báo không tìm thấy liên kết (rơi về nhánh "email đã tồn
  tại nhưng chưa liên kết" — tự động liên kết lại như case 1, hoặc kiểm tra xem có đúng vậy
  không).

---

## C. Chỉ áp dụng riêng cho Mobile App

### 8. Đăng nhập Google trên thiết bị thật/simulator
- Bấm nút "Đăng nhập bằng Google" trên màn hình login.
- **Kỳ vọng:** mở đúng luồng chọn tài khoản Google native (bottom sheet/dialog hệ thống trên
  Android, hoặc Safari view trên iOS), không crash app, không văng về màn hình trắng.

### 9. Expo Web — hành vi fallback
- Nếu test qua Expo Web (`localhost:8082/login`) thay vì thiết bị thật: bấm nút Google.
- **Kỳ vọng:** hoặc mở đúng popup Google Identity Services (web fallback), hoặc hiện thông báo rõ
  ràng "chỉ hỗ trợ trên ứng dụng di động" nếu tính năng này chủ đích không hỗ trợ web — **không**
  được crash trắng màn hình. Báo lại chính xác hành vi bạn thấy vì đây là phần tôi chưa xác nhận
  được qua code review.

---

## Ghi chú kỹ thuật (tham khảo, không cần đọc để test)
- Endpoint: `POST /api/v1/auth/login/google` — `{idToken, deviceId}` → trả về `LoginResponse`
  chuẩn giống các luồng login khác (`accessToken`, `refreshToken`, `tokenType`, `expiresIn`).
- Liên kết/hủy liên kết: `POST /api/v1/auth/link-google` (cần đăng nhập trước), `POST
  /api/v1/auth/unlink-google`.
- Không có rate-limit riêng cho `/login/google` (khác với `/login` email và `/otp/verify` đều
  có) — Google/OAuth flow tự nó đã có bảo vệ phía Google.
- Có sẵn trang test thủ công lấy Google ID token thật để gọi API trực tiếp (không qua UI):
  `api-server/src/main/resources/static/google-login-test.html`.
- Test tự động backend sẵn có: `tests/auth/test_google_login.sh`, `tests/auth/test_google_link.sh`.

## Chưa tự test được (cần bạn)
Toàn bộ case ở file này đều **chưa** được tôi tự động test qua Playwright — OAuth Google thật
cần tài khoản Google thật + tương tác popup ngoài tầm với của trình duyệt tự động trong môi
trường hiện tại. Case 5 và case 4 đặc biệt quan trọng vì có thể là gap nghiệp vụ thật (không
phải chỉ để xác nhận UI chạy đúng) — làm trước 2 case này rồi báo lại kết quả.
