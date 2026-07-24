# Tài liệu tích hợp Authentication cho Web và Mobile App

> Cập nhật theo code và API đang chạy ngày 24/07/2026. Base path của module: `/api/v1/auth`.

Tài liệu này là hợp đồng bàn giao cho frontend của các nhóm tính năng:

1. Đăng ký bằng số điện thoại và OTP.
2. Đăng ký bằng email và link xác thực.
3. Đăng nhập bằng email hoặc số điện thoại và mật khẩu.
4. Đăng nhập nhanh bằng OTP điện thoại qua Firebase (native).
5. Đăng nhập/liên kết Google và đồng bộ tài khoản theo email.
6. Quên mật khẩu / đặt lại mật khẩu qua email.
7. Đổi mật khẩu khi đã đăng nhập.
8. Đăng xuất và quản lý phiên đăng nhập (danh sách thiết bị, đăng xuất chọn lọc).
9. Cập nhật hồ sơ cá nhân — bao gồm avatar (upload file), thêm/đổi email hoặc số điện thoại còn thiếu.

Phần cuối tài liệu có state machine đề xuất, bảng ánh xạ lỗi sang giao diện, checklist bàn giao và những phần backend còn thiếu trước khi production.

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

Riêng request bị chặn bởi security filter (thiếu/hết hạn Bearer token) trả `401` với body **không có** `errorCode`:

```json
{ "success": false, "message": "Unauthorized", "data": null }
```

### 1.3 Quy ước dữ liệu

- Số điện thoại nên gửi theo E.164, ví dụ `+84912345678`.
- Backend hiện cũng chuẩn hóa `0912345678` thành `+84912345678`, nhưng frontend vẫn nên chuẩn hóa trước khi gọi API.
- Email được trim và chuyển về chữ thường.
- `deviceId` nên là ID ổn định theo bản cài đặt/trình duyệt, không phải ID tạo mới ở mỗi request. Ví dụ: UUID lưu trong secure storage của app hoặc storage của web. Đây cũng chính là giá trị hiển thị trong danh sách thiết bị ở mục 8.2 — đặt tên có ý nghĩa (`iphone-15-hieu`, `web-chrome-macbook`...) thay vì UUID thuần để người dùng tự nhận ra thiết bị.
- Mật khẩu (đăng ký/đổi mật khẩu/đặt lại mật khẩu): tối thiểu 8 ký tự, có ít nhất một chữ hoa, một chữ thường và một chữ số.
- `displayName`: bắt buộc khi đăng ký, tối đa 100 ký tự.
- Khi đăng ký, frontend phải chọn đúng một phương thức: `email` hoặc `phone`; không gửi cả hai trường trong cùng request. Xem lưu ý an toàn tại mục 12.

### 1.4 Link trong email — origin nào?

Kể từ 24/07/2026, hai loại link gửi qua email dùng **hai origin khác nhau**, đừng nhầm lẫn:

| Link | Origin | Vì sao |
|---|---|---|
| Reset password (`forgot-password`) | `APP_FRONTEND_URL` — origin của web/app frontend | Đây là link người dùng **bấm vào để mở màn hình thật** (form đặt mật khẩu mới), không phải gọi thẳng API. Web mở trang `/reset-password?token=...`; app mở qua Android App Links/iOS Universal Links hoặc custom scheme khi dev — xem mục 6.1. |
| Verify email (đăng ký / gửi lại) | `APP_FRONTEND_URL` | Tương tự — mở trang `/verify-email?token=...` |
| QR code TOTP, xác nhận đổi email trong hồ sơ (mục 9.4) | `APP_BASE_URL` — origin của chính API backend | Hai link này được thiết kế để mở/gọi thẳng vào backend, không qua giao diện frontend riêng. |

Frontend **phải** tự dựng 2 route `/reset-password` và `/verify-email`, nhận `token` qua query string, rồi gọi đúng 2 API tương ứng ở mục 6 và 3.3. Backend không tự redirect hay render HTML cho hai route này.

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

OTP đăng ký do backend tự quản lý (log ra console khi `app.sms.dev-mode=true`), khác với Firebase Phone Auth dùng ở mục 4 (đăng nhập nhanh) và tách biệt hoàn toàn — xem ghi chú kiến trúc ở mục 12.

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
- Tự gọi `POST /api/v1/auth/login` bằng `identifier=phone, password` nếu sản phẩm muốn auto-login.

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
Người dùng bấm link trong email -> mở trang frontend /verify-email?token=...
        |
        v
Trang frontend tự gọi GET /api/v1/auth/verify-email?token=...
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

Frontend chuyển sang màn "Kiểm tra email", hiển thị email đã che hoặc email đang nhập, nút "Mở ứng dụng email" nếu nền tảng hỗ trợ và nút "Gửi lại email". Không tự đăng nhập ở bước này.

Các lỗi chính:

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Email sai format, mật khẩu yếu, thiếu tên |
| 400 | `INVALID_ARGUMENT` | Không có cả email lẫn phone |
| 409 | `DUPLICATE_RESOURCE` | Email đã tồn tại, kể cả tài khoản chưa xác minh |

Với email đã tồn tại nhưng chưa xác minh, không gọi đăng ký lại; gọi API gửi lại email ở mục 3.4.

### 3.3 Bước 2 — Trang frontend xác minh email

Link trong email trỏ tới **frontend** (xem mục 1.4):

```text
{APP_FRONTEND_URL}/verify-email?token=<verificationToken>
```

Trang này tự gọi API xử lý token:

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

**Lưu ý quan trọng cho frontend web (React/Next.js):** effect gọi API xác minh chỉ được chạy **một lần** (token dùng một lần) — React StrictMode/development mode có thể chạy effect hai lần, gọi API hai lần liên tiếp khiến lần gọi thứ hai luôn nhận lỗi "đã dùng". Chặn bằng cờ `useRef` hoặc tương đương trước khi gọi API, không dựa vào response để phát hiện double-call.

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

Vì chống dò tài khoản, response giống nhau trong các trường hợp: email không tồn tại, email đã xác minh, gửi thành công hoặc đã chạm rate limit. Rate limit hiện tại là tối đa 3 lần/10 phút theo email và bị xử lý im lặng. Frontend luôn hiển thị thông báo trung tính như "Nếu tài khoản hợp lệ, email mới đã được gửi".

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

HTTP status: `403 Forbidden`. Frontend hiển thị CTA "Gửi lại email xác thực".

**Lưu ý hành vi quan trọng:** `EMAIL_NOT_VERIFIED` áp dụng ở cấp **tài khoản**, không phải cấp kênh đăng nhập. Nếu một tài khoản đăng ký bằng phone rồi bổ sung email (mục 9.3) nhưng chưa bấm link xác thực email đó, **mọi lần đăng nhập kể cả bằng phone** đều bị chặn 403 cho tới khi email được xác thực. Đây là hành vi cố ý (đảm bảo trạng thái xác thực nhất quán toàn tài khoản), frontend cần hiển thị đúng thông điệp "hãy xác thực email vừa thêm" trong trường hợp này, tránh gây hiểu lầm là lỗi mật khẩu/phone.

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

Nhánh TOTP này áp dụng cho **cả 3 kênh đăng nhập** dùng mật khẩu/OTP-thay-mật-khẩu: login thường (mục 4), đăng nhập nhanh Firebase OTP (mục 5). Riêng **Google login không đi qua TOTP** — xem mục 6.3.

### 4.4 Các lỗi đăng nhập

| HTTP | `errorCode` | Khi nào xảy ra | Hành vi UI đề xuất |
|---:|---|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu identifier/password hoặc password dưới 8 ký tự | Báo lỗi tại field |
| 401 | `INVALID_CREDENTIALS` | Sai identifier/password, account không tồn tại/không active | Thông báo chung, không tiết lộ tài khoản tồn tại |
| 403 | `EMAIL_NOT_VERIFIED` | Tài khoản có email chưa xác minh (xem lưu ý mục 3.5) | Hiện nút gửi lại email |
| 403 | `TENANT_SUSPENDED` | Doanh nghiệp đang active bị tạm dừng | Hiện thông báo liên hệ quản trị viên |
| 423 | `ACCOUNT_LOCKED` | Sai mật khẩu quá nhiều lần | Hiện thời điểm được thử lại |

Backend khóa tài khoản sau lần sai thứ 5 trong 30 phút. `ACCOUNT_LOCKED.message` và `userMessage` chứa thời điểm `lockedUntil` dạng ISO timestamp; frontend nên format theo timezone người dùng.

## 5. Đăng nhập nhanh bằng OTP điện thoại qua Firebase

Đây là kênh đăng nhập **riêng biệt** với đăng ký OTP ở mục 2 — dùng Firebase Phone Auth (client SDK) thay vì OTP do backend tự quản lý. Phù hợp cho app native đã tích hợp sẵn Firebase Auth SDK (Android/iOS) muốn đăng nhập chỉ bằng OTP, không cần mật khẩu.

### 5.1 Nguyên tắc

- Client tự thực hiện toàn bộ luồng gửi/xác minh OTP với Firebase (SDK native hoặc Firebase REST API) — backend **không** tham gia bước gửi OTP này.
- Sau khi Firebase xác minh xong, client nhận được **Firebase ID Token**, gửi token đó cho backend.
- Backend chỉ xác minh token đó là thật (qua Firebase Admin SDK) và trích số điện thoại đã verify, sau đó tìm tài khoản FAMS đã liên kết với số đó.
- Số điện thoại phải **đã tồn tại** trong hệ thống FAMS (qua đăng ký thường ở mục 2, hoặc do admin tạo) — endpoint này **không tự tạo tài khoản mới**.

### 5.2 API

`POST /api/v1/auth/otp/verify`

Public, không cần Bearer token.

```json
{
  "firebaseIdToken": "eyJhbGciOiJSUzI1NiJ9...",
  "deviceId": "android-28ab20d6-6fc2-427d-9853-50639af91099"
}
```

| Trường | Kiểu | Bắt buộc | Ghi chú |
|---|---|---:|---|
| `firebaseIdToken` | string | Có | ID token thật từ Firebase, chứa claim `phone_number` đã verify |
| `deviceId` | string | Không | Thiếu thì backend dùng `phone` |

Thành công trả cùng `LoginResponse` ở mục 4.2 (hoặc nhánh `totpRequired=true` như mục 4.3 nếu tài khoản bật TOTP).

### 5.3 Các lỗi

| HTTP | `errorCode` | Khi nào xảy ra | Xử lý UI |
|---:|---|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu `firebaseIdToken` | Báo lỗi client, không nên xảy ra nếu SDK dùng đúng |
| 401 | `INVALID_OTP` | Token Firebase sai/hết hạn/không hợp lệ, hoặc số điện thoại chưa có tài khoản FAMS | Nếu do chưa có tài khoản: gợi ý đăng ký (mục 2); nếu do token lỗi: cho thử lại |
| 429 | `OTP_RATE_LIMIT` | Vượt giới hạn request theo IP (mặc định 10 request/15 phút) | Khóa nút thử lại tạm thời |
| 503 | — | Firebase Admin SDK chưa được cấu hình trên server (thiếu `FCM_PROJECT_ID`/`FCM_SERVICE_ACCOUNT_JSON`) | Hiện lỗi "tính năng chưa khả dụng", không phải lỗi của người dùng |

**Đã kiểm chứng và sửa (24/07/2026):** trước đây một token có cấu trúc JWT hợp lệ nhưng chữ ký/claim giả có thể làm server trả lỗi `500 INTERNAL_ERROR` thay vì `401`. Đã sửa để mọi token không hợp lệ đều trả `401 INVALID_OTP` nhất quán — frontend không cần xử lý case 500 riêng cho endpoint này nữa.

## 6. Quên mật khẩu / đặt lại mật khẩu

### 6.1 Luồng giao diện

```text
Màn hình "Quên mật khẩu" — nhập email
        |
        v
POST /api/v1/auth/forgot-password
        |
        v
Màn hình "Kiểm tra email"
        |
        v
Người dùng bấm link trong email -> mở trang frontend /reset-password?token=...
        |
        v
Trang frontend hiện form nhập mật khẩu mới, tự gọi POST /api/v1/auth/reset-password
        |
        v
Thành công -> về màn đăng nhập
```

Link trong email trỏ tới **frontend** (xem mục 1.4):

```text
{APP_FRONTEND_URL}/reset-password?token=<resetToken>
```

Trên mobile app, route `/reset-password` và `/verify-email` (mục 3.3) cần được cấu hình như Android App Links / iOS Universal Links (khi đã có domain HTTPS thật) hoặc custom scheme (khi dev/chưa có domain) để hệ điều hành mở thẳng vào app thay vì trình duyệt — phối hợp với backend để đảm bảo `APP_FRONTEND_URL` khớp đúng domain đã đăng ký App Links/Universal Links.

### 6.2 Bước 1 — Yêu cầu reset

`POST /api/v1/auth/forgot-password`

Public, không cần Bearer token.

```json
{
  "email": "alice@example.com"
}
```

Thành công — **luôn `200 OK`** bất kể email có tồn tại hay không (chống dò tài khoản):

```json
{
  "success": true,
  "message": "If an account with that email exists, a password reset link has been sent.",
  "data": null
}
```

Quy tắc server:

- Token sống 1 giờ, dùng một lần.
- Rate limit: tối đa 3 lần/10 phút theo email, vượt quá cũng trả `200` im lặng (không gửi email mới).
- Chỉ tài khoản có email mới nhận được link (tài khoản phone-only không áp dụng được flow này — hướng dẫn họ liên hệ hỗ trợ hoặc dùng "thêm email" ở mục 9.3 trước).

Lỗi:

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu/sai định dạng email |

### 6.3 Bước 2 — Đặt mật khẩu mới

`POST /api/v1/auth/reset-password`

Public, không cần Bearer token.

```json
{
  "token": "6ca92346-59e5-4ee4-9a33-9046811f4c71",
  "newPassword": "NewPassword1"
}
```

| Trường | Kiểu | Bắt buộc | Ghi chú |
|---|---|---:|---|
| `token` | string | Có | Lấy từ query `token` trên URL trang `/reset-password` |
| `newPassword` | string | Có | Tối thiểu 8 ký tự; có hoa, thường và số |

Thành công — `200 OK`:

```json
{
  "success": true,
  "message": "Password has been reset successfully.",
  "data": null
}
```

Sau khi đổi thành công, **toàn bộ phiên đăng nhập cũ trên mọi thiết bị bị thu hồi** (giống mục 7 — đổi mật khẩu khi đã đăng nhập). Frontend chuyển thẳng về màn đăng nhập, không tự động đăng nhập.

Lỗi:

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu token/mật khẩu, mật khẩu yếu |
| 400 | `INVALID_ARGUMENT` | Token sai, hết hạn hoặc đã dùng |

Frontend nên phân biệt rõ 2 case của `INVALID_ARGUMENT`: hết hạn (mời gửi lại yêu cầu quên mật khẩu) và đã dùng (có thể do người dùng bấm link hai lần — coi như đã xử lý, mời thử đăng nhập bằng mật khẩu vừa đặt trước khi bắt gửi lại).

## 7. Đổi mật khẩu (khi đã đăng nhập)

`POST /api/v1/auth/change-password`

Cần Bearer token.

```json
{
  "currentPassword": "OldPassword1",
  "newPassword": "NewPassword1"
}
```

| Trường | Kiểu | Bắt buộc | Ghi chú |
|---|---|---:|---|
| `currentPassword` | string | Có | Mật khẩu hiện tại, dùng để xác minh |
| `newPassword` | string | Có | Tối thiểu 8 ký tự; có hoa, thường và số |

Thành công — `200 OK`, `data: null`.

**Hành vi bảo mật quan trọng:** ngay khi đổi mật khẩu thành công, backend vô hiệu hóa **ngay lập tức** toàn bộ token đang tồn tại của tài khoản — bao gồm cả access token vừa dùng để gọi chính API này, và access token của mọi thiết bị khác đang đăng nhập. Đây không phải chờ hết hạn tự nhiên (access token mặc định sống 15 phút) mà chết **ngay tại request kế tiếp**. Vì vậy:

- Frontend phải điều hướng thẳng về màn đăng nhập ngay sau response `200`, không gọi thêm API nào khác bằng token cũ (sẽ nhận `401`).
- Không cần tự gọi `POST /auth/logout` trước/sau — hành vi thu hồi đã bao trùm cả token hiện tại.
- Nếu sản phẩm muốn giữ người dùng đăng nhập ở thiết bị hiện tại, phải tự gọi lại `POST /auth/login` bằng mật khẩu mới ngay sau khi đổi thành công, để lấy token mới.

Lỗi:

| HTTP | `errorCode` | Khi nào xảy ra | Xử lý UI |
|---:|---|---|---|
| 401 | (không auth) | Thiếu/hết hạn Bearer token | Chuyển về đăng nhập |
| 401 | `INVALID_CREDENTIALS` | `currentPassword` sai | Báo lỗi tại field mật khẩu hiện tại |
| 400 | `VALIDATION_ERROR` | Thiếu trường, mật khẩu mới yếu | Báo lỗi tại field tương ứng |

## 8. Đăng xuất và quản lý phiên đăng nhập

### 8.1 Đăng xuất thiết bị hiện tại

`POST /api/v1/auth/logout`

Cần Bearer access token và body:

```json
{
  "refreshToken": "a3f1c2..."
}
```

Thành công `200`, `data: null`. Sau response, frontend xóa token và dữ liệu session local dù việc điều hướng sau đó có lỗi. Access token vừa dùng bị blacklist ngay lập tức (không cần chờ hết hạn), refresh token bị thu hồi.

### 8.2 Xem danh sách thiết bị đang đăng nhập

`GET /api/v1/auth/sessions`

Cần Bearer token.

```json
{
  "success": true,
  "message": "Success",
  "data": [
    {
      "id": "21db7719-9b8b-43a6-8756-5b9db27063fa",
      "deviceId": "iphone-15-hieu",
      "userAgent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0...)",
      "ipAddress": "203.0.113.42",
      "createdAt": "2026-07-23T15:33:27.768787Z",
      "lastUsedAt": "2026-07-23T15:33:27.768787Z",
      "expiresAt": "2026-08-22T15:33:27.768382Z",
      "current": true
    }
  ]
}
```

- `id`: dùng cho API đăng xuất một phiên cụ thể ở mục 8.3, **không phải** `userId`.
- `current`: `true` cho đúng phiên đang gọi request này (so khớp theo `deviceId` trong access token) — UI nên hiển thị rõ "(thiết bị này)" và ẩn nút đăng xuất cho phiên này (dùng nút riêng ở mục 8.1 để đăng xuất chính nó).

### 8.3 Đăng xuất một thiết bị cụ thể

`DELETE /api/v1/auth/sessions/{sessionId}`

Cần Bearer token. `sessionId` lấy từ field `id` ở mục 8.2.

Thành công `200`, `data: null`.

Refresh token của phiên bị thu hồi và mọi access token đã phát hành cho `deviceId` tương ứng bị
vô hiệu ngay ở request kế tiếp; không phải chờ access token tự hết hạn.

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 404 | `RESOURCE_NOT_FOUND` | Session không tồn tại, đã bị đăng xuất trước đó, hoặc thuộc user khác (không tiết lộ khác biệt) |

### 8.4 Đăng xuất tất cả thiết bị khác (giữ thiết bị hiện tại)

`POST /api/v1/auth/logout/others`

Cần Bearer token, không có request body.

Thành công `200`, `data: null`. Giữ nguyên phiên hiện tại đang gọi request, thu hồi mọi phiên khác.
Access token của các thiết bị khác bị vô hiệu ngay; access token của thiết bị hiện tại vẫn dùng được.

### 8.5 Đăng xuất toàn bộ (kể cả thiết bị hiện tại)

`POST /api/v1/auth/logout/all`

Cần Bearer token, không có request body.

Thành công `200`, `data: null`. Thu hồi tất cả token trên mọi thiết bị **kể cả thiết bị đang gọi request này** — access token hiện tại chết ngay, frontend phải tự điều hướng về màn đăng nhập, tương tự lưu ý ở mục 7.

## 9. Cập nhật hồ sơ cá nhân

### 9.1 Xem hồ sơ hiện tại

`GET /api/v1/auth/me`

Cần Bearer token.

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "alice@example.com",
    "emailVerified": true,
    "phone": null,
    "phoneVerified": false,
    "displayName": "Alice Nguyen",
    "avatarUrl": "http://localhost:9000/fams-avatars/avatars/550e8400-...-1690000000000.png",
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

Lưu ý field JSON thực tế là `active`, không phải `isActive`. Hai field mới bổ sung 24/07/2026: `emailVerified`, `phoneVerified` — dùng để biết một định danh vừa thêm (mục 9.3) đã xác thực xong chưa.

### 9.2 Cập nhật thông tin cơ bản (không gồm email/phone/avatar)

`PATCH /api/v1/auth/me`

Cần Bearer token. Mọi field đều optional, chỉ gửi field muốn đổi.

```json
{
  "displayName": "Alice Nguyen",
  "dateOfBirth": "1995-04-12",
  "hometown": "Nghệ An",
  "gender": "female",
  "address": "123 Nguyễn Trãi, Q.1, TP.HCM"
}
```

| Trường | Kiểu | Ghi chú |
|---|---|---|
| `displayName` | string | 1–100 ký tự |
| `dateOfBirth` | date (`YYYY-MM-DD`) | Phải là ngày trong quá khứ |
| `hometown` | string | Tối đa 255 ký tự |
| `gender` | string | Tự do, không giới hạn danh sách cố định (`male`/`female`/khác đều được) |
| `address` | string | Tối đa 500 ký tự |

Thành công `200`, trả về `UserProfileResponse` như mục 9.1.

**Quan trọng — thay đổi so với trước đây:** `PATCH /api/v1/auth/me` **không còn nhận** `email`, `phone`, hoặc `avatarUrl`. Gửi các field này sẽ bị bỏ qua lặng lẽ (không lỗi, nhưng cũng không ghi vào tài khoản). Ba field này bắt buộc phải đi qua API riêng có xác thực, xem mục 9.3 và 9.4 — lý do: nếu cho ghi trực tiếp một `avatarUrl` bất kỳ hoặc một email/phone chưa chứng minh quyền sở hữu, người dùng có thể tự khai một địa chỉ/ảnh không phải của mình.

Lỗi:

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Sai định dạng field, vượt độ dài, ngày sinh ở tương lai |

### 9.3 Thêm hoặc đổi email/phone còn thiếu — bắt buộc xác thực trước khi ghi

Khi đăng ký, tài khoản chỉ có **một trong hai** định danh (email hoặc phone). Muốn bổ sung định danh còn thiếu (hoặc đổi định danh đã có), phải qua quy trình 2 bước "yêu cầu → xác nhận" — **không có gì được ghi vào tài khoản cho tới khi xác nhận thành công**, nên định danh cũ vẫn dùng đăng nhập bình thường trong lúc chờ xác nhận (trừ trường hợp email, xem lưu ý mục 3.5).

#### 9.3.1 Thêm/đổi email

**Bước 1 — yêu cầu:**

`POST /api/v1/auth/profile/email/request-change`

Cần Bearer token.

```json
{ "email": "new-address@example.com" }
```

Thành công `200`, `data: null`. Gửi email chứa link xác thực tới địa chỉ **mới**:

```text
{APP_BASE_URL}/api/v1/auth/profile/email/confirm-change?token=<changeToken>
```

Web có thể để link gọi thẳng API, hoặc cấu hình proxy chuyển GET path trên sang
`/verify-email?token=<changeToken>&mode=email-change` để hiển thị trang kết quả thân thiện. Khi dùng
trang frontend, tham số `mode=email-change` giúp route gọi đúng API bước 2 dưới đây thay vì API xác
thực email đăng ký ở mục 3.3; hai loại token nằm ở namespace khác nhau.

Lỗi:

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Sai định dạng email |
| 409 | `DUPLICATE_RESOURCE` | Email đã dùng bởi tài khoản khác |

**Bước 2 — xác nhận:**

`GET /api/v1/auth/profile/email/confirm-change?token=<changeToken>`

Public, không cần Bearer token (giống xác thực email đăng ký, token tự xác định chủ tài khoản).

Thành công `200`:

```json
{
  "success": true,
  "message": "Email changed and verified successfully.",
  "data": null
}
```

Sau bước này, `email` mới được ghi vào tài khoản với `emailVerified: true`, và dùng để đăng nhập được ngay.

Lỗi:

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `INVALID_ARGUMENT` | Token sai/hết hạn |
| 409 | `DUPLICATE_RESOURCE` | Trong lúc chờ xác nhận, email đã bị người khác đăng ký trước |

#### 9.3.2 Thêm/đổi phone

**Bước 1 — yêu cầu OTP:**

`POST /api/v1/auth/profile/phone/request-change`

Cần Bearer token.

```json
{ "phone": "+84912345678" }
```

Thành công `200`, `data: null`. Gửi OTP 6 số qua SMS tới số **mới** (dev mode: log ra console server). Cùng cơ chế OTP với đăng ký ở mục 2 (5 phút, tối đa 3 lần gửi/15 phút, tối đa 5 lần nhập sai).

Lỗi: giống bảng lỗi mục 2.2 (`VALIDATION_ERROR`, `DUPLICATE_RESOURCE`, `OTP_RATE_LIMIT`).

**Bước 2 — xác nhận OTP:**

`POST /api/v1/auth/profile/phone/confirm-change`

Cần Bearer token.

```json
{ "phone": "+84912345678", "otpCode": "123456" }
```

Thành công `200`, trả về `UserProfileResponse` (mục 9.1) với `phone` mới và `phoneVerified: true`.

Lỗi:

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `INVALID_ARGUMENT` | Thiếu/sai/hết hạn OTP, hoặc nhập sai quá nhiều lần |
| 409 | `DUPLICATE_RESOURCE` | Trong lúc chờ xác nhận, số điện thoại đã bị người khác đăng ký trước |

### 9.4 Avatar — chỉ upload file, không dán URL

**Không có cách nào set avatar bằng cách gửi một chuỗi URL.** Avatar bắt buộc phải là ảnh thật upload từ thiết bị, lưu qua object storage (S3-compatible: MinIO ở dev, AWS S3 thật ở production — cùng một cơ chế, chỉ khác cấu hình khi deploy).

**Upload/thay avatar:**

`POST /api/v1/auth/profile/avatar` — `multipart/form-data`

Cần Bearer token. Field file: `file`.

```http
POST /api/v1/auth/profile/avatar
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data; boundary=...

--boundary
Content-Disposition: form-data; name="file"; filename="avatar.jpg"
Content-Type: image/jpeg

<binary>
--boundary--
```

Ràng buộc:

- Định dạng: JPEG, PNG, hoặc WEBP.
- Dung lượng tối đa: 5MB (giới hạn multipart chung của server).
- Upload mới sẽ **tự động xóa** file avatar cũ trên storage (nếu file cũ là do FAMS quản lý — avatar lấy từ Google, xem dưới, không bị xóa vì không phải file FAMS lưu).

Thành công `200`, trả `UserProfileResponse` (mục 9.1) với `avatarUrl` là URL công khai của ảnh vừa upload.

Lỗi:

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | File rỗng, sai định dạng, thiếu field `file` |
| 401 | (không auth) | Thiếu/hết hạn Bearer token |

**Xóa avatar:**

`DELETE /api/v1/auth/profile/avatar`

Cần Bearer token, không có body.

Thành công `200`, trả `UserProfileResponse` với `avatarUrl: null`. File trên storage cũng bị xóa.

**Avatar từ Google:** nếu tài khoản đăng nhập/liên kết Google (mục 10) và chưa có avatar, backend **tự động** lấy ảnh đại diện Google trả về khi login/link — không cần frontend gọi API upload cho trường hợp này. Nếu người dùng sau đó tự upload ảnh khác, ảnh đó sẽ thay thế ảnh Google.

## 10. Đăng nhập Google và đồng bộ hai chiều

### 10.1 Khái niệm token

Frontend đăng nhập với Google trước, nhận Google ID token, rồi gửi ID token đó cho backend. Không gửi Google access token và không gửi authorization code vào API này.

- Web Google Identity Services: dùng `response.credential` làm `idToken`.
- Android/iOS: cấu hình thư viện Google Sign-In để request ID token cho Web OAuth client/backend audience, sau đó dùng trường `idToken` trả về.
- Audience của token phải khớp chính xác `GOOGLE_CLIENT_ID` trên backend. Nếu app native dùng một client ID khác mà không request token cho Web client ID, backend trả 401.

### 10.2 API login Google

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

### 10.3 Quy tắc đồng bộ tài khoản

| Trạng thái trước khi Google login | Backend xử lý | Kết quả |
|---|---|---|
| Chưa có user cùng Google ID/email | Tạo user từ `email`, `name`, `picture`; email được coi là đã xác minh | Tài khoản Google-only, `passwordHash=null` |
| Đã đăng ký email/password cùng email, chưa link Google | Tự link `googleId`; chỉ lấy ảnh Google nếu avatar hiện tại trống; tự xác minh email | Một user dùng được cả Google và password |
| Đã link đúng Google ID | Dùng user hiện tại | Login bình thường, không tạo bản ghi trùng |
| Google email chưa được Google xác minh | Từ chối | HTTP 401 |
| Tài khoản FAMS không active | Từ chối | HTTP 401 |

"Hai chiều" trong code hiện tại có nghĩa:

- Email/password trước → Google sau: tự ghép theo email, không tạo user trùng.
- Google trước → password sau: dùng "Quên mật khẩu" (mục 6) cho đúng email Google để đặt mật khẩu lần đầu, sau đó có thể đăng nhập bằng cả hai cách.

Google login hiện được thiết kế không yêu cầu bước TOTP của FAMS và không bị chặn bởi `lockedUntil` do sai password. Frontend không chuyển sang màn TOTP sau Google login.

### 10.4 Các lỗi Google login

| HTTP | `errorCode` | Khi nào xảy ra |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu/rỗng `idToken` |
| 401 | `REQUEST_ERROR` | Token sai, hết hạn, sai audience hoặc Google email chưa verified |
| 401 | `INVALID_CREDENTIALS` | User FAMS không active |
| 403 | `TENANT_SUSPENDED` | Tenant active bị tạm dừng |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Không gửi `Content-Type: application/json` |

Nếu popup Google bị hủy hoặc SDK Google báo lỗi trước khi có ID token, đó là lỗi phía client; không gọi backend và hiển thị trạng thái tương ứng.

**Đã kiểm chứng và sửa (24/07/2026):** trang test thủ công `google-login-test.html` từng dùng nhầm Google OAuth Client ID khác với `GOOGLE_CLIENT_ID` cấu hình ở backend, khiến mọi lần test qua trình duyệt đều nhận 401 dù luồng thật không có lỗi. Đã đồng bộ lại — nếu frontend/app gặp 401 tương tự khi test, việc đầu tiên cần kiểm tra là Client ID phía client (Web Client ID cho native, hoặc client ID cấu hình trong Google Identity Services cho web) có khớp `GOOGLE_CLIENT_ID` phía backend hay không.

### 10.5 Liên kết Google khi user đang đăng nhập

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

### 10.6 Gỡ liên kết Google

`POST /api/v1/auth/unlink-google`

Cần Bearer token, không có request body.

Thành công trả `200` với `data: null`. Backend chỉ cho unlink khi tài khoản đã có password; Google-only account chưa đặt password nhận `400 INVALID_STATE`. UI cần hướng người dùng qua quên mật khẩu (mục 6)/đặt mật khẩu trước.

## 11. Refresh token

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

Không refresh lặp vô hạn và không chạy nhiều request refresh song song. Lưu ý: `POST /auth/logout/all`, `POST /auth/change-password`, `POST /auth/reset-password` đều thu hồi refresh token — nếu interceptor nhận `401 REQUEST_ERROR` từ chính `/refresh-token`, không thử refresh lại, chuyển thẳng về đăng nhập.

## 12. State machine đề xuất cho frontend

```text
UNAUTHENTICATED
  |-- email register 201 --------------> AWAITING_EMAIL_VERIFICATION
  |-- phone send OTP 200 --------------> AWAITING_PHONE_OTP
  |-- phone register 201 --------------> READY_TO_LOGIN
  |-- forgot-password 200 -------------> AWAITING_PASSWORD_RESET
  |-- password/Firebase-OTP login + TOTP -> AWAITING_TOTP
  |-- password/Firebase-OTP/Google login + tokens -> AUTHENTICATED

AWAITING_EMAIL_VERIFICATION
  |-- resend 200 -----------------------> giữ nguyên màn hình
  |-- verify link 200 ------------------> READY_TO_LOGIN

AWAITING_PASSWORD_RESET
  |-- reset-password 200 ---------------> READY_TO_LOGIN (tất cả phiên cũ đã bị thu hồi)

AWAITING_TOTP
  |-- login/totp 200 + tokens ----------> AUTHENTICATED
  |-- pending token expired -------------> UNAUTHENTICATED

AUTHENTICATED
  |-- app start -------------------------> refresh token nếu cần -> GET /me
  |-- logout / logout-all / change-password thành công -> UNAUTHENTICATED
  |-- profile: request-change email/phone -> vẫn AUTHENTICATED (chưa ghi gì, chờ confirm riêng)
  |-- profile: confirm-change email/phone thành công -> vẫn AUTHENTICATED, GET /me để cập nhật UI
```

## 13. Bảng xử lý lỗi dùng chung

| HTTP/Code | UI mặc định |
|---|---|
| `400 VALIDATION_ERROR` | Gắn lỗi theo `data.<field>` |
| `400 INVALID_ARGUMENT` | Hiển thị `message` gần bước đang thao tác |
| `400 MALFORMED_REQUEST` | Báo yêu cầu không hợp lệ; ghi log client để debug |
| `400 MISSING_PARAMETER` | Thiếu query param bắt buộc (thường do deep link/URL bị cắt) |
| `401 INVALID_CREDENTIALS` | "Thông tin đăng nhập không đúng" |
| `401 INVALID_OTP` | Token/OTP bên thứ ba không hợp lệ; cho thử lại |
| `401 REQUEST_ERROR` | Token bên thứ ba/session không hợp lệ; cho thử lại |
| `401` từ security filter (không có `errorCode`) | Thử refresh một lần; thất bại thì logout local |
| `400 INVALID_STATE` | Hành động bị chặn do trạng thái tài khoản chưa đủ điều kiện (VD: unlink Google khi chưa có password) — hiện hướng dẫn bước cần làm trước |
| `403 EMAIL_NOT_VERIFIED` | CTA gửi lại email xác thực |
| `403 TENANT_SUSPENDED` | Màn chặn và hướng liên hệ quản trị viên |
| `404 RESOURCE_NOT_FOUND` | Dữ liệu không còn tồn tại (session đã đăng xuất, user đã xóa...) — làm mới lại danh sách/state |
| `409 DUPLICATE_RESOURCE` | Gợi ý đăng nhập hoặc dùng tài khoản/định danh khác |
| `423 ACCOUNT_LOCKED` | Hiện thời gian mở khóa |
| `429 OTP_RATE_LIMIT` | Disable gửi lại OTP và hiện chờ |
| `500 INTERNAL_ERROR` | Thông báo chung, retry có kiểm soát |

## 14. Trạng thái xác minh và các phần còn thiếu

### 14.1 Đã xác minh trên backend đang chạy (24/07/2026)

| Nhóm | Kết quả |
|---|---|
| Phone register: gửi OTP, OTP sai, OTP đúng, login ngay, duplicate phone | Đạt, test end-to-end tự động |
| Email register: happy path, validation, duplicate, chặn login khi chưa verify | Đạt |
| Email verify token: login trước verify 403, verify 200, login sau verify 200 có token | Đạt end-to-end qua API/Redis |
| Đăng nhập nhanh Firebase OTP: validation, token giả (kể cả JWT giả có cấu trúc hợp lệ), rate limit | Đạt — đã sửa bug 500→401 nêu ở mục 5.3 |
| Google endpoint: validation, token giả/sai audience, public route, content type | Đạt |
| Google happy path | Có dữ liệu thực tế cho cả Google-only và account có password + Google; đã sửa bug client ID sai trên trang test thủ công |
| Logout / logout-all / logout-others / sessions list / xóa 1 session | Đạt, hoạt động đúng như hệ thống thực (Google/Slack-style) |
| Đổi mật khẩu: token chết ngay lập tức (không chờ hết hạn) | Đạt — đã sửa cả race-condition liên quan đến độ chính xác giây của JWT `iat` |
| Quên mật khẩu / đặt lại mật khẩu: link trỏ đúng trang frontend, không còn lộ path API/localhost trên mobile | Đạt — xem mục 14.2 việc còn lại phía hạ tầng |
| Cập nhật hồ sơ: field cơ bản, thêm/đổi email (xác thực trước khi ghi), thêm/đổi phone (OTP trước khi ghi), avatar upload/xóa, chặn dán URL avatar tùy ý | Đạt |

### 14.2 Chưa đủ để gọi là production-ready

1. **SMS thật cho OTP đăng ký/OTP đổi phone chưa được tích hợp.** `SmsService` hiện chỉ log OTP khi `app.sms.dev-mode=true`. Khi tắt dev mode, code ném lỗi; cần tích hợp ESMS/Twilio hoặc nhà cung cấp đã chọn, cấu hình secret, retry và theo dõi delivery.
2. **Backend chưa chặn request đăng ký có đồng thời email và phone.** Hiện code ưu tiên email flow. Frontend bắt buộc gửi đúng một trường; backend nên bổ sung validation XOR.
3. **`APP_FRONTEND_URL`/`APP_BASE_URL` cần khóa đúng theo môi trường trước khi deploy thật:**
   - Dev: cả hai mặc định `http://localhost:3000` (web dev server) — hoạt động vì có Next.js proxy `/api/**`.
   - Staging/Production: `APP_FRONTEND_URL` phải trỏ đúng domain web/app thật (khớp `EXPO_PUBLIC_APP_URL` phía mobile để Android App Links/iOS Universal Links hoạt động — xem tài liệu triển khai app 07/08); `APP_BASE_URL` trỏ đúng domain API backend thật.
   - Chưa cấu hình 2 file association (`/.well-known/assetlinks.json`, `/.well-known/apple-app-site-association`) trên domain thật — cần cả frontend lẫn hạ tầng phối hợp deploy.
4. **Một số script test cũ còn dùng body `{ "email": ... }` cho các endpoint dùng chung `admin@fams.com` ngoài phạm vi module auth** (site, employee, dashboard, report, checkin, rbac, randomcheck...). Contract hiện tại của `/auth/login` là `{ "identifier": ... }`; các script trong `tests/auth/` đã được cập nhật đầy đủ, các module khác thì chưa.
5. **Cấu hình production cần được khóa lại:** domain CORS thật, Gmail/SMTP, Google authorized origins/redirects, Firebase project thật cho mục 5, secret JWT/TOTP, chính sách lưu token trên web/mobile, và monitoring lỗi gửi email/SMS.

Không nên đưa phone registration/OTP thật ra production trước khi hoàn tất mục 1.

## 15. Checklist bàn giao frontend

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
- [ ] Có trang `/verify-email` thật (không còn phải hiện JSON thô) — tự gọi API, chặn double-call ở StrictMode.

### Password login

- [ ] Body dùng `identifier`.
- [ ] Lưu `deviceId` ổn định, đặt tên có ý nghĩa để hiển thị ở màn quản lý thiết bị.
- [ ] Xử lý `totpRequired=true` trước khi coi session là authenticated.
- [ ] Gọi `/me` sau khi có token.
- [ ] Có refresh single-flight và rotate refresh token.

### Đăng nhập nhanh Firebase OTP (native)

- [ ] Tích hợp Firebase Auth SDK (Android/iOS) để tự gửi/xác minh OTP.
- [ ] Chỉ gửi `firebaseIdToken` cho backend, không tự parse/tin tưởng phone số trong token ở phía client.
- [ ] Xử lý case số điện thoại chưa có tài khoản FAMS (401 `INVALID_OTP`) — gợi ý sang đăng ký.
- [ ] Test trên Development Build thật, không dùng Expo Go (Expo Go không hỗ trợ native Firebase Phone Auth).

### Google

- [ ] Web/native lấy đúng Google ID token cho backend audience (Web Client ID).
- [ ] Gửi `response.credential`/`idToken`, không gửi Google access token.
- [ ] Test tài khoản mới qua Google.
- [ ] Test email/password trước rồi Google cùng email: không tạo user trùng, `/me.googleLinked=true`.
- [ ] Test Google-only rồi đặt password qua quên mật khẩu.
- [ ] Test link/unlink trong trang hồ sơ.
- [ ] Test popup cancel, token sai audience và account bị Google từ chối.
- [ ] Test trên Development Build thật cho native (Expo Go không hỗ trợ Google Sign-In native).

### Quên mật khẩu / đặt lại mật khẩu

- [ ] Có trang `/reset-password` thật nhận `token` qua query, form nhập mật khẩu mới, tự gọi API.
- [ ] Sau reset thành công, điều hướng về đăng nhập, không giữ session cũ.
- [ ] Test mở link từ email trên cả web và mobile (App Links/Universal Links hoặc custom scheme khi dev).

### Đổi mật khẩu

- [ ] Sau `200`, tự động logout local ngay (token cũ đã chết ở server).
- [ ] Nếu muốn giữ đăng nhập, tự gọi lại `/login` bằng mật khẩu mới.

### Đăng xuất / quản lý thiết bị

- [ ] Màn "Thiết bị đang đăng nhập" dùng `GET /sessions`, đánh dấu rõ `current: true`.
- [ ] Nút đăng xuất một thiết bị dùng đúng `id` của session, không phải `userId`/`deviceId`.
- [ ] Phân biệt rõ UX giữa "đăng xuất thiết bị này" (mục 8.1), "đăng xuất nơi khác" (8.4) và "đăng xuất tất cả" (8.5).

### Cập nhật hồ sơ

- [ ] `PATCH /me` chỉ gửi field cơ bản (displayName, dateOfBirth, hometown, gender, address) — không gửi email/phone/avatarUrl.
- [ ] Thêm/đổi email: gọi `request-change`, hiện trạng thái "đang chờ xác thực", có trang `/verify-email` xử lý bước xác nhận (dùng chung route với mục 3.3).
- [ ] Thêm/đổi phone: gọi `request-change` → nhập OTP → `confirm-change`, UX giống bước OTP đăng ký.
- [ ] Cảnh báo rõ cho người dùng: nếu vừa thêm email mới, phải xác thực xong mới đăng nhập lại được (kể cả bằng phone cũ) — xem mục 3.5.
- [ ] Avatar: dùng file picker/camera thật, gọi `POST /profile/avatar` dạng multipart — không có ô nhập URL ảnh trong UI.
- [ ] Có nút xóa avatar gọi `DELETE /profile/avatar`.

### URL kiểm tra local

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Trang Google manual test: `http://localhost:8080/google-login-test.html`
