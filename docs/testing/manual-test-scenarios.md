# Kịch bản kiểm thử — FAMS (99 tính năng, đầy đủ thành công/lỗi/ngoại lệ)

> Tài liệu này là **kịch bản test case chi tiết**, khác với `feature-test-guide.md` (chỉ tra cứu nhanh account/dữ liệu). Ở đây mỗi tính năng có ≥1 case thành công (TH1) + ≥1 case lỗi/validation (TH2...) + case ngoại lệ/biên nếu có, cùng với việc phân loại **Độc lập vs Luồng** và **Tự động vs Thủ công**.
>
> Base URL: `http://localhost:8080`. Mật khẩu chung mọi tài khoản mẫu: `Admin@1234`. Danh sách tài khoản seed v3 hiện hành nằm tại `docs/testing/demo-seed-data.md`. Các email seed v2 còn xuất hiện ở những kịch bản cũ chỉ mang tính lịch sử và cần được thay bằng tài khoản có role tương ứng trong seed v3.

## 0. Quy ước đọc tài liệu

| Ký hiệu | Ý nghĩa |
|---|---|
| **[ĐL]** | Tính năng **độc lập** — test riêng lẻ được ngay, không cần dựng chuỗi bước trước |
| **[LUỒNG]** | Tính năng chỉ có ý nghĩa đầy đủ khi test **theo trình tự nhiều bước** — xem Phần B |
| **[TĐ]** | Đã có **script tự động** chạy được ngay — nêu rõ đường dẫn `tests/.../test_*.sh` |
| **[TC]** | **Chỉ test được thủ công** — nêu rõ lý do (cần mắt người xem, cần thiết bị thật, cần thao tác ngoài hệ thống...) |
| **[BTĐ]** | Bán tự động — script tự động xác nhận được response, nhưng vẫn cần bạn tự kiểm tra thêm 1 phần bằng mắt/thiết bị thật |
| TH1 | Trường hợp **thành công** (happy path) |
| TH2, TH3... | Trường hợp **lỗi/validation** |
| THx (biên) | Trường hợp **ngoại lệ/biên** |

**Cách chạy toàn bộ script tự động sẵn có**: `bash tests/run_all.sh`. Một số script tích hợp tự tạo dữ liệu tạm; chạy lại `bash scripts/seed.sh` để khôi phục ba tenant demo chuẩn. Seed chỉ lưu trữ các tenant demo v2 đã biết và không xóa tenant do người dùng tự tạo.

---

## Phần A — Test case theo từng tính năng độc lập

### A.1 Auth & tài khoản cá nhân

#### 1. Đăng nhập email/mật khẩu — [ĐL] [TĐ] `tests/auth/test_login.sh`

| Case | Input | Kết quả mong đợi |
|---|---|---|
| TH1 Thành công | `chu.hoanglong@gmail.com` / `Admin@1234` | 200, trả `accessToken`+`refreshToken` |
| TH2 Sai mật khẩu | Cùng email, `password="SaiRoi123"` | 401, `failedLoginAttempts` tăng 1 |
| TH3 Email không tồn tại | `khongtontai999@gmail.com` | 401 (không lộ email tồn tại hay không) |
| TH4 Thiếu field | `{"identifier":""}` | 400 validation |
| TH5 (biên) Email chưa xác thực | `chuaxacthucmail1@gmail.com` | Bị chặn, lỗi "vui lòng xác thực email" |
| TH6 (biên) Tài khoản đang khóa | `taikhoanbikhoa@gmail.com` | 423/401, dù mật khẩu đúng |
| TH7 (biên) Tài khoản Google-only | `dangnhapgoogle@gmail.com` / bất kỳ password | 401 (`password_hash=NULL`) |
| TH8 (biên) Platform staff bị vô hiệu hóa | `kythuat3.nentang@fams.com` / `Admin@1234` | 401/403 dù mật khẩu đúng |

#### 2. Đăng nhập bằng số điện thoại OTP — [ĐL] [TC] (cần Firebase Phone Auth thật, không mô phỏng được qua script)

| Case | Input | Kết quả mong đợi |
|---|---|---|
| TH1 Thành công | Số điện thoại thật nhận được SMS qua Firebase test project | 200, trả token |
| TH2 Sai OTP | Nhập sai mã | 401 |
| TH3 OTP hết hạn | Chờ quá thời gian hiệu lực rồi nhập | 401/400 |
| TH4 (biên) Vượt rate-limit | Gọi `/auth/otp/verify` liên tục >10 lần/IP trong khung giờ | 429 |

**Lý do [TC]**: OTP xác thực qua Firebase Phone Auth thật (SMS/reCAPTCHA), không có cách nào seed hoặc script hóa việc "nhận SMS thật" — phải test tay bằng số điện thoại thật hoặc Firebase test-phone-number đã đăng ký trước trong Firebase Console.

#### 3. Đăng nhập Google — [ĐL] [TC] (cần Google OAuth thật)

| Case | Input | Kết quả mong đợi |
|---|---|---|
| TH1 Thành công (tài khoản Google mới) | Google ID token thật từ tài khoản Google chưa từng dùng | 201/200, tự tạo user mới |
| TH2 Thành công (đã từng đăng nhập) | Google ID token của tài khoản đã link trước | 200, đăng nhập vào đúng user cũ |
| TH3 Token giả/hết hạn | Google ID token không hợp lệ | 401 |
| TH4 Link Google vào tài khoản đang login bằng email | `POST /auth/link-google` với token thật, đang login bằng `chu.hoanglong@gmail.com` | 200, `googleLinked=true` |
| TH5 Unlink | `POST /auth/unlink-google` | 200; **kỳ vọng**: nếu tài khoản không có password (chỉ có Google) thì unlink phải bị chặn (tránh khóa tài khoản vĩnh viễn) — cần xác nhận hành vi thật khi test |

**Lý do [TC]**: cần Google ID token thật (không mô phỏng được, `dangnhapgoogle@gmail.com` trong seed chỉ có `google_id` giả để test hiển thị dữ liệu, không login được qua Google thật).

#### 4. Đăng xuất khỏi thiết bị hiện tại — [ĐL] [TĐ] `tests/auth/test_logout.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200; gọi lại API bằng access token cũ → 401 |
| TH2 Refresh token không hợp lệ/đã dùng | 400/401 |
| TH3 (biên) Gọi logout 2 lần liên tiếp cùng refresh token | Lần 2 → lỗi (token đã revoke) |

#### 5. Đăng xuất khỏi tất cả thiết bị — [ĐL] [TĐ] `tests/auth/test_logout_all.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Login `dung.pham.hr@gmail.com` 2 lần lấy 2 token khác nhau → `/auth/logout/all` bằng 1 token → CẢ 2 token cũ đều 401 |
| TH2 (biên) Refresh token cũ đã bị revoke từ trước | `hotro1.nentang@fams.com` đã có 1 refresh token revoke sẵn trong seed — xác nhận không dùng lại được |

#### 6. Đăng ký tài khoản người dùng — [ĐL] [TĐ] `tests/auth/test_register.sh`, `test_register_manual_email.sh`, `test_register_phone_otp.sh`

| Case | Input | Kết quả mong đợi |
|---|---|---|
| TH1 Thành công (email) | Email mới hợp lệ | 201, `email_verified=false` |
| TH2 Email đã tồn tại | `chu.hoanglong@gmail.com` | 409 |
| TH3 Mật khẩu yếu | `password="123"` | 400 validation |
| TH4 Email sai định dạng | `email="abc"` | 400 |
| TH5 Đăng ký qua phone+OTP | Số điện thoại mới + OTP hợp lệ | 201 |
| TH6 (biên) Số điện thoại đã tồn tại | Số của 1 user đã có | 409 |

#### 7. Quên mật khẩu — [ĐL] [TĐ] `tests/auth/test_forgot_reset_password.sh` + manual variant

| Case | Kết quả mong đợi |
|---|---|
| TH1 Email tồn tại | 200, log server có link reset |
| TH2 Email không tồn tại | 200 (KHÔNG lộ khác biệt — chống dò email) |
| TH3 (biên) Gọi liên tục vượt rate-limit | 429 sau N lần |

#### 8. Đặt lại mật khẩu — [ĐL] [TĐ] `tests/auth/test_forgot_reset_password.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Token hợp lệ | 200; login lại bằng mật khẩu mới OK, mật khẩu cũ không dùng được |
| TH2 Token sai/đã dùng | 400/401 |
| TH3 Token hết hạn | 400/401 |
| TH4 Mật khẩu mới yếu | 400 validation |
| **Lưu ý** | Test trên **tài khoản không quan trọng** (tự đăng ký mới ở case #6), không test trên tài khoản demo chính để tránh đổi mất mật khẩu chung |

#### 9. Đổi mật khẩu — [ĐL] [TĐ] `tests/auth/test_change_password.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200; mọi session khác bị đăng xuất |
| TH2 Sai mật khẩu hiện tại | 401/400 |
| TH3 Mật khẩu mới yếu | 400 |
| **Lưu ý** | Test trên tài khoản tự tạo, không đổi mật khẩu tài khoản demo chính |

#### 10. Xem thông tin cá nhân — [ĐL] [TĐ] `tests/auth/test_profile.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | `GET /auth/me` trả đủ field |
| TH2 Không có token | 401 |
| TH3 Token hết hạn/blacklist | 401 |

#### 11. Cập nhật hồ sơ cá nhân — [ĐL] [TĐ] `tests/auth/test_update_profile.sh`, `test_profile_fields_and_avatar.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | `PATCH /auth/me` đổi `hometown`/`gender` → 200 |
| TH2 Field sai định dạng | `dateOfBirth="abc"` → 400 |
| TH3 Upload avatar hợp lệ | `POST /profile/avatar` → 200, có URL |
| TH4 Upload avatar sai định dạng/quá dung lượng | 400/413 |
| TH5 Đổi email — luồng 2 bước | `POST /profile/email/request-change` → nhận link → `GET /profile/email/confirm-change` → email mới có hiệu lực |
| TH6 Đổi phone — luồng 2 bước tương tự | Tương tự qua OTP |

### A.2 Bảo mật nâng cao

#### 12. Bật TOTP 2FA — [ĐL] [TĐ] `tests/auth/test_totp.sh` + [BTĐ] cần Authenticator app thật để xác nhận UX

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Setup → verify bằng mã TOTP đúng (dùng `pyotp` hoặc app thật) → 200, trả `backupCodes` |
| TH2 Mã TOTP sai | 400 |
| TH3 Setup token hết hạn | 400 |
| TH4 (biên) Bật 2FA khi đã bật rồi | 409 — test trên `bat2fa1@gmail.com` (đã bật sẵn) |
| TH5 Tắt 2FA (`/totp/disable`) | Cần đúng 1 trong `password`/`code`/`backupCode` — thiếu cả 3 → 401 |

#### 13. Đăng nhập có 2FA — [ĐL] [TĐ] `tests/auth/test_login_totp.sh` + [BTĐ]

| Case | Input | Kết quả mong đợi |
|---|---|---|
| TH1 Thành công bằng mã TOTP | `bat2fa1@gmail.com`, mã sinh từ secret hiện tại (xem log seed gần nhất) | 200, trả accessToken thật |
| TH2 Thành công bằng backup code | 1 trong 8 mã in log | 200; dùng lại mã đó lần 2 → lỗi |
| TH3 Mã sai | Mã ngẫu nhiên | 401 |
| TH4 pendingToken hết hạn | Chờ >5 phút rồi xác nhận | 401 |
| TH5 (biên) Login thường (không qua `/login/totp`) khi đã bật 2FA | `POST /auth/login` | Chỉ trả `pendingToken`, KHÔNG có accessToken thật |

#### 14. Khóa tài khoản khi đăng nhập sai — [ĐL] [TĐ] `tests/auth/test_account_lock.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 5 lần sai liên tiếp | Lần 6 dù đúng mật khẩu vẫn bị khóa 60 phút |
| TH2 (biên) Đã khóa sẵn | `taikhoanbikhoa@gmail.com` — xác nhận login đúng mật khẩu vẫn 423/401 |
| TH3 Reset password mở khóa sớm | Đặt lại mật khẩu thành công → `locked_until` được xóa, login lại được ngay |
| TH4 (biên) Đăng nhập đúng giữa chừng (lần 3/5) | Counter reset về 0, không bị khóa |

---

### A.3 Tenant / Platform / Gói dịch vụ

#### 15. Tạo tenant mới — [ĐL] [TĐ] `tests/tenant/test_create_tenant.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | `admin@fams.com` tạo tenant với `ownerEmail` mới → 201 |
| TH2 Slug trùng | `slug="acme-corp"` → 409 |
| TH3 Không phải Platform Admin | Company owner tự gọi → 403 |
| TH4 Thiếu field bắt buộc | Thiếu `name` → 400 |

#### 16. Xem danh sách tenant — [ĐL] [TĐ] `tests/tenant/test_list_tenants.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | `admin@fams.com` → 18 tenant, phân trang/tìm kiếm/lọc theo status hoạt động |
| TH2 Không đủ quyền | Company owner gọi → 403 |
| TH3 Tìm kiếm không khớp | `search=khongtontai` → mảng rỗng |

#### 17. Cập nhật thông tin tenant — [ĐL] [TĐ] `tests/tenant/test_update_tenant.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Owner `rong-vang-holdings` đổi `industry` → 200 |
| TH2 Không phải owner của tenant đó | 403 |
| TH3 Tenant không tồn tại | 404 |

#### 18. Cấu hình giao diện và định dạng — [ĐL] [TĐ] `tests/tenant/test_tenant_settings.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Đổi `brandPrimaryColor` tenant `acme-corp` (đang `#B22222`) → 200, đọc lại đúng |
| TH2 Giá trị màu sai định dạng | `"khongphaimau"` → 400 |
| TH3 (biên) Tenant chưa từng cấu hình | `rong-vang-holdings` → trả về giá trị mặc định, không lỗi |

#### 19. Quản lý IP whitelist — [ĐL] [TĐ] `tests/tenant/test_ip_whitelist.sh` + [TC] xác nhận chặn thật cần gọi từ IP ngoài dải

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thêm IP | 201 |
| TH2 IP sai định dạng CIDR | 400 |
| TH3 Thêm trùng | 409 |
| TH4 Xóa | 200 |
| TH5 [TC] Gọi API từ IP ngoài whitelist khi đang bật | Bị chặn — acme-corp đã có sẵn 2 whitelist entry để test, cần gọi thật từ mạng ngoài dải `203.113.128.0/24`/`14.161.0.0/16` mới xác nhận được chặn thật |

#### 20. Quản lý gói dịch vụ — [ĐL] [TĐ] `tests/subscription/test_plans.sh`, `test_plan_deactivation_migration.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Tạo gói mới | 201 |
| TH2 Tên gói trùng | 409 |
| TH3 Deactivate gói KHÔNG có tenant nào dùng | 200 |
| TH4 Deactivate gói ĐANG có tenant dùng, không truyền `migrateToPlanId` | 409 (Issue #8) |
| TH5 (biên) Deactivate + migrate — đối chiếu case có sẵn | `legacy_basic` đã deactivate, `dai-duong-fishery` đã migrate sang `basic` — xác nhận lại |

#### 21. Cấu hình giới hạn gói — [ĐL] [TĐ] `tests/subscription/test_plan_limits.sh`, `tests/tenant/test_plan_limits.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Cập nhật giới hạn | `PATCH /plans/{id}/limits` → 200 |
| TH2 Giá trị âm | 400 |
| TH3 [LUỒNG] Enforcement thật | Tạo nhân viên thứ 6 tại `tia-sang-startup` (5/5 Trial) → 422 `PLAN_LIMIT_EXCEEDED` |

#### 22. Gán subscription cho tenant — [ĐL] [TĐ] `tests/subscription/test_subscription.sh`, `test_subscription_expiration.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Gán mới | 201 |
| TH2 Tenant đã có subscription active | 409 (dùng PATCH thay vì POST) |
| TH3 Cập nhật (PATCH) | 200 |
| TH4 (biên) Trial sắp hết hạn / đã hết hạn | Đối chiếu `hoa-phuong-trading` (còn 2 ngày) và `nam-viet-services` (hết hạn 5 ngày) |

### A.4 RBAC & Audit

#### 23. Seed role và permission hệ thống — [ĐL] [TĐ] `tests/rbac/test_rbac_seed.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Xác nhận đủ 5 role hệ thống | `GET /roles` không truyền tenantId → thấy PLATFORM_ADMIN, TENANT_ADMIN, HR_MANAGER, SITE_SUPERVISOR, EMPLOYEE |

#### 24. Danh sách role — [ĐL] [TĐ] `tests/rbac/test_list_roles.sh`, `test_get_role.sh`, `test_my_roles.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | `GET /roles` trả role hệ thống + custom |
| TH2 `GET /roles/me` | Trả đúng role của user đang login — test bằng `dung.pham.hr@gmail.com` (2 role khác nhau ở 2 tenant) |

#### 25. Tạo role tùy chỉnh — [ĐL] [TĐ] `tests/rbac/test_create_role.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Owner `acme-corp` tạo role mới → 201 |
| TH2 Tên trùng trong cùng tenant | 409 |
| TH3 `permissionIds` chứa ID không tồn tại | 400 |
| TH4 Không đủ quyền (EMPLOYEE tự tạo role) | 403 |

#### 26. Sửa role và quyền — [ĐL] [TĐ] `tests/rbac/test_update_role.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Sửa role tự tạo ở #25 → 200 |
| TH2 Sửa role HỆ THỐNG (`EMPLOYEE`...) | 403 |
| TH3 [LUỒNG] Sửa quyền role đang gán cho user | User giữ role đó gọi API ngay sau → quyền thay đổi tức thì, KHÔNG cần re-login |

#### 27. Xóa hoặc vô hiệu hóa role — [ĐL] [TĐ] `tests/rbac/test_delete_role.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Xóa role chưa gán ai | 200/204 |
| TH2 Xóa role ĐANG gán cho user | 409 |
| TH3 Vô hiệu hóa qua `PUT` `isActive:false` | 200 (khác cơ chế với xóa cứng) |
| TH4 Xóa role hệ thống | 403 |

#### 28. Xem permission theo nhóm — [ĐL] [TĐ] `tests/rbac/test_list_permissions.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | `GET /permissions` → gom nhóm theo resource (`employees`, `sites`, `checkins`...) |

#### 29. Gán role cho user — [ĐL] [TĐ] `tests/rbac/test_assign_role.sh`, `test_platform_staff_role.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công (tenant-scope) | 201 |
| TH2 Thành công (platform-scope) | Gán 1 trong 5 role nền tảng chưa ai giữ (`PLATFORM_ONBOARDING_SPECIALIST`...) cho `kythuat1.nentang@fams.com` → 201 |
| TH3 Gán trùng role đã có | 409 |
| TH4 [LUỒNG] Gán role site-scoped | Xem A.20 mục site-scope RBAC |

#### 30. Thu hồi role — [ĐL] [TĐ] `tests/rbac/test_revoke_role.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200/204; user mất quyền ngay lập tức |
| TH2 (biên) Người khác cùng role không bị ảnh hưởng | Sau khi thu hồi role platform vừa gán ở #29, xác nhận `kythuat2.nentang@fams.com` vẫn còn `PLATFORM_STAFF` |
| TH3 user-role không tồn tại | 404 |

#### 31. Ghi audit cho hành động quan trọng — [ĐL] [TĐ] `tests/audit/test_audit_logs.sh` — **[LƯU Ý GAP ĐÃ BIẾT]**

| Case | Kết quả mong đợi |
|---|---|
| TH1 Sự kiện auth có audit | Login/đổi mật khẩu `chu.hoanglong@gmail.com` vài lần → `GET /audit-logs` thấy log |
| TH2 (biên, XÁC NHẬN GAP) | Tạo/sửa role, gán subscription, sửa tenant settings → **KHÔNG có log audit tương ứng** (gap đã biết, xem `backend-feature-audit-2026-08-01.md`) — test này để XÁC NHẬN gap còn tồn tại, không phải test tìm lỗi mới |

#### 32. Tạo notification in-app cơ bản — [ĐL] [TĐ] (gián tiếp qua các test khác) — nội bộ `POST /internal/notifications`

| Case | Kết quả mong đợi |
|---|---|
| TH1 | Không test tay trực tiếp (endpoint nội bộ, không cần auth theo thiết kế) — xác nhận gián tiếp qua A.11 |

---

### A.5 Nhân viên, mời, workspace, Face ID

#### 33. Mời nhân viên bằng email — [ĐL] [TĐ] `tests/employee/test_invite_employee.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | HR `dung.pham.hr@gmail.com` mời email mới tại acme-corp → 201 |
| TH2 Email đã có invitation pending | 409 |
| TH3 Email đã là nhân viên trong tenant | 409 |
| TH4 Không đủ quyền | EMPLOYEE tự mời → 403 |

#### 34. Chấp nhận lời mời — [ĐL] [TĐ] `tests/employee/test_accept_invitation.sh`, `test_validate_invitation.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Dùng token của `hoa.nguyen.moi@hoanglong.vn` (pending có sẵn) → 201, trả JWT |
| TH2 Token sai/không tồn tại | 404/400 |
| TH3 Token đã hết hạn | Dùng token của `vi.pham.het.han@hoanglong.vn` (expired sẵn) → 400 |
| TH4 Token đã bị hủy | Dùng token của `kiet.tran.moi@hoanglong.vn` (cancelled sẵn) → 400 |
| TH5 Mật khẩu yếu | 400 |

#### 35. Hủy lời mời — [ĐL] [TĐ] `tests/employee/test_cancel_invitation.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Hủy `phuong.dang.choloi@hoanglong.vn` (còn pending) → 200 |
| TH2 Hủy invitation đã accepted | 422/409 |
| TH3 Hủy invitation không tồn tại | 404 |

#### 36. Danh sách nhân viên — [ĐL] [TĐ] `tests/employee/test_list_employees.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | acme-corp → 40 nhân viên, test phân trang (`size=10`), sắp xếp |
| TH2 Tìm kiếm | `search=Nguyễn` → chỉ khớp tên chứa "Nguyễn" |
| TH3 Lọc theo status | `status=terminated` → thấy `HL-012` |
| TH4 Không đủ quyền (tenant khác) | User `beta-industries` gọi API `acme-corp` → 403 |

#### 37. Xem chi tiết nhân viên — [ĐL] [TĐ] `tests/employee/test_get_employee.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | `HL-002` (Trần Thị Bình) → đủ role/workspace/Face ID status |
| TH2 ID không tồn tại | 404 |
| TH3 ID thuộc tenant khác | 404 (không lộ thông tin xuyên tenant) |

#### 38. Tạo nhân viên thủ công — [ĐL] [TĐ] `tests/employee/test_create_employee.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 201 |
| TH2 Email trùng trong tenant | 409 |
| TH3 Mã nhân viên trùng | 409 |
| TH4 Thiếu field bắt buộc | 400 |
| TH5 (biên) Vượt giới hạn gói | `tia-sang-startup` (5/5) tạo thêm → 422 `PLAN_LIMIT_EXCEEDED` |

#### 39. Cập nhật nhân viên — [ĐL] [TĐ] `tests/employee/test_update_employee.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200 |
| TH2 Đổi email thành email đã tồn tại trong tenant | 409 |
| TH3 ID không tồn tại | 404 |

#### 40. Tạm ngừng/nghỉ việc nhân viên — [ĐL] [TĐ] `tests/employee/test_change_employee_status.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 → inactive | 200 |
| TH2 → terminated | 200; [LUỒNG] xác nhận assignment/scheduled-check liên quan tự hủy |
| TH3 Giá trị status không hợp lệ | 400 (chỉ nhận active/inactive/terminated) |
| TH4 (biên) Đổi trạng thái nhân viên đã terminated | Cho phép về lại active hay không — cần xác nhận hành vi thật |

#### 41. Import danh sách nhân viên — [ĐL] [TĐ] `tests/employee/test_import_employees.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 File hợp lệ | 200, tất cả dòng thành công |
| TH2 File có dòng lỗi (thiếu field/trùng email) | 200 nhưng trả danh sách lỗi từng dòng, dòng hợp lệ vẫn tạo được |
| TH3 File sai định dạng (không phải .xlsx) | 400 |
| TH4 File rỗng | 400/200 với 0 dòng |

#### 42. Export danh sách nhân viên — [ĐL] [TĐ] `tests/employee/test_export_employees.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Trả file `.xlsx` đúng số dòng theo filter |
| TH2 [TC] Mở file kiểm tra định dạng/encoding tiếng Việt | Cần mở file thật bằng Excel để xác nhận không lỗi font |

#### 43. Tạo workspace — [ĐL] [TĐ] `tests/workspace/test_create_workspace.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 201 |
| TH2 Tên trùng trong tenant (không phân biệt hoa/thường) | 409 |
| TH3 `parentId` không tồn tại | 400/404 |
| TH4 `type` không hợp lệ | 400 (chỉ nhận `department`/`team`) |

#### 44. Danh sách workspace — [ĐL] [TĐ] `tests/workspace/test_list_workspace.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Danh sách phẳng | 13 workspace/tenant chuyên sâu |
| TH2 Dạng tree | `GET .../workspaces/tree` → 3 cấp (Đội → Nhóm) |

#### 45. Cập nhật workspace — [ĐL] [TĐ] `tests/workspace/test_update_workspace.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200 |
| TH2 Đặt `parentId` = chính nó (vòng lặp) | 400 |
| TH3 Đặt `status=inactive` | 200 — đối chiếu 3 case đã inactive sẵn |

#### 46. Gán nhân viên vào workspace — [ĐL] [TĐ] `tests/workspace/test_workspace_members.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 201 |
| TH2 Đã là thành viên | 409 |
| TH3 Gán vào workspace đã inactive | 400 (business rule cần xác nhận) |

#### 47. Chuyển workspace cho nhân viên — [ĐL] [TĐ] `tests/workspace/test_transfer_workspace_member.sh`, `test_workspace_member_status.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200, atomic remove+add |
| TH2 Site nguồn = site đích | 400 |
| TH3 Member không tồn tại | 404 |

#### 48. Ghi nhận đồng ý Face ID — [ĐL] [TĐ] `tests/face-id/test_consent.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Nhân viên tự consent → 200 |
| TH2 Đồng ý lần 2 (idempotent) | 200, không lỗi |
| TH3 HR consent hộ nhân viên khác | 403 (chỉ tự bản thân được consent) |

#### 49. Đăng ký Face ID — [ĐL] [BTĐ] `tests/face-id/test_enroll.sh` — **[TC một phần: cần ảnh thật qua ai-service]**

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công (HR-assisted, ≥3 ảnh thật) | 201, vào hàng chờ duyệt |
| TH2 Thiếu số ảnh tối thiểu | 400 |
| TH3 Ảnh không nhận diện được khuôn mặt | 400/422 từ ai-service |
| TH4 Chưa consent | 403 |
| TH5 Luồng self-service qua liveness challenge | `POST .../liveness-challenge` → `.../frames` → `.../enroll-from-challenge` |
| **Lý do BTĐ** | Cần ảnh khuôn mặt thật để ai-service xử lý được — script tự động cần chuẩn bị sẵn file ảnh test hợp lệ, nếu không có sẽ luôn nhận lỗi ảnh không hợp lệ |

#### 50. HR xem trạng thái Face ID — [ĐL] [TĐ] `tests/face-id/test_status.sh`, `test_face_id_report.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Xem 1 nhân viên | Đủ status/review_status |
| TH2 Hàng chờ duyệt toàn tenant | `GET .../face-id/pending-review` → ~10 người/tenant |
| TH3 Duyệt | `POST .../approve` → 200 |
| TH4 Từ chối kèm lý do | `POST .../reject` → 200, đối chiếu 5 câu lý do đã đa dạng hóa trong seed |

#### 51. Xóa/vô hiệu hóa Face ID — [ĐL] [TĐ] `tests/face-id/test_revoke.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200, status → revoked, xóa ảnh/embedding phía ai-service |
| TH2 Revoke khi chưa từng enroll | 400/404 |

---

### A.6 Site / Geofence / Shift / Assignment

#### 52. Tạo công trình — [ĐL] [TĐ] `tests/site/test_create_site.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 201 |
| TH2 Tên/mã trùng trong tenant | 409 |
| TH3 Tọa độ không hợp lệ (lat>90) | 400 |
| TH4 (biên) Vượt giới hạn `max_sites` của gói | Test trên tenant Trial (`max_sites=1`) tạo site thứ 2 → 422 |

#### 53. Danh sách công trình — [ĐL] [TĐ] `tests/site/test_list_sites.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | acme-corp → 13 site |
| TH2 Lọc status=inactive | Thấy "Công trình Hoàng Long - Nam Định" |

#### 54. Xem chi tiết công trình — [ĐL] [TĐ] `tests/site/test_get_site_detail.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Có geofence active, shift, số phân công |
| TH2 ID không tồn tại | 404 |

#### 55. Cập nhật công trình — [ĐL] [TĐ] `tests/site/test_update_site.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200 |
| TH2 Đổi tên trùng site khác cùng tenant | 409 |

#### 56. Tạo geofence cho công trình — [ĐL] [TĐ] `tests/site/test_create_geofence.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công (đa giác ≥4 điểm khép kín) | 201 |
| TH2 <4 điểm | 400 |
| TH3 Đa giác không khép kín | 400 |
| TH4 Site đã có geofence active | Tạo mới → geofence cũ tự chuyển `superseded` (không lỗi) |

#### 57. Sửa geofence — [ĐL] [TĐ] `tests/site/test_update_geofence.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200; xác nhận bản CŨ → `superseded`, KHÔNG bị xóa |
| TH2 Toạ độ không hợp lệ | 400 |

#### 58. Xem lịch sử geofence — [ĐL] [TĐ] `tests/site/test_geofence_history.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Site có lịch sử sửa | `GET .../geofences` → ≥2 bản ghi (1 active + superseded) |
| TH2 Site chưa từng sửa geofence | Chỉ 1 bản ghi active |

#### 59. Tạo ca làm việc — [ĐL] [TĐ] `tests/site/test_create_shift.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 201 |
| TH2 `startTime`/`endTime` sai định dạng | 400 |
| TH3 Tên ca trùng trong site | 409 |
| TH4 (biên) Ca qua đêm | `allowOvernight=true`, `endTime < startTime` → 201, KHÔNG bị coi là lỗi |

#### 60. Cấu hình OT và giới hạn giờ — [ĐL] [TĐ] `tests/site/test_shift_ot_config.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200 |
| TH2 Giá trị âm | 400 |
| TH3 (biên) Dung sai lệch hẳn 2 thái cực | Đối chiếu "Ca ngắn (6h)" @ BM-MAIN (45p sớm/5p muộn) |

#### 61. Danh sách ca theo site — [ĐL] [TĐ] `tests/site/test_list_shifts.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | HL-HN → 3 ca (sáng/chiều/đêm) |

#### 62. Cập nhật hoặc ngừng dùng ca — [ĐL] [TĐ] `tests/site/test_update_shift.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Cập nhật giờ | 200 |
| TH2 `status=inactive` | 200 — đối chiếu 3 case sẵn |
| TH3 Xóa cứng ca ĐANG có assignment | 409 |
| TH4 Xóa cứng ca CHƯA có assignment | 200/204 |

#### 63. Tạo phân công nhân viên vào site — [ĐL] [TĐ] `tests/site/test_create_assignment.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 201 |
| TH2 (biên quan trọng) Nhân viên ĐÃ có assignment active tại CÙNG site | 409 |
| TH3 Nhân viên có assignment active ở site KHÁC | 201 — đối chiếu 3 case multi-site đã có sẵn trong seed |
| TH4 `endDate < startDate` | 400 |

#### 64. Danh sách phân công — [ĐL] [TĐ] `tests/site/test_list_assignments.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Lọc theo role/status/employeeId |

#### 65. Cập nhật phân công — [ĐL] [TĐ] `tests/site/test_update_assignment.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200 |
| TH2 `clearShift`/`clearEndDate` | 200, field về NULL |

#### 66. Hủy phân công — [ĐL] [TĐ] `tests/site/test_cancel_assignment.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200, status → cancelled; đối chiếu 3 case sẵn |
| TH2 (biên) Hết hạn TỰ NHIÊN, KHÔNG hủy chủ động | 3 case sẵn (`HL-011`@HL-DN...) — status vẫn `active` dù `endDate` đã qua, KHÔNG nhầm với "cancelled" |
| TH3 Hủy assignment đã cancelled | 400/404 |

#### 67. Hiển thị site được phép check-in — [ĐL] [TĐ] `tests/checkin/test_available_sites.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Có assignment | `binh.tran@hoanglong.vn` → thấy site tương ứng |
| TH2 Không có assignment nào | Nhân viên chưa phân công → mảng rỗng |
| TH3 (biên) Ca đêm gần nửa đêm | Xác nhận đúng site hiển thị theo timezone site-local |

---

### A.7 Check-in / Check-out / Attendance

#### 68. Check-in GPS cơ bản — [ĐL/LUỒNG] [TĐ] `tests/checkin/test_basic_checkin.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Trong geofence, đúng giờ ca | 201, `status=valid` |
| TH2 Ngoài geofence | 201, `status=pending_review` |
| TH3 Không có assignment active tại site đó | 403/404 |
| TH4 Đã check-in chưa check-out (check-in lần 2) | 409/400 |
| TH5 Tọa độ thiếu/null | 400 |

#### 69. Check-in có Face ID — [LUỒNG] [BTĐ] `tests/face-id/test_checkin_face.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Nhân viên đã enroll, ảnh khớp | `face_verified=true` (async, cần đợi vài giây) |
| TH2 Nhân viên chưa enroll | Chặn ngay tại bước check-in, không tạo được (fail-safe) |
| TH3 Ảnh không khớp | `face_verified=false`, escalate `pending_review` |

#### 70. Check-in có liveness — [LUỒNG] [BTĐ] `tests/face-id/test_checkin_liveness.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Challenge pass trước, check-in trong thời hạn | Thành công |
| TH2 Không có challenge pass | Chặn |
| TH3 Challenge đã hết hạn/dùng rồi | Chặn |

#### 71. Kiểm tra check-in sớm — [ĐL] [TĐ] `tests/checkin/test_early_checkin.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Trong dung sai cho phép | 201 |
| TH2 Vượt dung sai | 422 `CHECKIN_TOO_EARLY` |

#### 72. Check-out GPS — [LUỒNG] [TĐ] `tests/checkin/test_checkout.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200 |
| TH2 Check-out khi chưa check-in | 404/400 |
| TH3 Check-out 2 lần | 409/400 |
| TH4 Ngoài geofence | Escalate `pending_review` |

#### 73. Kiểm tra check-out muộn — [ĐL] [TĐ] (chung `test_checkout.sh`)

| Case | Kết quả mong đợi |
|---|---|
| TH1 Trong dung sai | `work_minutes` tính đủ |
| TH2 Vượt dung sai — dùng "Ca ngắn (6h)" (chỉ cho phép trễ 5p) | `work_minutes` bị cap, không tính dư |

#### 74. Tính work_minutes cho cặp check-in/out — [ĐL] [TĐ] (đối chiếu qua `test_checkin_result.sh`)

| Case | Kết quả mong đợi |
|---|---|
| TH1 Ca thường | Khớp chênh lệch thời gian |
| TH2 Ca qua đêm (`allowOvernight`) | Tính đúng qua mốc 00:00 |

#### 75. Check-in offline và đồng bộ — [ĐL] [TĐ] `tests/checkin/test_basic_checkin.sh` (phần sync) — **[LƯU Ý]** case conflict cố ý KHÔNG có sẵn trong seed, phải tự tạo lúc test

| Case | Kết quả mong đợi |
|---|---|
| TH1 Sync 1 bản ghi hợp lệ, `clientNonce` mới | 200/201 |
| TH2 Sync lại CÙNG `clientNonce` | Idempotent, không tạo trùng |
| TH3 [TC] Sync bị từ chối do trùng/overlap với checkin đã có | Tự tạo checkin thật trước, rồi sync 1 bản ghi offline đè lên cùng khung giờ → kỳ vọng bị từ chối/conflict — case này KHÔNG có sẵn trong seed (xem `sample-data-requirements-v2.md` mục 2.6 — cố ý không fake) |

#### 76. Hiển thị kết quả check-in/out — [ĐL] [TĐ] `tests/checkin/test_checkin_result.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Mọi trạng thái | Message tiếng Việt đúng ngữ cảnh (valid/pending_review) |

#### 77. Nhân viên xem lịch sử chấm công — [ĐL] [TĐ] `tests/checkin/test_checkin_history.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Chỉ thấy checkin CỦA CHÍNH MÌNH |
| TH2 Lọc theo khoảng ngày | Đúng filter |

#### 78. HR xem danh sách check-in — [ĐL] [TĐ] `tests/checkin/test_hr_list_checkins.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | Toàn site, có cả `pending_review` |
| TH2 SITE_SUPERVISOR site-scoped | `binh.tran@hoanglong.vn` chỉ thấy site được gán |

#### 79. HR xem chi tiết check-in — [ĐL] [TĐ] `tests/checkin/test_hr_checkin_detail.sh`, `test_employee_explanation.sh`, `test_override_checkin.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Xem chi tiết | Đủ bằng chứng |
| TH2 Có giải trình của nhân viên | Thấy `employee_note`/`employee_photo_url` (~50% case pending_review demo có sẵn) |
| TH3 HR override quyết định | `POST .../override` → 200, ghi lý do |

#### 80. Tự động tạo attendance summary — [ĐL] [TĐ] `tests/attendance/test_attendance_summary.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Sau checkin/checkout | Tự tính real-time |
| TH2 Job đêm (không đợi được, xem log job) | Chạy `0 0 1 * * *`, tính lại ngày hôm trước |

#### 81. Tính đi muộn — [ĐL] [TĐ] `tests/attendance/test_late_detection.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Muộn > 10 phút | `isLate=true`, `lateMinutes` đúng |
| TH2 Đúng giờ | `isLate=false` |

#### 82. Tính về sớm — [ĐL] [TĐ] `tests/attendance/test_early_leave.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Về sớm | `isEarlyLeave=true` |
| TH2 (biên) Còn session mở (chưa checkout) | KHÔNG tính về sớm (chờ đóng session) |

#### 83. Tính OT — [ĐL] [TĐ] `tests/attendance/test_ot_minutes.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Ca cho phép OT, làm quá giờ | `otMinutes` > 0 |
| TH2 Ca KHÔNG cho phép OT | `otMinutes` = 0 dù làm quá giờ |
| TH3 Vượt `lateCheckoutMinutes` | Phần vượt bị cắt, không tính |

#### 84. Phát hiện thiếu checkout — [ĐL] [TĐ] `tests/attendance/test_missing_checkout.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Chỉ có check-in, hết ngày | `missingCheckout=true` |
| TH2 Đã check-out | `missingCheckout=false` |

#### 85. Nhân viên xem bảng công ngày/tháng — [ĐL] [TĐ] `tests/attendance/test_employee_timesheet.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Xem tháng có dữ liệu | Tổng hợp đúng (dữ liệu trải ~75 ngày, từ tháng 5→8/2026) |
| TH2 Xem tháng không có dữ liệu | Trả rỗng, không lỗi 500 |

#### 86. HR xem bảng công tổng hợp — [ĐL] [TĐ] `tests/attendance/test_hr_monthly.sh`, `test_adjust_attendance_summary.sh`, `test_attendance_names.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Xem tổng hợp toàn tenant | Đúng số liệu |
| TH2 HR điều chỉnh tay (`adjust`) | 200, ghi `adjustment_reason` |
| TH3 Unlock-and-recompute | 200, tính lại từ checkin thô |
| TH4 Guard chặn xuất báo cáo khi dữ liệu chưa sẵn sàng | 409 `ATTENDANCE_NOT_READY` (nếu áp dụng) |

---

### A.8 Notification & Random Check

#### 87. Đăng ký thiết bị nhận push — [ĐL] [TĐ] `tests/notification/test_fcm_devices.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 201 |
| TH2 Đăng ký lại cùng token ở user khác | Token được chuyển chủ sở hữu (upsert), không lỗi |
| TH3 Unregister | 200, `deleted_at` set — đối chiếu `demo-fcm-token-dung-huy-0002` đã unregister sẵn |

#### 88. Gửi push notification — [ĐL] [BTĐ] `tests/notification/test_fcm_retry_fallback.sh` — **[TC một phần: xác nhận nhận được thật cần thiết bị**

| Case | Kết quả mong đợi |
|---|---|
| TH1 Token hợp lệ | Gửi thành công qua Firebase thật |
| TH2 Token không hợp lệ/đã unregister | `notification_delivery_logs.status=FAILED`, retry rồi fallback email |
| TH3 [TC] Nhận thật trên điện thoại | Cần app FE thật + device token thật đăng ký qua app, không mô phỏng được bằng script |

#### 89. Danh sách thông báo trong app/web — [ĐL] [TĐ] `tests/notification/test_notification_inbox.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | ~13 loại/tenant, mix đọc/chưa đọc |
| TH2 `unreadOnly=true` | Chỉ trả chưa đọc |

#### 90. Đánh dấu đã đọc — [ĐL] [TĐ] `tests/notification/test_mark_read.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Đánh dấu 1 | 200 |
| TH2 Đánh dấu tất cả | 200, `unreadCount=0` |
| TH3 Đánh dấu notification của người khác | 403/404 |

#### 91. Tạo cấu hình random check mặc định tenant — [ĐL] [TĐ] `tests/randomcheck/test_random_check_config.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công (tenant chưa có) | 201 — test trên `rong-vang-holdings` |
| TH2 Tenant đã có config | 409 |

#### 92. Tạo cấu hình override theo site — [ĐL] [TĐ] `tests/randomcheck/test_random_check_config_site_override.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 201 |
| TH2 Site đã có override | 409 |
| TH3 `GET .../effective` khi có override | `resolvedFrom=site_override` |
| TH4 `GET .../effective` khi KHÔNG có override | `resolvedFrom=tenant_default` |

#### 93. Cấu hình số lần và khung giờ check — [ĐL] [TĐ] `tests/randomcheck/test_scheduling_fields.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Thành công | 200 |
| TH2 `allowedEndTime < allowedStartTime` | 400 |

#### 94. Cấu hình mode kiểm tra — [ĐL] [TĐ] `tests/randomcheck/test_check_mode.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Đổi mode | 200 — đối chiếu 3 tenant chính đã 3 mode khác nhau |
| TH2 Giá trị mode không hợp lệ | 400 |

#### 95. Cấu hình áp dụng theo vai trò — [ĐL] [TĐ] `tests/randomcheck/test_applicable_roles.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Chỉ áp dụng `worker` | Supervisor không bị sinh check |
| TH2 Mảng rỗng | Áp dụng mọi role |

#### 96. Tự động sinh scheduled checks đầu ca — [LUỒNG] [TĐ] `tests/randomcheck/test_scheduled_check_generation.sh`, `test_manual_check.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Trigger tay | `POST .../scheduled-checks/manual` → sinh đúng theo config |
| TH2 Job tự động 00:01 | [TC] cần đợi thật hoặc xem log, không trigger tay được |
| TH3 Nhân viên không có assignment active | Không sinh check |

#### 97. Snapshot config khi sinh check — [ĐL] [TĐ] `tests/randomcheck/test_config_snapshot.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Sinh check, sau đó sửa config | Check cũ giữ nguyên `configSnapshot`, check MỚI dùng config mới |

#### 98. Tạo job Redis gửi check — [LUỒNG] [TĐ] `tests/randomcheck/test_dispatch_job.sh`, `test_dispatch_notification.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Check đến giờ | Job poll 60s tự dispatch, status → `sent`, gửi push |
| TH2 [BTĐ] Xác nhận trực tiếp Redis | `ZRANGE fams:randomcheck:dispatch 0 -1 WITHSCORES` |
| TH3 [LUỒNG] `/my-pending` time-bound | Check `pending` cách xa >60s KHÔNG hiện; trong 60s → hiện (xem A.9 dưới) |

#### 99. Hủy scheduled check — [ĐL] [TĐ] `tests/randomcheck/test_cancel_scheduled_check.sh`

| Case | Kết quả mong đợi |
|---|---|
| TH1 Hủy check `pending`/`sent` | 200, xóa khỏi Redis queue |
| TH2 Hủy check đã `responded`/`no_response` | 400 |
| TH3 [LUỒNG] Hủy tự động khi assignment bị hủy | Xem A.6 #66 |

---

## Phần B — Kịch bản test theo luồng nghiệp vụ (multi-step)

Mỗi luồng dưới đây gộp nhiều tính năng [LUỒNG] ở Phần A thành 1 chuỗi test liên tục, đúng ví dụ bạn nêu (site → geofence → ca → phân công → chấm công → HR xem kết quả).

### B.1 [LUỒNG CHÍNH] Công trình → Vùng làm việc → Ca làm → Phân công → Chấm công → HR xem kết quả

**Test trên**: tenant `rong-vang-holdings` (tenant rỗng, an toàn để dựng từ đầu) hoặc tenant mới tự tạo.

| Bước | Tính năng (Phần A) | Hành động | Kết quả mong đợi trước khi qua bước sau |
|---|---|---|---|
| 1 | #52 Tạo công trình | `POST /sites` | 201, có `siteId` |
| 2 | #56 Tạo geofence | `POST /sites/{id}/geofences` (đa giác quanh 1 tọa độ demo) | 201, `status=active` |
| 3 | #59 Tạo ca làm việc | `POST /sites/{id}/shifts` (vd 08:00-17:00) | 201, có `shiftId` |
| 4 | #38 Tạo nhân viên | `POST /employees` | 201, có `employeeId` |
| 5 | #63 Phân công | `POST /sites/{id}/assignments` | 201 |
| 6 | #67 Site được phép check-in | Login nhân viên → `GET .../available-sites` | Thấy đúng site vừa tạo |
| 7 | #68 Check-in GPS | Tọa độ TRONG geofence vừa vẽ | 201 `status=valid` |
| 8 | #72 Check-out | Sau ~vài phút | 200, `work_minutes` được tính |
| 9 | #80 Attendance summary | Real-time recompute | Có bản ghi ngày hôm đó |
| 10 | #78 HR xem check-in | HR/owner tenant xem lại | Thấy đúng bản ghi vừa tạo |
| 11 | #86 HR xem bảng công | `GET /attendance` | Số liệu khớp bước 8-9 |

**Case lỗi chèn vào giữa luồng (test riêng, không phá luồng chính)**: check-in NGOÀI geofence ở bước 7 → `pending_review` → HR override ở bước 10 → xác nhận `attendance_summary` cập nhật lại đúng sau override.

### B.2 [LUỒNG] Mời nhân viên → chấp nhận → Face ID → duyệt → chấm công Face ID

1. HR mời (#33) → 2. Ứng viên chấp nhận (#34), nhận JWT luôn → 3. Đồng ý Face ID (#48) → 4. Đăng ký Face ID với ảnh thật (#49) → 5. HR duyệt (#50) → 6. Nhân viên check-in tại site có `checkMode=location_face` (#69) → 7. Đợi vài giây, xem `face_verified=true` ở chi tiết checkin (#79).

**Case lỗi chèn giữa luồng**: bước 5 đổi thành HR TỪ CHỐI (dùng 1 trong 5 lý do đã đa dạng hóa) → bước 6 check-in vẫn KHÔNG cho verify Face ID (vì chưa được duyệt) → xác nhận hệ thống fail-safe đúng như audit trước đó.

### B.3 [LUỒNG] Cấu hình Random Check → sinh lịch → dispatch → phản hồi/không phản hồi → vi phạm → HR xử lý

1. Xem effective config site (#92) → 2. Trigger sinh check thủ công (#96) → 3. Theo dõi Redis dispatch queue (#98) → 4. Nhân viên xem `/my-pending`, xác nhận time-bound đúng (#98b) → 5a. Phản hồi kịp (#respond) → outcome pass → **HOẶC** 5b. Không phản hồi → hết hạn tự `no_response` → tự sinh violation (#17 nhóm cũ) → 6. HR xem/xử lý vi phạm (`resolution=confirmed/dismissed`).

**Case biên**: lặp lại bước 1-4 nhưng đổi `checkMode=location_face_liveness` (dùng cấu hình gamma-logistics có sẵn) → xác nhận bước 5 yêu cầu thêm liveness challenge trước khi respond.

### B.4 [LUỒNG] Đa công ty — 1 tài khoản, 2 vai trò khác nhau

Dùng `dung.pham.hr@gmail.com`: login 1 lần → gọi API acme-corp (quyền HR_MANAGER đầy đủ) → gọi API beta-industries CÙNG token (chỉ quyền SITE_SUPERVISOR site-scoped, chỉ thấy "Nhà máy Bình Minh" không thấy "Kho A") → xác nhận KHÔNG lẫn quyền giữa 2 tenant dù chung 1 JWT.

### B.5 [LUỒNG] RBAC đầy đủ

Tạo role custom (#25) → gán cho 1 nhân viên (#29) → nhân viên đó login, gọi đúng API trong quyền (thành công) và ngoài quyền (403) → thu hồi role (#30) → gọi lại API trong quyền cũ → giờ 403.

### B.6 [LUỒNG] Bảo mật tài khoản

Đăng nhập sai 5 lần (#14) → khóa → reset password để mở khóa sớm (#8) → login lại OK → bật 2FA (#12) → logout (#4) → login lại phải qua bước 2FA (#13) → logout-all từ 1 thiết bị, xác nhận cả 2 thiết bị đều mất quyền (#5).

### B.7 [LUỒNG] Vòng đời gói dịch vụ tenant

Tenant mới ở Trial (#22) → chạm giới hạn nhân viên (#38 TH5) → Platform Admin nâng gói (#22) → tạo thêm nhân viên thành công → (case khác) tenant Trial hết hạn không nâng cấp (`nam-viet-services`) → thử hành động → ghi nhận hành vi thật (cần xác nhận, xem gap đã nêu ở `sample-data-requirements-v2.md` mục 3.3/20.2).

### B.8 [LUỒNG CHÍNH — UAT GO-LIVE] Từ tenant mới hoàn toàn → chấm công → báo cáo → export

**Bổ sung 2026-08-06** theo đúng yêu cầu story "Là một PO/QA, tôi muốn kiểm thử luồng từ tenant đến chấm công và báo cáo để đảm bảo hệ thống sẵn sàng triển khai" — khác B.1 (giả định site/shift đã có sẵn), luồng này bắt đầu từ **tenant chưa tồn tại**, đúng kịch bản triển khai khách hàng mới thật (dùng cho Go-live Checklist, xem `docs/deployment/go-live-checklist.md`).

**Cập nhật 2026-08-19**: audit lại phát hiện AC gốc của #148 yêu cầu rõ chuỗi
`tenant->employee->site->assignment->checkin->summary->random check->violation->report` nhưng bản
2026-08-06 dừng ở báo cáo/export, **thiếu hẳn đoạn kiểm tra ngẫu nhiên → vi phạm** (2 bước 7-8 bên
dưới, mới thêm). Đồng thời đã viết **script tự động hoá đúng luồng 15 bước này**,
`tests/e2e_uat_go_live_flow.sh` — không chỉ là tài liệu cho người chạy tay, mà một lệnh
`BASE_URL=... bash tests/e2e_uat_go_live_flow.sh` chạy được cả chuỗi thật với dữ liệu thật (không
gồm Face ID/liveness vì AC #148 không yêu cầu — phần đó vẫn giữ ở bước 7 cũ bên dưới cho người
test tay muốn phủ luôn Face ID). Test live: PASS 16/16, chạy lại 2 lần liên tiếp đều pass.

| Bước | Hành động | Endpoint | Kết quả mong đợi |
|---|---|---|---|
| 1 | Platform Admin tạo tenant mới | `POST /api/v1/tenants` — **bắt buộc** `ownerEmail` (thiếu field này là lỗi kịch bản test phổ biến nhất gặp trong session, không phải lỗi hệ thống) | 201, có `tenantId`; chủ sở hữu nhận được tài khoản Owner |
| 2 | Owner đăng nhập lần đầu | `POST /auth/login` | 200, `accessToken` |
| 3 | Owner/Admin tạo công trình + geofence + ca làm | `POST /sites`, `POST /sites/{id}/geofences`, `POST /sites/{id}/shifts` | 201 từng bước (= B.1 bước 1-3) |
| 4 | HR mời nhân viên đầu tiên | `POST /tenants/{id}/invitations` (cần `ownerEmail`/email hợp lệ) | 201, có invitation token (đọc từ response CREATE, không phải từ list) |
| 5 | Ứng viên chấp nhận lời mời | `POST /invitations/accept` | 200/201, nhận JWT luôn, `Employee` được tạo |
| 6 | HR phân công vào site + ca | `POST /sites/{id}/assignments` | 201 |
| 7 | Nhân viên đăng ký Face ID (đồng ý + ảnh thật) | `POST .../faceid/consent`, `POST .../faceid/enroll` | 201, `status=pending_review` |
| 8 | HR duyệt Face ID | `POST .../faceid/approve` | 200, `status=enrolled` |
| 9 | Nhân viên check-in trong geofence | `POST /checkin` (site có `checkMode=location_face` để dùng luôn Face ID vừa duyệt) | 201, `status=valid`, `face_verified=true` |
| 10 | Nhân viên check-out | `POST /checkin/{id}/checkout` | 200, `work_minutes` tính đúng |
| 11 | HR xem báo cáo chấm công | `GET /reports/attendance/daily` hoặc `/monthly` | Có đúng bản ghi bước 9-10 |
| 12 | HR export báo cáo | `GET /reports/attendance/export` | 200, file `.xlsx` hợp lệ, mở được, dữ liệu khớp bước 11 |
| 13 | HR lưu bộ lọc thường dùng cho màn báo cáo | `POST /tenants/{id}/saved-filters` | 201, `isDefault` hoạt động đúng (xem mục Story "Lưu bộ lọc thường dùng") |
| 14 | Platform Admin trace lại toàn bộ thao tác vừa làm | `GET /audit-logs?tenantId={id}` | Thấy đúng các hành động bước 1-13 có audit (login, TOTP nếu có bật — **lưu ý**: tạo/sửa Employee hiện CHƯA được audit, xem giới hạn đã biết ở báo cáo audit 2026-08-06) |
| 15 | Platform Admin kiểm tra trạng thái hệ thống trước khi bàn giao | `GET /api/v1/platform/system-status` | `overallHealth=UP`, DB/Redis/FCM/**AI service** (mới 2026-08-06) đều `UP` |

**Case lỗi chèn giữa luồng**: bước 9 đổi thành nhân viên khác gọi check-in hộ (tài khoản B check-in bằng token của A) → xác nhận bị chặn đúng bởi permission guard tự-scope theo `callerUserId` (không dựa vào `@PreAuthorize` mà dựa vào việc mọi service tự tra `userId` từ JWT, xem báo cáo audit "Permission Guard" 2026-08-06).

**Dữ liệu nhạy cảm cần xác nhận trong luồng này (Data Masking, 2026-08-06)**: ở bước 11, gọi lại `GET /employees/{id}` bằng tài khoản KHÔNG có quyền `users:create` → xác nhận `email`/`phone` bị che (`a***@...`, `***xxx`); gọi `GET /employees/export` cùng tài khoản → xác nhận file Excel cũng che giống hệt, không còn lệch giữa JSON và Excel như trước bản vá.

---

## Phần C — Tổng hợp: tính năng CHỈ test được thủ công (không tự động hóa hoàn toàn) và lý do

| # | Tính năng | Lý do không tự động hóa hoàn toàn |
|---|---|---|
| 2 | Đăng nhập OTP | Cần Firebase Phone Auth thật (SMS/reCAPTCHA) |
| 3 | Đăng nhập Google | Cần Google ID token thật từ OAuth flow thật |
| 12/13 | 2FA (bật/đăng nhập) | Script tự động được nhờ `pyotp`, nhưng xác nhận UX quét QR bằng Authenticator app thật vẫn cần test tay |
| 19 (TH5) | IP whitelist chặn thật | Cần gọi API từ 1 IP thật ngoài dải whitelist |
| 42 (TH2) | Export nhân viên — mở file | Cần mở file `.xlsx` thật bằng Excel để xác nhận font/encoding |
| 49 | Đăng ký Face ID | Cần ảnh khuôn mặt thật hợp lệ để ai-service xử lý |
| 69/70 | Check-in Face ID/liveness | Cần ảnh/frame thật qua ai-service, kết quả verify là async — cần đợi thật |
| 75 (TH3) | Offline sync conflict | Cố ý không có dữ liệu giả sẵn (đúng quyết định trong `sample-data-requirements-v2.md`) — phải tự tạo tình huống trùng lúc test |
| 88 (TH3) | Nhận push thật trên điện thoại | Cần app FE thật cài trên thiết bị thật, đăng ký device token thật |
| 96 (TH2) | Job tự động 00:01 hàng ngày | Không trigger tay được đúng nghĩa "tự động đầu ngày" — chỉ xem log hoặc đợi thật |

**Tất cả các tính năng còn lại** (89/99) đã có ít nhất 1 script tự động (`tests/*/test_*.sh`) chạy xác nhận được response — kịch bản thủ công ở Phần A dùng để **bổ sung case lỗi/biên cụ thể theo đúng dữ liệu mẫu**, không phải thay thế script tự động.
