# Tài liệu tích hợp: Khóa tài khoản khi đăng nhập sai (cho Mobile App)

> Cập nhật theo code đang chạy ngày 24/07/2026. Base path: `/api/v1/auth`.
> Tài liệu này CHỈ dành cho `fams-front-app-project`. Hai tài liệu còn lại (`auth-api.md`, `tenant-api.md`) mô tả đầy đủ mọi API auth/tenant — file này trích riêng phần app cần build, kèm hướng dẫn UI/UX cụ thể cho mobile.

## 1. Vì sao chỉ tính năng này lên app

App phục vụ nhân viên (check-in/check-out, xem lịch, phản hồi kiểm tra ngẫu nhiên) — không có màn quản trị công ty. Trong 3 tính năng backend vừa cập nhật (khóa tài khoản, tạo tenant, danh sách/chi tiết tenant), **chỉ khóa tài khoản ảnh hưởng tới màn hình app đã có sẵn: màn Đăng nhập**. Tạo/quản lý tenant là nghiệp vụ back-office, chỉ có trên web admin (`fams-front-web-project`).

## 2. Thay đổi cần biết

- Đăng nhập sai **5 lần liên tiếp** trên cùng một tài khoản → khóa **1 tiếng** (trước đây 30 phút).
- Ngay khi khóa, nếu tài khoản có email, backend tự gửi 1 email cảnh báo kèm link đặt lại mật khẩu.
- **Đặt lại mật khẩu thành công = mở khóa ngay lập tức**, không cần đợi hết 1 tiếng. Đây chính là con đường app nên dẫn người dùng tới khi họ báo "tài khoản bị khóa".

Không có API mới nào cho tính năng này — chỉ có **1 status code mới cần xử lý** trên API đăng nhập app đã gọi sẵn, và tận dụng lại đúng flow "quên mật khẩu"/`reset-password` mà app đã build (theo tài liệu 07/08 trước đó).

## 3. API liên quan

### 3.1 Đăng nhập — endpoint app đã dùng, chỉ thêm 1 case lỗi

App hiện đang gọi 1 trong 2 endpoint tùy luồng:
- `POST /api/v1/auth/login` (email/phone + password)
- `POST /api/v1/auth/otp/verify` (Firebase phone OTP)

Cả hai đều có thể trả **case mới**: `423 Locked`

```json
{
  "success": false,
  "message": "Account locked until 2026-07-24T09:57:36Z",
  "data": null,
  "errorCode": "ACCOUNT_LOCKED",
  "userMessage": "Tài khoản bị khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau 2026-07-24T09:57:36Z."
}
```

**Field app cần đọc:**
- `errorCode = "ACCOUNT_LOCKED"` → dùng để switch-case, không parse theo `message` (câu chữ có thể đổi).
- Thời điểm mở khóa nằm trong `message` (tiếng Anh, dạng ISO 8601 UTC: `Account locked until <timestamp>`) — app tự trích chuỗi timestamp sau `"until "` để tính đếm ngược, hoặc đơn giản chỉ cần hiển thị `userMessage` (đã có sẵn tiếng Việt) mà không cần tính đếm ngược chính xác.
- **Không có field JSON riêng cho `lockedUntil`** hiện tại — nếu team app cần con số chính xác để hiển thị đồng hồ đếm ngược đẹp, báo lại để backend bổ sung field `lockedUntil` riêng trong `data` (hiện `data: null`). Tạm thời parse từ `message` là đủ dùng.

### 3.2 Quên mật khẩu — app đã có sẵn, tái sử dụng nguyên vẹn

Đây chính là 2 API app đã tích hợp theo tài liệu 08 (`APP_FRONTEND_URL` + deep link `/reset-password`):

`POST /api/v1/auth/forgot-password`
```json
{"email": "user@example.com"}
```
→ luôn `200`, gửi email chứa link `{APP_FRONTEND_URL}/reset-password?token=...` mà app đã tự xử lý deep link.

`POST /api/v1/auth/reset-password`
```json
{"token": "<token từ deep link>", "newPassword": "NewPassword1"}
```
→ `200` nếu thành công. **Kể từ 24/07/2026, gọi API này khi tài khoản đang bị khóa sẽ tự động mở khóa luôn** (xóa cả bộ đếm sai lẫn thời điểm khóa) — app không cần gọi thêm API nào khác để "mở khóa", chỉ cần đi hết flow reset password bình thường là xong.

**Không cần sửa code của 2 API này** — hành vi mở khóa xảy ra ở phía backend, hoàn toàn trong suốt với app.

## 4. Luồng UI/UX đề xuất cho app

```text
Người dùng bấm "Đăng nhập"
        |
        v
POST /auth/login (hoặc /auth/otp/verify)
        |
        +-- 200 --------------------------> vào app bình thường
        |
        +-- 401 INVALID_CREDENTIALS -------> "Sai email/SĐT hoặc mật khẩu" (như hiện tại)
        |
        +-- 423 ACCOUNT_LOCKED
                |
                v
        Hiện màn/dialog "Tài khoản tạm khóa"
        - Icon khóa + userMessage
        - (tùy chọn) đếm ngược tới thời điểm mở khóa, parse từ message
        - Nút chính: "Đặt lại mật khẩu để mở khóa ngay"
                |
                v
        Điều hướng sang màn Quên mật khẩu ĐÃ CÓ SẴN
        (POST /forgot-password -> chờ email -> deep link /reset-password -> POST /reset-password)
                |
                v
        Reset thành công -> quay về màn Đăng nhập, có thể đăng nhập lại NGAY
        (không cần đợi hết 1 tiếng, không cần hiển thị lại màn khóa)
```

### Gợi ý copy tiếng Việt cho màn khóa

- Tiêu đề: "Tài khoản tạm thời bị khóa"
- Nội dung: dùng thẳng `userMessage` từ response (đã viết sẵn bằng tiếng Việt, thân thiện).
- Nút phụ (nếu muốn): "Kiểm tra email — chúng tôi đã gửi thông báo và hướng dẫn mở khóa" (vì backend đã tự gửi email cảnh báo, không cần app tự thông báo lại bằng push notification).
- Nút chính: "Đặt lại mật khẩu" → mở màn Quên mật khẩu.

### Trường hợp tài khoản chỉ đăng ký bằng số điện thoại (không có email)

Nếu tài khoản không có email, backend **không gửi được** email cảnh báo và người dùng **không có** đường tắt "quên mật khẩu" để mở khóa sớm — bắt buộc phải đợi hết 1 tiếng. App nên phát hiện case này và **ẩn nút "Đặt lại mật khẩu"**, thay bằng thông điệp đơn giản: "Vui lòng thử lại sau [thời gian]". Cách phát hiện: nếu tài khoản đăng nhập bằng `identifier` là số điện thoại (không chứa `@`) và trước đó chưa từng thêm email, khả năng cao không có đường tắt — nhưng an toàn nhất là **cứ luôn hiện nút "Đặt lại mật khẩu"**; nếu bấm vào và nhập số điện thoại đó vào form quên-mật-khẩu, `forgot-password` API chỉ nhận `email` (không nhận phone) nên form quên mật khẩu vốn đã chỉ hỏi email — nếu người dùng không nhớ/không có email, tự nhiên họ sẽ không đi tiếp được, không cần app xử lý logic phát hiện riêng.

## 5. Test case cho app (không cần backend, chỉ cần môi trường dev/staging)

| # | Bước | Kỳ vọng |
|---|---|---|
| 1 | Đăng nhập sai mật khẩu 5 lần liên tiếp | Lần 5 hiện màn "Tài khoản tạm khóa", không phải lỗi generic |
| 2 | Trong lúc đang khóa, thử đăng nhập lại bằng đúng mật khẩu | Vẫn hiện màn khóa (423), KHÔNG cho vào app |
| 3 | Từ màn khóa, bấm "Đặt lại mật khẩu" | Điều hướng đúng sang flow quên mật khẩu hiện có |
| 4 | Hoàn tất reset password (qua deep link email) | Quay lại được màn đăng nhập |
| 5 | Đăng nhập lại bằng mật khẩu MỚI ngay sau bước 4 | Vào app thành công — không bị 423 nữa dù chưa hết 1 tiếng |
| 6 | Đóng app, mở lại, đăng nhập sai 1 lần rồi đăng nhập đúng | Vào app bình thường (bộ đếm sai đã reset sau lần đăng nhập đúng ở bước 5) |

## 6. Việc KHÔNG cần làm trên app

- Không cần tự implement logic đếm số lần sai — hoàn toàn phía backend.
- Không cần gọi API riêng để "kiểm tra xem còn bị khóa không" — cứ thử đăng nhập, response tự nói lên tất cả.
- Không cần push notification riêng cho việc khóa tài khoản — email cảnh báo đã được backend gửi tự động.
- Không cần sửa flow `forgot-password`/`reset-password` đã build — dùng nguyên, chỉ thêm điểm điều hướng từ màn 423 tới flow đó.
