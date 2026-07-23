# Sprint 1 — Backend review

## #1 — Đăng nhập email/mật khẩu

**Trạng thái: 🟡 Cần sửa nhỏ** (nghiệp vụ chính đúng, thiếu vài điểm theo Acceptance Criteria)

### Code hiện tại
- Controller: `AuthController.login` — `api-server/src/main/java/com/fams/modules/auth/controller/AuthController.java:188-193` (`POST /api/v1/auth/login`, public, không cần auth).
- Service: `AuthService.login` — `api-server/src/main/java/com/fams/modules/auth/service/AuthService.java:68-159`.
- DTO request: `LoginRequest` (`email` `@NotBlank @Email`, `password` `@NotBlank @Size(min=8)`, `deviceId` optional, không validate).
- DTO response: `LoginResponse` (`accessToken`, `refreshToken`, `tokenType`, `expiresIn`, `totpRequired`, `pendingToken`).

### Luồng xử lý (đã đúng)
1. Tìm user theo email (`findByEmailAndDeletedAtIsNull`) — sai thì trả `InvalidCredentialsException` (401), **không lộ thông tin** user có tồn tại hay không (dùng chung message với sai mật khẩu — đúng chuẩn bảo mật).
2. Kiểm tra khóa tài khoản (`lockedUntil`) — khóa 30 phút (`AppConstants.LOCK_DURATION_MINUTES`) sau 5 lần sai (`AppConstants.MAX_FAILED_ATTEMPTS`) → `AccountLockedException` (423).
3. So khớp mật khẩu bằng BCrypt; sai thì tăng `failedLoginAttempts`.
4. Chặn email chưa xác thực → `EmailNotVerifiedException` (403).
5. Reset bộ đếm sai khi login thành công.
6. Chặn tenant bị suspend (trừ platform admin) → `TenantSuspendedException` (403) — đây là lớp chặn thứ nhất; lớp thứ hai là `JwtAuthFilter` kiểm tra cờ Redis cho mọi request sau đó (đúng thiết kế phòng thủ 2 lớp).
7. Nếu user bật TOTP → trả `pendingToken` (Redis TTL 5 phút) thay vì token thật, `totpRequired=true`.
8. Phát hành access token (JWT) + lưu `RefreshToken` (đã hash) trong DB.

### Thiếu so với Acceptance Criteria ("...ghi last_login và audit LOGIN")
1. **Không có cột `last_login`** trên entity `User` (`api-server/src/main/java/com/fams/modules/auth/entity/User.java`) và không có ở bất kỳ migration nào — cần thêm migration `ALTER TABLE users ADD COLUMN last_login_at TIMESTAMPTZ` + set trong `AuthService.login` khi thành công.
2. **Không ghi audit log** — `AuditLogService.record(...)` (`api-server/.../audit/service/AuditLogService.java:67`) tồn tại đầy đủ nhưng **không có nơi nào trong toàn bộ codebase gọi tới nó** (grep xác nhận: 0 caller ngoài chính `AuditLogController` dùng để đọc). Đây là lỗ hổng hệ thống ảnh hưởng ~40 tính năng khác, không riêng #1 — xem `docs/BACKLOG.md` mục 7.
3. **Không có rate-limit** trên `/login` (chỉ `/forgot-password` có tài liệu 429). Rủi ro brute-force dù đã có lockout theo tài khoản — vẫn có thể dò email hợp lệ qua timing/volume nếu không giới hạn theo IP.
4. **Swagger thiếu 2 mã lỗi thật**: `AuthController.java:180-186` chỉ khai báo 200/400/401/423, nhưng service thực tế còn ném `TenantSuspendedException` (403) và `EmailNotVerifiedException` (403) — cả hai không có trong `@ApiResponses`. Dev tích hợp Web/Mobile đọc Swagger sẽ không biết cần xử lý 2 case này.

### Test coverage hiện tại
`tests/auth/test_login.sh` (shell/curl, chạy tay hoặc qua `run_all.sh`) — **có** cover: happy path (200 + token), sai mật khẩu (401), thiếu email (400), email không tồn tại (401), email chưa verify (403).
**Chưa cover**: khóa tài khoản sau 5 lần sai (423), login vào tenant đã suspend (403 TENANT_SUSPENDED), luồng TOTP-pending (200 + `totpRequired=true`), format `last_login`/audit sau khi thêm.
Không có test Java (unit/integration) nào cho `AuthService.login` — `ApiServerApplicationTests.java` chỉ là context-load rỗng.

### Đề xuất sửa (phạm vi hợp lý cho #1, không lan sang toàn hệ thống)
1. Thêm migration `last_login_at` + set trong `AuthService.login` khi đăng nhập thành công.
2. Gọi `auditLogService.record(...)` với `action="LOGIN"` sau khi đăng nhập thành công (entityType `"USER"`, entityId = user id). Đây sẽ là **lần gọi đầu tiên** của service này trong toàn bộ codebase — dùng làm mẫu tham chiếu cho các feature audit khác sau này thay vì làm riêng một cơ chế lớn (AOP/interceptor) ngay bây giờ, tránh vượt phạm vi #1.
3. Bổ sung 2 mã lỗi còn thiếu vào `@ApiResponses` của endpoint login.
4. Thêm 3 test case còn thiếu vào `tests/auth/test_login.sh` (lockout, tenant suspended, TOTP-pending).
5. **Không** thêm rate-limit theo IP trong lần sửa này — đây là hạng mục lớn hơn (cần Redis bucket/filter dùng chung cho nhiều endpoint), đề xuất theo dõi như một tính năng riêng ở Sprint 6 (Security) thay vì chèn ngang vào #1. Sẽ hỏi ý kiến trước khi đụng tới.

### Roadmap test thủ công trên Swagger (`http://localhost:8080/swagger-ui/index.html`)
1. `POST /api/v1/auth/login` với `admin@fams.com` / `Admin@1234` → kỳ vọng 200, có `accessToken`/`refreshToken`.
2. Lặp lại với mật khẩu sai 5 lần liên tiếp cùng 1 email → lần thứ 6 (dù đúng mật khẩu) phải trả 423 `ACCOUNT_LOCKED`.
3. Login bằng tài khoản thuộc tenant `dong-a-jsc` (đã suspend trong seed data) → kỳ vọng 403 `TENANT_SUSPENDED`.
4. Login bằng tài khoản chưa verify email → 403 `EMAIL_NOT_VERIFIED`.
5. Sau khi fix xong: query `SELECT last_login_at FROM users WHERE email='admin@fams.com'` phải có giá trị mới sau bước 1; `GET /api/v1/audit-logs?entityType=USER&action=LOGIN` (quyền admin) phải thấy bản ghi vừa tạo.

## #2 — Đăng nhập bằng số điện thoại OTP

**Trạng thái: 🟡 Code đúng kiến trúc — chờ Firebase project thật để test SMS live (2026-07-23)**

Chi tiết đầy đủ (phát hiện cross-repo, các sửa đổi cụ thể từng bên): xem `docs/BACKLOG.md` mục #2. Tóm tắt phần backend: đã xóa `OtpService`/`SendOtpRequest`/`VerifyOtpRequest`/`SmsService` (code chết), xóa rule `permitAll` thừa cho `/otp/send`, thêm rate-limit theo IP cho `/otp/verify` (429), và bổ sung nhánh kiểm tra TOTP/2FA còn thiếu trong `FirebasePhoneLoginService` (lỗ hổng bảo mật thật: trước đây user bật 2FA có thể bỏ qua bằng cách đăng nhập qua SĐT). `FirebaseApp` chưa được cấu hình trong `.env` local (`FCM_PROJECT_ID`/`FCM_SERVICE_ACCOUNT_JSON` trống) — mọi request tới `/otp/verify` hiện trả 503, đã kiểm chứng đúng hành vi này.
