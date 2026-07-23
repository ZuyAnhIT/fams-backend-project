# Tài liệu tích hợp Authentication cho Web và Mobile App

> Cập nhật theo code và API đang chạy ngày 23/07/2026. Base path của module: `/api/v1/auth`.

Tài liệu này là hợp đồng bàn giao cho frontend của bốn nhóm tính năng:

1. Đăng ký bằng số điện thoại và OTP.
2. Đăng ký bằng email và link xác thực.
3. Đăng nhập bằng email hoặc số điện thoại và mật khẩu.
4. Đăng nhập/liên kết Google và đồng bộ tài khoản theo email.

Phần cuối tài liệu có các API dùng chung sau đăng nhập, cách xử lý token, bảng ánh xạ lỗi sang giao diện và những phần backend còn thiếu trước khi production.

## 1. Thông tin chung

### 1.1 Base URL

| Môi trường | Base URL |
|---|---|
| Local | `http://localhost:8080` |
| Staging/Production | Lấy từ biến cấu hình frontend, ví dụ `API_BASE_URL` |

Mọi request JSON phải gửi:

```http
Content-Type: application/json
```

API cần đăng nhập phải gửi:

```http
Authorization: Bearer <accessToken>
```

### 1.2 Cấu trúc response chung

Thành công:

```json
{
  "success": true,
  "message": "Success",
  "data": {}
}
```

Thất bại nghiệp vụ/validation:

```json
{
  "success": false,
  "message": "Thông tin kỹ thuật hoặc chi tiết lỗi",
  "data": null,
  "errorCode": "ERROR_CODE",
  "userMessage": "Thông báo an toàn để hiển thị cho người dùng"
}
```

Riêng validation từng trường, `data` là object có dạng:

```json
{
  "success": false,
  "message": "Validation failed",
  "data": {
    "password": "Mật khẩu phải có ít nhất 8 ký tự",
    "email": "Định dạng email không hợp lệ"
  },
  "errorCode": "VALIDATION_ERROR",
  "userMessage": "Dữ liệu không hợp lệ, vui lòng kiểm tra lại các trường bắt buộc."
}
```

Frontend nên ưu tiên xử lý theo `errorCode`, dùng `data.<field>` cho lỗi tại input và dùng `userMessage` cho toast/dialog. Không phụ thuộc hoàn toàn vào `message` vì đây có thể là thông tin kỹ thuật.

### 1.3 Quy ước dữ liệu

- Số điện thoại nên gửi theo E.164, ví dụ `+84912345678`.
- Backend hiện cũng chuẩn hóa `0912345678` thành `+84912345678`, nhưng frontend vẫn nên chuẩn hóa trước khi gọi API.
- Email được trim và chuyển về chữ thường.
- `deviceId` nên là ID ổn định theo bản cài đặt/trình duyệt, không phải ID tạo mới ở mỗi request. Ví dụ: UUID lưu trong secure storage của app hoặc storage của web.
- Mật khẩu đăng ký: tối thiểu 8 ký tự, có ít nhất một chữ hoa, một chữ thường và một chữ số.
- `displayName`: bắt buộc, tối đa 100 ký tự.
- Khi đăng ký, frontend phải chọn đúng một phương thức: `email` hoặc `phone`; không gửi cả hai trường trong cùng request. Xem lưu ý an toàn tại mục 9.

## 2. Đăng ký bằng số điện thoại — OTP flow

### 2.1 Luồng giao diện

```text
Màn hình nhập phone, displayName, password
        |
        v
POST /api/v1/auth/register/send-otp
        |
        v
Màn hình nhập OTP 6 số + đếm ngược 5 phút
        |
        v
POST /api/v1/auth/register
        |
        v
HTTP 201 -> chuyển sang đăng nhập hoặc tự gọi API login
```

OTP đăng ký do backend quản lý, khác với Firebase Phone Auth tại `POST /api/v1/auth/otp/verify`.

### 2.2 Bước 1 — Gửi OTP đăng ký

`POST /api/v1/auth/register/send-otp`

Public, không cần Bearer token.

Request:

```json
{
  "phone": "+84912345678"
}
```

| Trường | Kiểu | Bắt buộc | Validation |
|---|---|---:|---|
| `phone` | string | Có | 8–15 chữ số, có thể bắt đầu bằng `+`, nên dùng E.164 |

Thành công — `200 OK`:

```json
{
  "success": true,
  "message": "Mã OTP đã được gửi đến số điện thoại của bạn. Có hiệu lực trong 5 phút.",
  "data": null
}
```

Quy tắc server:

- OTP gồm 6 chữ số, hết hạn sau 5 phút.
- Gửi OTP mới sẽ vô hiệu OTP cũ chưa dùng.
- Tối đa 3 lần gửi trong cửa sổ 15 phút cho một số điện thoại.
- API chưa trả `retryAfter`; frontend tự quản lý countdown UX nhưng phải coi HTTP `429` là nguồn sự thật.

Các lỗi:

| HTTP | `errorCode` | Khi nào xảy ra | Xử lý UI |
|---:|---|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu hoặc sai định dạng `phone` | Báo lỗi dưới ô số điện thoại |
| 409 | `DUPLICATE_RESOURCE` | Số điện thoại đã có tài khoản được xác minh | Gợi ý chuyển sang đăng nhập/quên mật khẩu |
| 429 | `OTP_RATE_LIMIT` | Gửi quá 3 lần trong 15 phút | Khóa nút gửi lại và yêu cầu thử sau |
| 500 | `INTERNAL_ERROR` | Lỗi hệ thống/SMS ngoài dự kiến | Hiện lỗi chung, cho phép thử lại |

### 2.3 Bước 2 — Xác minh OTP và tạo tài khoản

`POST /api/v1/auth/register`

Public, không cần Bearer token.

Request dành riêng cho phone flow:

```json
{
  "phone": "+84912345678",
  "password": "TestPass1",
  "displayName": "Nguyễn Văn A",
  "otpCode": "123456",
  "deviceId": "web-bb5b6876-6fd0-4abf-a295-3a8fcad9ea83"
}
```

| Trường | Kiểu | Bắt buộc | Validation/Ghi chú |
|---|---|---:|---|
| `phone` | string | Có | Cùng số đã dùng ở bước gửi OTP |
| `password` | string | Có | Tối thiểu 8 ký tự; có hoa, thường và số |
| `displayName` | string | Có | Tối đa 100 ký tự |
| `otpCode` | string | Có | Đúng 6 chữ số |
| `deviceId` | string | Không | Hiện chưa được dùng khi tạo tài khoản; có thể bỏ khỏi request đăng ký |

Thành công — `201 Created`:

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "emailVerificationRequired": false,
    "phoneVerified": true,
    "message": "Đăng ký thành công! Bạn có thể đăng nhập ngay bây giờ."
  }
}
```

API đăng ký không trả token. Sau HTTP 201, frontend có thể:

- Chuyển về màn đăng nhập và điền sẵn số điện thoại; hoặc
- Tự gọi `POST /api/v1/auth/login` bằng `phone + password` nếu sản phẩm muốn auto-login.

Các lỗi:

| HTTP | `errorCode` | Khi nào xảy ra | Xử lý UI |
|---:|---|---|---|
| 400 | `VALIDATION_ERROR` | Sai format trường, mật khẩu yếu | Hiện lỗi tại field tương ứng |
| 400 | `INVALID_ARGUMENT` | Thiếu OTP, OTP sai/hết hạn/đã dùng hoặc nhập sai quá nhiều | Giữ ở màn OTP; dùng `message` để biết chi tiết |
| 409 | `DUPLICATE_RESOURCE` | Phone đã đăng ký | Chuyển sang đăng nhập |

Chi tiết số lần nhập OTP:

- Tối đa 5 lần nhập sai cho OTP hiện tại.
- Mỗi lần sai, `message` cho biết số lần còn lại.
- Khi hết lượt hoặc hết hạn, người dùng phải quay lại bước gửi OTP.
- OTP thành công chỉ dùng được một lần.

## 3. Đăng ký bằng email — verify link flow

### 3.1 Luồng giao diện

```text
Màn hình đăng ký email
        |
        v
POST /api/v1/auth/register
        |
        v
Màn hình "Kiểm tra email" + nút gửi lại
        |
        v
Người dùng mở link GET /api/v1/auth/verify-email?token=...
        |
        v
Xác minh thành công -> về màn đăng nhập
```

### 3.2 Bước 1 — Tạo tài khoản và gửi email

`POST /api/v1/auth/register`

Request dành riêng cho email flow:

```json
{
  "email": "alice@example.com",
  "password": "TestPass1",
  "displayName": "Alice Nguyen",
  "deviceId": "web-bb5b6876-6fd0-4abf-a295-3a8fcad9ea83"
}
```

Không gửi `phone` hoặc `otpCode` trong flow này.

Thành công — `201 Created`:

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "emailVerificationRequired": true,
    "phoneVerified": false,
    "message": "Đăng ký thành công! Vui lòng kiểm tra email a***@example.com để xác thực tài khoản trước khi đăng nhập."
  }
}
```

Frontend chuyển sang màn “Kiểm tra email”, hiển thị email đã che hoặc email đang nhập, nút “Mở ứng dụng email” nếu nền tảng hỗ trợ và nút “Gửi lại email”. Không tự đăng nhập ở bước này.

Các lỗi chính:

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Email sai format, mật khẩu yếu, thiếu tên |
| 400 | `INVALID_ARGUMENT` | Không có cả email lẫn phone |
| 409 | `DUPLICATE_RESOURCE` | Email đã tồn tại, kể cả tài khoản chưa xác minh |

Với email đã tồn tại nhưng chưa xác minh, không gọi đăng ký lại; gọi API gửi lại email ở mục 3.4.

### 3.3 Bước 2 — Xác minh link email

`GET /api/v1/auth/verify-email?token=<verificationToken>`

Public, không cần Bearer token. Token tồn tại 24 giờ và chỉ dùng một lần.

Thành công — `200 OK`:

```json
{
  "success": true,
  "message": "Email verified successfully. You can now log in.",
  "data": null
}
```

Các lỗi:

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `MISSING_PARAMETER` | URL thiếu query `token` |
| 400 | `INVALID_ARGUMENT` | Token sai, hết hạn hoặc đã dùng |
| 404 | `RESOURCE_NOT_FOUND` | User tương ứng không còn tồn tại |

Hiện link trong email gọi thẳng API và trả JSON. Để có UX hoàn chỉnh, web cần trang kết quả xác minh hoặc backend cần đổi link email sang route frontend; xem mục 9.

### 3.4 Gửi lại email xác minh

`POST /api/v1/auth/resend-verification`

Request:

```json
{
  "email": "alice@example.com"
}
```

Thành công — luôn `200 OK` nếu request hợp lệ:

```json
{
  "success": true,
  "message": "If an account with that email exists and is not yet verified, a new verification email has been sent.",
  "data": null
}
```

Vì chống dò tài khoản, response giống nhau trong các trường hợp: email không tồn tại, email đã xác minh, gửi thành công hoặc đã chạm rate limit. Rate limit hiện tại là tối đa 3 lần/10 phút theo email và bị xử lý im lặng. Frontend luôn hiển thị thông báo trung tính như “Nếu tài khoản hợp lệ, email mới đã được gửi”.

### 3.5 Đăng nhập trước khi xác minh

Nếu mật khẩu đúng nhưng email chưa xác minh, `POST /login` trả:

```json
{
  "success": false,
  "message": "Email is not verified",
  "data": null,
  "errorCode": "EMAIL_NOT_VERIFIED",
  "userMessage": "Email chưa được xác thực. Vui lòng kiểm tra hộp thư và xác thực email của bạn."
}
```

HTTP status: `403 Forbidden`. Frontend hiển thị CTA “Gửi lại email xác thực”.

## 4. Đăng nhập bằng email hoặc số điện thoại và mật khẩu

### 4.1 API đăng nhập

`POST /api/v1/auth/login`

Public, không cần Bearer token.

Email:

```json
{
  "identifier": "alice@example.com",
  "password": "TestPass1",
  "deviceId": "web-bb5b6876-6fd0-4abf-a295-3a8fcad9ea83"
}
```

Số điện thoại:

```json
{
  "identifier": "+84912345678",
  "password": "TestPass1",
  "deviceId": "android-28ab20d6-6fc2-427d-9853-50639af91099"
}
```

Lưu ý quan trọng: tên trường là `identifier`, không phải `email` hoặc `phone`.

| Trường | Kiểu | Bắt buộc | Ghi chú |
|---|---|---:|---|
| `identifier` | string | Có | Email hoặc số điện thoại |
| `password` | string | Có | DTO yêu cầu ít nhất 8 ký tự |
| `deviceId` | string | Không | Thiếu thì backend lưu là `unknown` |

### 4.2 Thành công bình thường

`200 OK`:

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "activeTenantId": "660e8400-e29b-41d4-a716-446655440001",
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "a3f1c2...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "totpRequired": false,
    "pendingToken": null
  }
}
```

- `activeTenantId` có thể là `null` nếu user chưa thuộc doanh nghiệp nào.
- `accessToken` mặc định sống 15 phút.
- `refreshToken` mặc định sống 30 ngày và được rotate sau mỗi lần refresh.
- Sau khi lưu token, gọi `GET /api/v1/auth/me` để lấy hồ sơ và `googleLinked`.

### 4.3 Nhánh tài khoản có TOTP

Nếu user bật TOTP, bước login đầu trả `200 OK` nhưng chưa đăng nhập hoàn tất:

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "userId": null,
    "activeTenantId": null,
    "accessToken": null,
    "refreshToken": null,
    "tokenType": "Bearer",
    "expiresIn": 0,
    "totpRequired": true,
    "pendingToken": "3f2a1b4c-..."
  }
}
```

Frontend phải chuyển sang màn nhập mã Authenticator và gọi:

`POST /api/v1/auth/login/totp`

```json
{
  "pendingToken": "3f2a1b4c-...",
  "code": "123456"
}
```

Hoặc dùng `backupCode` thay cho `code`. `pendingToken` hết hạn sau 5 phút và chỉ dùng một lần. Thành công trả cặp access/refresh token theo cùng cấu trúc login.

### 4.4 Các lỗi đăng nhập

| HTTP | `errorCode` | Khi nào xảy ra | Hành vi UI đề xuất |
|---:|---|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu identifier/password hoặc password dưới 8 ký tự | Báo lỗi tại field |
| 401 | `INVALID_CREDENTIALS` | Sai identifier/password, account không tồn tại/không active | Thông báo chung, không tiết lộ tài khoản tồn tại |
| 403 | `EMAIL_NOT_VERIFIED` | Tài khoản có email chưa xác minh | Hiện nút gửi lại email |
| 403 | `TENANT_SUSPENDED` | Doanh nghiệp đang active bị tạm dừng | Hiện thông báo liên hệ quản trị viên |
| 423 | `ACCOUNT_LOCKED` | Sai mật khẩu quá nhiều lần | Hiện thời điểm được thử lại |

Backend khóa tài khoản sau lần sai thứ 5 trong 30 phút. `ACCOUNT_LOCKED.message` và `userMessage` chứa thời điểm `lockedUntil` dạng ISO timestamp; frontend nên format theo timezone người dùng.

## 5. Đăng nhập Google và đồng bộ hai chiều

### 5.1 Khái niệm token

Frontend đăng nhập với Google trước, nhận Google ID token, rồi gửi ID token đó cho backend. Không gửi Google access token và không gửi authorization code vào API này.

- Web Google Identity Services: dùng `response.credential` làm `idToken`.
- Android/iOS: cấu hình thư viện Google Sign-In để request ID token cho Web OAuth client/backend audience, sau đó dùng trường `idToken` trả về.
- Audience của token phải khớp chính xác `GOOGLE_CLIENT_ID` trên backend. Nếu app native dùng một client ID khác mà không request token cho Web client ID, backend trả 401.

### 5.2 API login Google

`POST /api/v1/auth/login/google`

Public, không cần Bearer token.

```json
{
  "idToken": "eyJhbGciOiJSUzI1NiJ9...",
  "deviceId": "web-bb5b6876-6fd0-4abf-a295-3a8fcad9ea83"
}
```

| Trường | Kiểu | Bắt buộc | Ghi chú |
|---|---|---:|---|
| `idToken` | string | Có | Google ID token còn hạn, đúng audience |
| `deviceId` | string | Không | Thiếu thì backend dùng `google` |

Thành công trả cùng `LoginResponse` ở mục 4.2.

### 5.3 Quy tắc đồng bộ tài khoản

| Trạng thái trước khi Google login | Backend xử lý | Kết quả |
|---|---|---|
| Chưa có user cùng Google ID/email | Tạo user từ `email`, `name`, `picture`; email được coi là đã xác minh | Tài khoản Google-only, `passwordHash=null` |
| Đã đăng ký email/password cùng email, chưa link Google | Tự link `googleId`; chỉ lấy ảnh Google nếu avatar hiện tại trống; tự xác minh email | Một user dùng được cả Google và password |
| Đã link đúng Google ID | Dùng user hiện tại | Login bình thường, không tạo bản ghi trùng |
| Google email chưa được Google xác minh | Từ chối | HTTP 401 |
| Tài khoản FAMS không active | Từ chối | HTTP 401 |

“Hai chiều” trong code hiện tại có nghĩa:

- Email/password trước → Google sau: tự ghép theo email, không tạo user trùng.
- Google trước → password sau: dùng “Quên mật khẩu” cho đúng email Google để đặt mật khẩu lần đầu, sau đó có thể đăng nhập bằng cả hai cách.

Google login hiện được thiết kế không yêu cầu bước TOTP của FAMS và không bị chặn bởi `lockedUntil` do sai password. Frontend không chuyển sang màn TOTP sau Google login.

### 5.4 Các lỗi Google login

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu/rỗng `idToken` |
| 401 | `REQUEST_ERROR` | Token sai, hết hạn, sai audience hoặc Google email chưa verified |
| 401 | `INVALID_CREDENTIALS` | User FAMS không active |
| 403 | `TENANT_SUSPENDED` | Tenant active bị tạm dừng |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Không gửi `Content-Type: application/json` |

Nếu popup Google bị hủy hoặc SDK Google báo lỗi trước khi có ID token, đó là lỗi phía client; không gọi backend và hiển thị trạng thái tương ứng.

### 5.5 Liên kết Google khi user đang đăng nhập

`POST /api/v1/auth/link-google`

Cần Bearer token FAMS.

```json
{
  "idToken": "eyJhbGciOiJSUzI1NiJ9..."
}
```

Thành công — `200 OK`:

```json
{
  "success": true,
  "message": "Success",
  "data": null
}
```

Lỗi chính:

- `401 REQUEST_ERROR`: Google token không hợp lệ.
- `409 DUPLICATE_RESOURCE`: Google account đã link với FAMS user khác.
- `401` không có `errorCode`: Bearer token FAMS thiếu/hết hạn và bị security filter chặn.

Sau thành công gọi lại `GET /api/v1/auth/me`; `data.googleLinked` phải là `true`.

### 5.6 Gỡ liên kết Google

`POST /api/v1/auth/unlink-google`

Cần Bearer token, không có request body.

Thành công trả `200` với `data: null`. Backend chỉ cho unlink khi tài khoản đã có password; Google-only account chưa đặt password nhận `400 INVALID_STATE`. UI cần hướng người dùng qua quên mật khẩu/đặt mật khẩu trước.

## 6. API dùng chung sau đăng nhập

### 6.1 Lấy hồ sơ hiện tại

`GET /api/v1/auth/me`

Cần Bearer token.

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "alice@example.com",
    "phone": null,
    "displayName": "Alice Nguyen",
    "avatarUrl": "https://example.com/avatar.jpg",
    "dateOfBirth": null,
    "hometown": null,
    "gender": null,
    "address": null,
    "googleLinked": true,
    "createdAt": "2026-07-23T13:48:29.362694Z",
    "updatedAt": "2026-07-23T13:48:29.362694Z",
    "active": true
  }
}
```

Lưu ý field JSON thực tế là `active`, không phải `isActive`.

### 6.2 Refresh token

`POST /api/v1/auth/refresh-token`

Public, không cần Bearer token.

```json
{
  "refreshToken": "a3f1c2..."
}
```

Thành công `200` trả `LoginResponse` với access token mới và refresh token mới. Backend rotate token: refresh token cũ bị thu hồi ngay. Frontend phải ghi đè cả hai token một cách atomic; không được tiếp tục dùng refresh token cũ.

Lỗi:

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu/rỗng refresh token |
| 401 | `REQUEST_ERROR` | Token không tồn tại, đã revoke, hết hạn hoặc account inactive |
| 403 | `TENANT_SUSPENDED` | Tenant đang active bị suspend |
| 404 | `RESOURCE_NOT_FOUND` | User đã bị xóa |

Luồng interceptor đề xuất:

```text
API nghiệp vụ trả 401 do access token hết hạn
        |
        v
Chỉ một request gọi POST /refresh-token
        |
        +-- 200: lưu cả token mới, phát lại các request đang chờ
        |
        +-- 401/403/404: xóa session local, về màn đăng nhập
```

Không refresh lặp vô hạn và không chạy nhiều request refresh song song.

### 6.3 Logout thiết bị hiện tại

`POST /api/v1/auth/logout`

Cần Bearer access token và body:

```json
{
  "refreshToken": "a3f1c2..."
}
```

Thành công `200`, `data: null`. Sau response, frontend xóa token và dữ liệu session local dù việc điều hướng sau đó có lỗi.

## 7. State machine đề xuất cho frontend

```text
UNAUTHENTICATED
  |-- email register 201 ------------> AWAITING_EMAIL_VERIFICATION
  |-- phone send OTP 200 ------------> AWAITING_PHONE_OTP
  |-- phone register 201 ------------> READY_TO_LOGIN
  |-- password login + TOTP ----------> AWAITING_TOTP
  |-- password/Google login + tokens -> AUTHENTICATED

AWAITING_EMAIL_VERIFICATION
  |-- resend 200 ---------------------> giữ nguyên màn hình
  |-- verify link 200 ----------------> READY_TO_LOGIN

AWAITING_TOTP
  |-- login/totp 200 + tokens --------> AUTHENTICATED
  |-- pending token expired ----------> UNAUTHENTICATED

AUTHENTICATED
  |-- app start ----------------------> refresh token nếu cần -> GET /me
  |-- logout -------------------------> UNAUTHENTICATED
```

## 8. Bảng xử lý lỗi dùng chung

| HTTP/Code | UI mặc định |
|---|---|
| `400 VALIDATION_ERROR` | Gắn lỗi theo `data.<field>` |
| `400 INVALID_ARGUMENT` | Hiển thị `message` gần bước đang thao tác |
| `400 MALFORMED_REQUEST` | Báo yêu cầu không hợp lệ; ghi log client để debug |
| `401 INVALID_CREDENTIALS` | “Thông tin đăng nhập không đúng” |
| `401 REQUEST_ERROR` | Token bên thứ ba/session không hợp lệ; cho thử lại |
| `401` từ security filter | Thử refresh một lần; thất bại thì logout local |
| `403 EMAIL_NOT_VERIFIED` | CTA gửi lại email xác thực |
| `403 TENANT_SUSPENDED` | Màn chặn và hướng liên hệ quản trị viên |
| `409 DUPLICATE_RESOURCE` | Gợi ý đăng nhập hoặc dùng tài khoản khác |
| `423 ACCOUNT_LOCKED` | Hiện thời gian mở khóa |
| `429 OTP_RATE_LIMIT` | Disable gửi lại OTP và hiện chờ |
| `500 INTERNAL_ERROR` | Thông báo chung, retry có kiểm soát |

## 9. Trạng thái xác minh và các phần còn thiếu

### 9.1 Đã xác minh trên backend đang chạy

| Nhóm | Kết quả ngày 23/07/2026 |
|---|---|
| Phone register: gửi OTP, OTP sai, OTP đúng, login ngay, duplicate phone | 6/6 đạt |
| Email register: happy path, validation, duplicate, chặn login khi chưa verify | 12/12 đạt |
| Email verify token: login trước verify 403, verify 200, login sau verify 200 có token | Đạt end-to-end qua API/Redis |
| Google endpoint: validation, token giả/sai audience, public route, content type | 6/6 đạt |
| Google happy path | Có dữ liệu thực tế cho cả Google-only và account có password + Google; vẫn nên chạy lại checklist trình duyệt ở mục 10 khi bàn giao frontend |

### 9.2 Chưa đủ để gọi là production-ready

1. **SMS thật cho OTP đăng ký chưa được tích hợp.** `SmsService` hiện chỉ log OTP khi `app.sms.dev-mode=true`. Khi tắt dev mode, code ném `UnsupportedOperationException`; cần tích hợp ESMS/Twilio hoặc nhà cung cấp đã chọn, cấu hình secret, retry và theo dõi delivery.
2. **Backend chưa chặn request đăng ký có đồng thời email và phone.** Hiện code ưu tiên email flow và có thể lưu phone chưa xác minh. Frontend bắt buộc gửi đúng một trường; backend nên bổ sung validation XOR để tránh gắn số điện thoại chưa chứng minh quyền sở hữu.
3. **Link verify email chưa có trang UI kết quả.** Link hiện mở thẳng API JSON. Cần chọn một trong hai cách: email trỏ vào route frontend rồi frontend gọi API, hoặc backend verify xong redirect về frontend với trạng thái an toàn.
4. **Một số script test cũ còn dùng body `{ "email": ... }` cho `/auth/login`.** Contract hiện tại là `{ "identifier": ... }`; cần cập nhật các script cũ trước khi coi toàn bộ auth regression suite là xanh.
5. **Tài liệu `.env.example` về phone registration còn mô tả flow Firebase cũ.** Firebase hiện chỉ còn liên quan đến endpoint đăng nhập phone bằng Firebase ID token; phone registration mới dùng OTP do backend quản lý.
6. **Cấu hình production cần được khóa lại:** domain CORS thật, `APP_BASE_URL`, Gmail/SMTP, Google authorized origins/redirects, secret JWT/TOTP, chính sách lưu token trên web/mobile và monitoring lỗi gửi email/SMS.

Không nên đưa phone registration ra production trước khi hoàn tất mục 1 và 2.

## 10. Checklist bàn giao frontend

### Phone registration

- [ ] Chỉ gửi `phone`, không gửi `email`.
- [ ] Có countdown OTP 5 phút và nút gửi lại.
- [ ] Xử lý 400 OTP sai/hết hạn, 409 duplicate, 429 rate limit.
- [ ] Sau 201 chuyển login hoặc tự login bằng `identifier=phone`.
- [ ] Test trên SMS provider staging, không dùng OTP đọc từ log.

### Email registration

- [ ] Chỉ gửi `email`, không gửi `phone`/`otpCode`.
- [ ] Có màn chờ xác minh và gửi lại email.
- [ ] Xử lý `EMAIL_NOT_VERIFIED` ở login.
- [ ] Có trang web thân thiện cho link thành công/thất bại/hết hạn.

### Password login

- [ ] Body dùng `identifier`.
- [ ] Lưu `deviceId` ổn định.
- [ ] Xử lý `totpRequired=true` trước khi coi session là authenticated.
- [ ] Gọi `/me` sau khi có token.
- [ ] Có refresh single-flight và rotate refresh token.

### Google

- [ ] Web/native lấy đúng Google ID token cho backend audience.
- [ ] Gửi `response.credential`/`idToken`, không gửi Google access token.
- [ ] Test tài khoản mới qua Google.
- [ ] Test email/password trước rồi Google cùng email: không tạo user trùng, `/me.googleLinked=true`.
- [ ] Test Google-only rồi đặt password qua quên mật khẩu.
- [ ] Test link/unlink trong trang hồ sơ.
- [ ] Test popup cancel, token sai audience và account bị Google từ chối.

### URL kiểm tra local

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Trang Google manual test: `http://localhost:8080/google-login-test.html`
