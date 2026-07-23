# Sprint 1 — Web Admin review

## #1 — Đăng nhập email/mật khẩu

**Trạng thái: ✅ Đã sửa xong (2026-07-22, kiểm chứng qua Playwright thật trên `localhost:3000`)**

### Đã sửa
1. `LoginResponse` type: bỏ field `user` không có thật, thêm `tokenType`.
2. Bỏ hẳn field chết `rememberMe` khỏi `LoginPayload` (không có UI, không được gửi, backend cũng không nhận).
3. **423 (khóa tài khoản) và 403 (TENANT_SUSPENDED)** giờ hiển thị đúng message tiếng Việt — sửa tận gốc ở `api-client.ts`: interceptor giờ ưu tiên field `userMessage` (luôn tiếng Việt, có sẵn cho mọi lỗi nghiệp vụ ở backend) thay vì `message` (thường tiếng Anh) trước khi trả lỗi về cho toàn bộ 47 chỗ gọi API trong app — sửa 1 chỗ, hết lỗi hiển thị tiếng Anh ở toàn bộ ứng dụng, không chỉ màn login.
4. Riêng lỗi 423, backend nhúng thẳng ISO timestamp thô vào message — `LoginForm.tsx` giờ tự parse và hiển thị giờ:phút ngày/tháng/năm dễ đọc thay vì chuỗi ISO.
5. Nén lại `BGR_LOGIN.png` (4.9MB) → `BGR_LOGIN.jpg` (167KB, giảm 97%), thêm `sizes` prop cho `next/image` (hết cảnh báo console).

### Đã test thật qua Playwright (không chỉ đọc code)
Đúng mật khẩu, sai mật khẩu, form rỗng, tài khoản khóa (423), email chưa xác thực (403), tenant bị suspend (403), luồng TOTP/2FA đầy đủ (mã sai → mã đúng → vào dashboard) — tất cả hiển thị đúng tiếng Việt, đúng nghiệp vụ.

### Còn lại (không thuộc phạm vi #1, ghi nhận cho sau)
- Web Admin chưa có test suite tự động (Jest/Playwright CI) — biết trước, ngoài phạm vi.
- Đăng ký tài khoản mới hiện có thể trả 500 nếu quota gửi email Gmail hết hạn mức/ngày (`550 5.4.5 Daily user sending limit exceeded`) — lỗi hạ tầng bên ngoài, không phải do code; nhưng cũng lộ ra một gap thiết kế thật: `EmailService`/`RegisterService` hiện không bắt exception khi gửi mail thất bại, khiến cả giao dịch tạo tài khoản bị hủy theo — nên cân nhắc sửa khi làm tới tính năng Đăng ký (#6)/Quên mật khẩu (#7) để việc gửi mail thất bại không chặn tạo tài khoản.

### Code hiện tại
- Route: `src/app/(auth)/login/page.tsx` → `LoginForm` (`src/features/customer/auth/components/LoginForm.tsx`).
- API call: `authService.login()` (`src/features/customer/auth/services/auth.service.ts:34-37`) → `POST /api/v1/auth/login` qua axios (`NEXT_PUBLIC_API_URL=/api/v1`, Next.js rewrite proxy sang `localhost:8080`).
- Token lưu ở `localStorage` (`fams_access_token`/`fams_refresh_token`, `src/services/auth-token.service.ts`).
- TOTP: xử lý đúng — khi `totpRequired=true` thì đổi form tại chỗ sang nhập mã (`LoginForm.tsx:91-95`), không redirect.
- Đủ link Quên mật khẩu / Đăng ký / Google login / chuyển sang đăng nhập bằng SĐT.

### Vấn đề phát hiện
1. **Sai lệch kiểu dữ liệu response** (`auth.type.ts:39-46`): khai báo `LoginResponse` thiếu `tokenType` (backend có trả) và có thêm field `user` mà **backend không hề trả về** trong `LoginResponse` (đã xác nhận lại phía backend: chỉ có `accessToken, refreshToken, tokenType, expiresIn, totpRequired, pendingToken`). Nếu có chỗ nào đọc `response.user` sẽ luôn `undefined` — cần rà lại toàn bộ chỗ dùng.
2. **`rememberMe` là field chết**: có trong `LoginPayload` và được gửi lên server nhưng **không có ô checkbox nào trên UI** để người dùng bật/tắt — luôn gửi `undefined`. Backend cũng không có field này trong `LoginRequest` nên bị bỏ qua hoàn toàn ở cả 2 đầu.
3. **Không xử lý 423 (khóa tài khoản) và 403 (tenant suspended)** — `LoginForm.tsx:124-135` chỉ bắt riêng 401 và message "has not been verified"; các trường hợp còn lại rơi vào thông báo lỗi chung chung. Đây là gap khớp với thiếu sót đã tìm thấy ở backend review: backend đã trả đúng mã lỗi/message riêng cho từng case (423 `ACCOUNT_LOCKED`, 403 `TENANT_SUSPENDED`) nhưng **web không hiển thị message hữu ích tương ứng** cho người dùng — họ sẽ không biết vì sao không đăng nhập được.
4. Password login không có rule độ dài tối thiểu ở form validate (chỉ required) — chấp nhận được vì đây là *login* chứ không phải *register* (không cần ép rule mạnh khi xác thực mật khẩu cũ), không tính là lỗi.
5. **Không có test nào** (không có script `test` trong `package.json`, không có file `.test.`/`.spec.`).

### Đề xuất sửa
1. Sửa `LoginResponse` type: bỏ field `user` sai, thêm `tokenType`.
2. Bỏ hẳn `rememberMe` (dead field) hoặc — nếu nghiệp vụ thực sự muốn "ghi nhớ đăng nhập", thêm checkbox UI thật và xử lý (refresh token TTL dài hơn) — cần hỏi bạn có muốn tính năng này thật hay bỏ.
3. Thêm nhánh xử lý lỗi cho 423 (hiển thị "Tài khoản bị khóa, thử lại sau [thời gian]") và 403 `TENANT_SUSPENDED` (hiển thị "Doanh nghiệp của bạn đang bị tạm dừng, liên hệ quản trị viên") — dựa theo `errorCode` backend trả (`ACCOUNT_LOCKED`/`TENANT_SUSPENDED`) thay vì chỉ theo HTTP status để tránh nhầm với các lỗi 403 khác (vd RBAC).

### Roadmap test thủ công trên UI (`http://localhost:3000/login`)
1. Đăng nhập đúng `admin@fams.com` / `Admin@1234` → vào dashboard, token lưu trong localStorage (kiểm tra DevTools → Application → Local Storage).
2. Sai mật khẩu 5 lần liên tiếp → lần 6 hiện thông báo lỗi (hiện tại: message chung chung, sau khi sửa: phải hiện rõ đang bị khóa + thời gian).
3. Đăng nhập bằng tài khoản thuộc tenant `dong-a-jsc` (suspended trong seed) → hiện tại: message chung chung; sau khi sửa: phải hiện rõ "doanh nghiệp bị tạm dừng".
4. Bật TOTP cho 1 tài khoản test → login → xác nhận form chuyển sang nhập mã 6 số tại chỗ, không mất context.

### Cập nhật 2026-07-23 — Sửa lỗi độ bền gửi email (theo yêu cầu riêng, ảnh hưởng cả Đăng ký/Quên mật khẩu)
Backend `EmailService` trước đây không bắt lỗi SMTP, khiến Đăng ký/Quên mật khẩu bị hủy toàn bộ nếu gửi mail lỗi — đã sửa (xem `docs/BACKLOG.md` #1 và memory `fams_frontend_integration_testing`). Đã kiểm chứng thật qua Playwright trên Web Admin: đăng ký (`/register`) và quên mật khẩu (`/forgot-password`) đều chạy đúng, nhanh (trước ~5-15s do chờ SMTP timeout, giờ ~0.3s vì gửi mail chạy nền `@Async`), kể cả khi Gmail dev vẫn đang bị chặn quota.

## #2 — Đăng nhập bằng số điện thoại OTP

**Trạng thái: 🟡 Code đã sửa đúng — chờ Firebase project thật để test SMS live (2026-07-23)**

Trước đây `PhoneLoginForm.tsx` gọi `/auth/otp/send {phone}` (endpoint không tồn tại trên backend) rồi `/auth/otp/verify {phone,code}` (sai hoàn toàn shape backend thật nhận: `{firebaseIdToken,deviceId}`) — chưa tích hợp Firebase Web SDK, tính năng không chạy được. Đã sửa: thêm `src/lib/firebase.ts` (lazy-init, không crash trang khi chưa cấu hình), viết lại `PhoneLoginForm.tsx` dùng `signInWithPhoneNumber` thật + reCAPTCHA vô hình, sửa `RegisterForm.tsx` để đăng ký bằng SĐT cũng xác thực Firebase OTP trước (trước đây sẽ luôn bị backend từ chối 400 thiếu `firebaseIdToken`). Đã kiểm chứng qua Playwright: cả 2 trang (`/login/phone`, `/register` với SĐT) hiện thông báo tiếng Việt rõ ràng "chưa khả dụng" thay vì crash/lỗi khó hiểu khi Firebase chưa cấu hình. Chi tiết đầy đủ: `docs/BACKLOG.md` mục #2. Cần bạn cung cấp Firebase Web config vào `.env.local` để test gửi/nhận SMS thật.
