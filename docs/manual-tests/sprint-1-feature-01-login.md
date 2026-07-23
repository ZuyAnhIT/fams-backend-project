# Kịch bản test thủ công — #1 Đăng nhập email/mật khẩu

Áp dụng cho cả 2 giao diện: **Web Admin** (`http://localhost:3000/login`) và **Mobile App**
(Expo Go trên điện thoại, hoặc Expo Web tại `http://localhost:8082/login` nếu muốn test nhanh
trên máy tính).

Tài khoản có sẵn (dữ liệu seed hiện tại):
| Tài khoản | Mật khẩu | Dùng để test |
|---|---|---|
| `admin@fams.com` | `Admin@1234` | Happy path, sai mật khẩu, khóa tài khoản, TOTP/2FA |
| `hanh.bach@donga.vn` | `Admin@1234` | Tenant bị tạm dừng (Công ty Đông Á, seed sẵn `suspended`) |

ℹ️ **Cập nhật 2026-07-23:** tài khoản Gmail dùng gửi email dev vẫn đang bị Google chặn do vượt
hạn mức gửi/ngày (`Daily user sending limit exceeded`) — hậu quả của việc test dồn dập cả ngày,
không phải lỗi code. Trước đây điều này làm **Đăng ký/Quên mật khẩu báo lỗi 500** hoặc bị treo
5-15 giây; đã sửa (xem mục D) — giờ cả 2 luồng chạy đúng và nhanh **kể cả khi quota vẫn đang bị
chặn**, chỉ riêng email thật sự thì không tới hộp thư. Case "email chưa xác thực" ở mục A vẫn
dùng SQL để chuẩn bị nhanh gọn, không phụ thuộc email có gửi được hay không.

---

## A. Các trường hợp test trên cả 2 giao diện

### 1. Bỏ trống form
- Để trống Email + Mật khẩu, bấm "Đăng nhập".
- **Kỳ vọng:** hiện lỗi "Vui lòng nhập email" / "Vui lòng nhập mật khẩu" ngay dưới từng ô, không gọi API.

### 2. Sai mật khẩu
- `admin@fams.com` / `SaiMatKhau123`.
- **Kỳ vọng:** thông báo tiếng Việt "Email hoặc mật khẩu không đúng" (không phải tiếng Anh).

### 3. Đăng nhập đúng (happy path)
- `admin@fams.com` / `Admin@1234`.
- **Kỳ vọng:** vào thẳng dashboard/trang chủ tương ứng vai trò. Đăng nhập lần nữa xong kiểm tra
  DB: `docker exec fams-postgres psql -U fams_user -d fams_db -t -c "SELECT last_login_at FROM users WHERE email='admin@fams.com';"` phải ra giờ vừa đăng nhập.

### 4. Tài khoản bị khóa tạm thời (423)
- Sai mật khẩu **5 lần liên tiếp** với `admin@fams.com`.
- **Kỳ vọng:** từ lần thứ 5 hiện banner/thông báo đỏ "Tài khoản tạm khóa do đăng nhập sai nhiều
  lần..." kèm giờ được mở khóa lại (không phải chuỗi ISO thô kiểu `2026-07-22T15:18:...Z`).
  Mobile App còn hiện thêm bộ đếm ngược "còn X phút".
- **Dọn dẹp sau khi test xong** (để không ảnh hưởng người khác dùng chung tài khoản demo):
  ```
  docker exec fams-postgres psql -U fams_user -d fams_db -c \
    "UPDATE users SET failed_login_attempts=0, locked_until=NULL WHERE email='admin@fams.com';"
  ```

### 5. Email chưa xác thực (403)
Chuẩn bị 1 tài khoản chưa xác thực bằng SQL (do quota email đang hết, xem lưu ý ở trên):
```
docker exec fams-postgres psql -U fams_user -d fams_db -c "
INSERT INTO users (email, password_hash, display_name, is_active, email_verified)
SELECT 'unverified_manual_test@fams.com', password_hash, 'Unverified Manual Test', true, false
FROM users WHERE email='admin@fams.com'
ON CONFLICT (email) DO NOTHING;"
```
- Đăng nhập `unverified_manual_test@fams.com` / `Admin@1234`.
- **Kỳ vọng:** thông báo tiếng Việt "Email chưa được xác thực. Vui lòng kiểm tra hộp thư..."
  (không phải tiếng Anh).

### 6. Doanh nghiệp (tenant) bị tạm dừng (403)
- Đăng nhập `hanh.bach@donga.vn` / `Admin@1234` (thuộc tenant Đông Á, seed sẵn `suspended`).
- **Kỳ vọng:** thông báo tiếng Việt "Tài khoản doanh nghiệp đang bị tạm dừng. Vui lòng liên hệ
  quản trị viên." — **không** phải thông báo lỗi chung chung.

### 7. Xác thực 2 lớp (TOTP/2FA)
Bật TOTP cho `admin@fams.com` trước (cần 1 app Authenticator thật — Google Authenticator, Authy...):
1. Đăng nhập `admin@fams.com`, vào phần Cài đặt tài khoản → Bật xác thực 2 lớp.
2. Quét mã QR bằng app Authenticator, nhập mã 6 số để xác nhận bật — lưu lại 8 mã dự phòng hiện ra.
3. Đăng xuất, đăng nhập lại `admin@fams.com` / `Admin@1234`.
4. **Kỳ vọng:** không vào thẳng dashboard mà chuyển sang màn "Xác thực 2 Lớp", nhập mã 6 số sai
   trước → báo lỗi, nhập mã đúng từ app Authenticator → vào dashboard bình thường.
5. Thử dùng 1 trong 8 mã dự phòng thay vì mã 6 số — phải đăng nhập được, và mã đó chỉ dùng được
   đúng 1 lần (thử lại lần 2 phải báo lỗi).
6. **Nhớ tắt TOTP lại** sau khi test xong (Cài đặt → Tắt xác thực 2 lớp, cần nhập mật khẩu hoặc
   mã hiện tại) để không ảnh hưởng người test sau.

---

## B. Chỉ áp dụng riêng cho Web Admin

### 8. Đăng nhập bằng số điện thoại / Google
- Bấm "Đăng nhập bằng Số điện thoại" → chuyển đúng màn hình nhập SĐT (không lỗi, không vỡ layout).
- Bấm nút Google → mở popup chọn tài khoản Google thật (cần tài khoản Google thật để đăng nhập
  trọn vẹn, nhưng riêng việc mở đúng popup không lỗi là đã đạt).

### 9. Giao diện responsive
- Thu nhỏ trình duyệt xuống độ rộng điện thoại (< 1024px) → cột hình nền bên trái phải tự ẩn,
  chỉ còn form đăng nhập, có logo FAMS phía trên form.

---

## C. Chỉ áp dụng riêng cho Mobile App

### 10. Chuyển tab Email / Số điện thoại
- Bấm tab "Số điện thoại" → chuyển đúng màn hình OTP riêng, không bị kẹt/trắng màn hình khi
  bấm "Quay lại" trở về tab Email.

### 11. Phiên đăng nhập được giữ khi tắt/mở lại app
- Đăng nhập xong, tắt hẳn app rồi mở lại (hoặc reload trang nếu test qua Expo Web).
- **Kỳ vọng:** vẫn đang đăng nhập, không bị đá về màn hình login.

---

## D. Đăng ký & Quên mật khẩu vẫn chạy đúng dù gửi email lỗi (cả 2 giao diện)

Bối cảnh: trước đây nếu gửi email thất bại (vd Gmail hết hạn mức/ngày như hiện tại), cả giao
dịch Đăng ký/Quên mật khẩu bị hủy theo (Đăng ký trả lỗi 500, không tạo được tài khoản). Đã sửa
tận gốc ở backend (`EmailService`) — giờ giao dịch chính luôn thành công dù gửi mail thất bại,
và việc gửi mail chạy nền (không làm chậm phản hồi API).

### 12. Đăng ký tài khoản mới (email)
- Vào `/register`, điền Họ tên + email mới (chưa từng đăng ký) + mật khẩu.
- **Kỳ vọng:** bấm "Đăng ký"/"Tạo tài khoản" → phản hồi **nhanh** (dưới 1 giây, không phải xoay
  vòng nhiều giây) → chuyển sang màn "Xác thực Email" (Web) hoặc quay về màn Đăng nhập kèm
  thông báo thành công (Mobile App). Xác nhận tài khoản đã được tạo thật:
  ```
  docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT email, email_verified FROM users WHERE email='<email vừa đăng ký>';"
  ```
  phải trả về đúng 1 dòng với `email_verified = f`.
- Vì quota Gmail đang hết, email xác thực sẽ **không** thực sự tới hộp thư — đây là hành vi đúng
  hiện tại (không phải bug); kiểm tra log backend thấy dòng `Failed to send verification email`
  là đủ xác nhận: `docker logs fams-api --tail 50 | grep "Failed to send"`.

### 13. Quên mật khẩu
- Vào `/forgot-password`, nhập `admin@fams.com` (hoặc bất kỳ email đã tồn tại nào).
- **Kỳ vọng:** phản hồi **nhanh** (dưới 1 giây) → hiện màn "Kiểm tra hộp thư của bạn" /
  "Đã gửi email!" — dù thực tế Gmail có gửi được hay không, UI vẫn phải báo thành công (đúng
  chuẩn bảo mật: không tiết lộ gửi thành công hay thất bại cho người dùng cuối).

---

## Ghi chú
- Toàn bộ các case ở mục A (trừ case 5, cần chuẩn bị SQL do quota email) đã được tôi tự động
  test qua trình duyệt thật (Playwright) và xác nhận đạt trên cả 2 giao diện trước khi đưa kịch
  bản này — nếu bạn thấy sai khác so với mô tả "Kỳ vọng", đó có thể là hồi quy mới, báo lại ngay.
- Case 8/9/10/11 tôi **chưa** tự động test được (Google real OAuth cần tài khoản thật; responsive/app
  lifecycle cần mắt người xem trực tiếp) — cần bạn tự kiểm tra.
- Case 12/13 (mục D) đã được tôi tự động test qua Playwright trên cả 2 giao diện, xác nhận chạy
  đúng và nhanh dù quota Gmail vẫn đang bị chặn lúc viết tài liệu này.
