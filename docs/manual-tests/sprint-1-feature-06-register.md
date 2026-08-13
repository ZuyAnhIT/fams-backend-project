# Kịch bản test thủ công — #6 Đăng ký tài khoản người dùng

Áp dụng cho cả 2 giao diện. Đăng ký có **2 luồng khác nhau**: bằng **email** (verify qua link
email) và bằng **số điện thoại** (verify qua OTP SMS nội bộ — khác hẳn Firebase Phone Login của
#2, đây là OTP riêng của module đăng ký/đổi SĐT). Ở môi trường dev, OTP SMS **không gửi thật**,
mà log ra console backend — xem hướng dẫn lấy mã ở mục A.4.

---

## A. Đăng ký bằng Email — test trên cả 2 giao diện

### 1. Bỏ trống form
- Để trống Họ tên/Email/Mật khẩu, bấm "Đăng ký".
- **Kỳ vọng:** lỗi validate tại chỗ theo từng ô, không gọi API.

### 2. Mật khẩu không đạt policy
- Nhập mật khẩu `12345678` (chỉ số, không hoa/thường) hoặc `abc` (quá ngắn).
- **Kỳ vọng:** lỗi tiếng Việt rõ ràng "Mật khẩu phải chứa ít nhất 1 chữ hoa, 1 chữ thường và 1 chữ
  số" / "Mật khẩu phải có ít nhất 8 ký tự".

### 3. Email đã tồn tại (kể cả chưa xác thực)
- Đăng ký lại bằng `admin@fams.com` (đã tồn tại).
- **Kỳ vọng:** lỗi "Email này đã được đăng ký. Nếu chưa xác thực, vui lòng kiểm tra hộp thư hoặc
  dùng chức năng gửi lại email xác thực." — không tạo user trùng, không tiết lộ thêm thông tin
  khác về tài khoản đó.

### 4. Đăng ký thành công (happy path)
- Điền Họ tên + email mới hoàn toàn + mật khẩu hợp lệ, bấm "Đăng ký".
- **Kỳ vọng:** phản hồi nhanh (đã sửa async ở #1), chuyển sang màn "Xác thực email" hoặc thông
  báo tương tự. Xác nhận DB:
  ```
  docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT email, email_verified FROM users WHERE email='<email vừa đăng ký>';"
  ```
  → `email_verified = f`.
- Kiểm tra log backend xem có cố gắng gửi email không (dù quota Gmail dev có thể vẫn hết):
  ```
  docker logs fams-api --tail 30 | grep -i "verification email"
  ```

---

## B. Đăng ký bằng Số điện thoại — test trên cả 2 giao diện

### 5. Gửi OTP đăng ký
- Vào form đăng ký, chọn tab/chế độ "Số điện thoại", nhập SĐT mới (chưa từng đăng ký, định dạng
  `+84912345678`), bấm "Gửi mã".
- **Kỳ vọng:** API `POST /auth/register/send-otp` được gọi, hiện màn nhập OTP. Lấy mã OTP thật từ
  log backend (dev mode không gửi SMS thật):
  ```bash
  docker logs fams-api --tail 50 | grep -A2 "OTP CODE"
  ```

### 6. Nhập sai OTP
- Nhập 1 mã 6 số sai bất kỳ ở bước xác nhận.
- **Kỳ vọng:** báo lỗi rõ ràng, không tạo tài khoản.

### 7. Nhập đúng OTP — hoàn tất đăng ký
- Nhập đúng mã OTP lấy từ log ở case 5, hoàn tất form (mật khẩu, họ tên).
- **Kỳ vọng:** tài khoản được tạo, `phone_verified = true` ngay (khác luồng email — không cần xác
  thực thêm bước nào), có thể đăng nhập ngay bằng SĐT + mật khẩu vừa tạo qua `/auth/login`.

### 8. SĐT đã đăng ký và đã verify
- Thử đăng ký lại bằng đúng SĐT vừa tạo ở case 7.
- **Kỳ vọng:** lỗi "Số điện thoại này đã được đăng ký".

### 9. Gửi OTP quá nhiều lần (rate limit)
- Gửi lại OTP cho cùng 1 SĐT (chưa hoàn tất đăng ký) quá 3 lần trong 15 phút.
- **Kỳ vọng:** từ lần thứ 4, báo lỗi giới hạn tần suất, không gửi thêm.

---

## Ghi chú
- Toàn bộ case ở file này **chưa** được tôi tự test qua Playwright ở phiên trước — chỉ riêng case
  4 (đăng ký email happy path) đã được xác nhận đạt trong kịch bản #1 (mục D, case 12), không cần
  test lại nếu đã pass ở đó.
- Nếu môi trường dev không phải `app.sms.dev-mode=true`, case 5 sẽ không thấy log OTP — kiểm tra
  `docker exec fams-api env | grep SMS_DEV_MODE` nếu không thấy log.
