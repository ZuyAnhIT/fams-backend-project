# Kịch bản test thủ công — #2 Đăng nhập bằng số điện thoại OTP

⚠️ **Trạng thái hiện tại:** Code đã sửa đúng theo kiến trúc Firebase Phone Auth thật trên
cả 3 bên, nhưng **chưa có dự án Firebase thật** nên chưa thể test luồng gửi/nhận SMS thật.
Phần **A** dưới đây test được **ngay bây giờ** (không cần Firebase). Phần **B** chỉ test được
**sau khi** bạn hoàn tất tạo dự án Firebase (xem hướng dẫn tôi đã gửi) và với Mobile App còn
cần build lại qua EAS.

---

## A. Test được ngay bây giờ (không cần Firebase)

### 1. Backend — chưa cấu hình Firebase → 503 rõ ràng, không crash
```bash
curl -s -X POST http://localhost:8080/api/v1/auth/otp/verify \
  -H "Content-Type: application/json" -d '{"firebaseIdToken":"bat-ky-gia-tri-gi"}'
```
**Kỳ vọng:** HTTP 503, message "Firebase authentication is not configured on this server".

### 2. Backend — rate limit theo IP
Gọi lệnh ở case 1 liên tục 11 lần.
**Kỳ vọng:** 4 lần đầu ra 503 (nếu tính cả các lần gọi test khác trước đó có thể ít hơn), từ
lúc vượt quá `OTP_RATE_LIMIT_MAX` (mặc định 10) trong 15 phút → chuyển sang HTTP 429, message
"Bạn đã yêu cầu mã OTP quá nhiều lần...".

### 3. Web Admin — `/login/phone`
- Mở `http://localhost:3000/login/phone`, nhập số điện thoại bất kỳ, bấm "Gửi mã OTP".
- **Kỳ vọng:** hiện thông báo đỏ "Đăng nhập bằng số điện thoại hiện chưa khả dụng. Vui lòng
  đăng nhập bằng email hoặc thử lại sau." — **không** phải trang trắng/lỗi 500.

### 4. Web Admin — Đăng ký bằng số điện thoại
- Mở `http://localhost:3000/register`, điền Họ tên + số điện thoại (không phải email) + mật khẩu.
- **Kỳ vọng:** cùng thông báo "chưa khả dụng" như case 3, form không bị treo/vỡ.

### 5. Mobile App — màn hình đăng nhập OTP
- Mở app (Expo Web tại `http://localhost:8082/phone-login` hoặc trong Expo Go), nhập số điện
  thoại, bấm "Gửi OTP".
- **Kỳ vọng:** hiện banner đỏ "Đăng nhập bằng số điện thoại chỉ hỗ trợ trên ứng dụng di động."
  — đây là do Expo Web không hỗ trợ Firebase native SDK; trên **điện thoại thật** (sau khi có
  Firebase + EAS build) sẽ khác, xem mục B.

---

## B. Test sau khi có dự án Firebase thật (+ EAS build cho Mobile)

Chuẩn bị: đã hoàn tất hướng dẫn tạo Firebase project, đã đưa tôi Project ID + service account
JSON (backend) + Web config (`fams-front-web-project/.env.local`) + `google-services.json`
(mobile). Với Mobile App, cần chạy thêm 1 lần:
```bash
cd fams-front-app-project
eas build --profile development --platform android   # hoặc --platform ios
```
rồi cài file build ra lên điện thoại thật/simulator (không dùng Expo Go được cho tính năng này).

Khuyến nghị: dùng **số điện thoại test** đã đăng ký trong Firebase Console (Authentication →
Settings → Phone numbers for testing) kèm mã OTP cố định — tránh tốn hạn mức SMS thật (free tier
chỉ 10 SMS/ngày) và không cần chờ tin nhắn thật.

### 6. Đăng nhập bằng SĐT — happy path (Web + Mobile)
- Cần 1 tài khoản FAMS đã có sẵn số điện thoại (hoặc dùng bước 8 để tạo mới).
- Nhập số điện thoại test → nhận/nhập mã OTP cố định đã cấu hình → bấm xác nhận.
- **Kỳ vọng:** đăng nhập thành công, vào đúng dashboard/màn hình chính.

### 7. Sai mã OTP
- Nhập sai mã OTP ở bước xác nhận.
- **Kỳ vọng:** Firebase báo lỗi ngay tại bước này (không cần gọi tới backend) — thông báo tiếng
  Việt "Mã OTP không chính xác."

### 8. Đăng ký tài khoản mới bằng SĐT (Web)
- Vào `/register`, điền Họ tên + số điện thoại mới (chưa từng đăng ký) + mật khẩu → gửi form.
- **Kỳ vọng:** chuyển sang bước nhập OTP ngay trong form đăng ký (không phải đăng ký xong mới
  xác thực như luồng email) → nhập đúng OTP → tài khoản được tạo **và** tự động đăng nhập luôn.

### 9. Tài khoản có bật TOTP/2FA vẫn đăng nhập qua SĐT
- Bật TOTP cho 1 tài khoản có số điện thoại (xem kịch bản #1, case 7).
- Đăng nhập bằng SĐT của tài khoản đó.
- **Kỳ vọng:** **không** vào thẳng dashboard — phải chuyển sang màn hình "Xác thực 2 Lớp" giống
  hệt khi đăng nhập bằng email/mật khẩu, nhập đúng mã TOTP mới vào được. (Đây là lỗ hổng bảo mật
  thật đã tìm thấy và sửa — trước đây đăng nhập qua SĐT bỏ qua 2FA hoàn toàn.)

### 10. Rate limit trong thực tế
- Thử gửi/xác nhận OTP hơn 10 lần liên tục trong 15 phút (kể cả từ số điện thoại khác nhau, vì
  giới hạn tính theo IP của thiết bị/máy, không theo số điện thoại).
- **Kỳ vọng:** từ lần thứ 11 trở đi, backend trả lỗi "Bạn đã yêu cầu mã OTP quá nhiều lần..."
  ngay cả khi Firebase ID token hợp lệ.

---

## Ghi chú
- Case 1-5 (mục A) đã được tôi tự động test và xác nhận đạt trước khi đưa kịch bản này.
- Case 6-10 (mục B) tôi **chưa** tự test được — cần dự án Firebase thật + build EAS thật, ngoài
  khả năng của tôi trong môi trường hiện tại. Sau khi bạn hoàn tất, báo tôi để cùng chạy lại.
- Test tự động phía backend đã có sẵn: `tests/auth/test_otp_login.sh` (cần `FIREBASE_API_KEY`
  hoặc `FIREBASE_ID_TOKEN` env var) và `tests/auth/test_register_phone_otp.sh` — chạy được ngay
  khi có Firebase config, không cần sửa gì thêm.
