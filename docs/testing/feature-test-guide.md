# Hướng dẫn test tính năng bằng dữ liệu mẫu — FAMS

> **Lưu ý:** phần tra cứu tài khoản bên dưới là hồ sơ kiểm thử lịch sử của seed v2 và không còn là nguồn dữ liệu mặc định. Seed v3 hiện có 3 tenant, trong đó `demo-an-phat` có 15 thành viên. Dùng danh sách tài khoản hiện hành tại `docs/testing/demo-seed-data.md`. Mật khẩu mặc định: **`Admin@1234`**. Base URL dev: `http://localhost:8080`.
>
> Tài liệu chia 2 phần: **Phần A** — tra cứu nhanh tài khoản/dữ liệu theo từng tính năng đơn lẻ (đối chiếu đúng thứ tự checklist 99 mục bạn đưa). **Phần B** — các kịch bản kiểm thử theo luồng nghiệp vụ nhiều bước.
>
> **Lưu ý về đăng nhập**: hầu hết tài khoản dùng để đăng nhập là **email hoặc phone của `users`** (ví dụ `chu.hoanglong@gmail.com`) — KHÁC với `employees.email` (email liên hệ nội bộ trong công ty, ví dụ `an.nguyen@hoanglong.vn`). 2 người "đa công ty" (mục A.15) là ví dụ rõ nhất: work-email trong từng công ty khác nhau (`dung.pham@hoanglong.vn`, `dung.pham@binhminh.vn`) nhưng **CÙNG 1 login** (`dung.pham.hr@gmail.com`).

---

## Phần A — Tra cứu theo từng tính năng đơn lẻ

### A.1 Auth & tài khoản cá nhân

| # | Tính năng | Dữ liệu mẫu dùng để test | Bước test | Kỳ vọng |
|---|---|---|---|---|
| 1 | Đăng nhập email/mật khẩu | `chu.hoanglong@gmail.com` / `Admin@1234` | `POST /api/v1/auth/login` `{"identifier":"chu.hoanglong@gmail.com","password":"Admin@1234"}` | 200, trả `accessToken`+`refreshToken` |
| 1b | Đăng nhập sai mật khẩu | Cùng tài khoản trên, mật khẩu sai | Gọi lại với `"password":"SaiRoi123"` | 401, `failedLoginAttempts` tăng 1 (query DB `users.failed_login_attempts`) |
| 2 | Đăng nhập bằng số điện thoại OTP | Không có sẵn số điện thoại thật để nhận SMS trong seed — dùng `app.sms.dev-mode=true` (OTP log ra console `fams-api`) | `POST /auth/otp/verify` sau khi gửi OTP qua Firebase test flow — xem `docker logs fams-api` để lấy OTP dev-mode | Đăng nhập thành công, nhận token |
| 3 | Đăng nhập Google | `dangnhapgoogle@gmail.com` (có `google_id` giả, KHÔNG login qua Google thật được — chỉ dùng để test hiển thị/link/unlink) | `GET /auth/me` sau khi build token thủ công, hoặc test `/auth/link-google`/`/unlink-google` bằng tài khoản khác đã login email | Xem field `googleLinked=true` |
| 4 | Đăng xuất khỏi thiết bị hiện tại | Bất kỳ tài khoản nào, ví dụ `hotro2.nentang@fams.com` | Login lấy `accessToken`+`refreshToken` → `POST /auth/logout` kèm `refreshToken` | 200; gọi lại API bằng access token cũ → 401 (đã blacklist) |
| 5 | Đăng xuất khỏi tất cả thiết bị | `dung.pham.hr@gmail.com` (login thử ở "nhiều thiết bị" bằng cách login 2 lần lấy 2 access token khác nhau) | `POST /auth/logout/all` bằng 1 trong 2 token | Cả 2 token cũ đều 401 sau đó |
| 5b | Đăng xuất 1 phiên cụ thể (session khác) | — | `GET /auth/sessions` xem danh sách, `DELETE /auth/sessions/{id}` | Session đó bị revoke, các session khác còn sống |
| 6 | Đăng ký tài khoản người dùng | Email mới bất kỳ chưa tồn tại, ví dụ `test.dangky.moi@gmail.com` | `POST /auth/register` `{"email":...,"password":"Admin@1234","displayName":"..."}` | 201, `email_verified=false` — **đây chính là case #1 trong bảng A.2 dưới, không cần verify tay** |
| 7 | Quên mật khẩu | `chu.hoanglong@gmail.com` | `POST /auth/forgot-password` `{"email":"chu.hoanglong@gmail.com"}` | 200 luôn (không lộ email có tồn tại hay không); xem `docker logs fams-api` để lấy link reset (dev không gửi mail thật nếu GMAIL chưa cấu hình) |
| 8 | Đặt lại mật khẩu | Token lấy từ bước 7 | `POST /auth/reset-password` `{"token":...,"newPassword":"MatKhauMoi@123"}` | 200; login lại bằng mật khẩu mới thành công, mật khẩu cũ (`Admin@1234`) không dùng được nữa cho tài khoản này — **nên test trên 1 tài khoản KHÔNG quan trọng** (vd tạo mới ở bước 6) để không phá mật khẩu chung của tài khoản demo khác |
| 9 | Đổi mật khẩu | Đăng nhập bằng `hotro2.nentang@fams.com` | `POST /auth/change-password` `{"currentPassword":"Admin@1234","newPassword":"MoiHon@123"}` | 200; mọi session khác của tài khoản này bị đăng xuất — **cũng nên test trên tài khoản không quan trọng** |
| 10 | Xem thông tin cá nhân | Bất kỳ, ví dụ `binh.tran@hoanglong.vn` (SITE_SUPERVISOR) | `GET /auth/me` | Trả đủ field profile + có thể thấy field `roles`/`tenantId` liên quan |
| 11 | Cập nhật hồ sơ cá nhân | `lan.bui@hoanglong.vn` | `PATCH /auth/me` `{"hometown":"Nam Định","gender":"female"}` | 200, field cập nhật đúng |
| 12 | Bật TOTP 2FA | Dùng **tài khoản mới** (không dùng `bat2fa1/2@gmail.com` vì đã bật sẵn — xem A.1 mục 13) | Login → `POST /auth/totp/setup` lấy `manualEntryKey` → dùng `pyotp`/Google Authenticator sinh mã → `POST /auth/totp/verify` | Trả `backupCodes` 1 lần |
| 13 | Đăng nhập có 2FA | **`bat2fa1@gmail.com`** hoặc **`bat2fa2@gmail.com`** / `Admin@1234` — 2 tài khoản này ĐÃ bật TOTP sẵn qua seed (secret in ra ở lần chạy seed gần nhất, xem log hoặc mục dưới) | `POST /auth/login` → trả `totpRequired:true`+`pendingToken` (không có accessToken) → sinh mã TOTP từ secret → `POST /auth/login/totp` `{"pendingToken":...,"code":...}` | 200, trả accessToken thật. **Secret hiện tại**: xem file `/tmp/seed_run_clean.log` dòng `TOTP 2FA đã bật thật` (mỗi lần reseed sinh secret MỚI — bí danh cũ hết hiệu lực) |
| 13b | Đăng nhập 2FA bằng backup code | Cùng 2 tài khoản trên | `/auth/login/totp` dùng `{"pendingToken":...,"backupCode":"<1 trong 8 mã in ra log>"}` | 200; dùng lại backup code đó lần 2 → lỗi (1 lần dùng) |
| 14 | Khóa tài khoản khi đăng nhập sai | **`taikhoanbikhoa@gmail.com`** — ĐÃ bị khóa sẵn trong seed (`locked_until` = seed_time+60 phút) | `POST /auth/login` với mật khẩu đúng `Admin@1234` | 423/401 báo tài khoản đang khóa (không phụ thuộc mật khẩu đúng/sai vì đã khóa sẵn). Muốn test **tự tay khóa từ đầu**: tạo tài khoản mới, gọi login sai 5 lần liên tiếp |

### A.2 Ma trận xác thực tài khoản (bổ sung, phục vụ trực tiếp việc test edge-case đăng nhập)

| Case | Tài khoản | Kỳ vọng khi login bằng `Admin@1234` |
|---|---|---|
| Email chưa xác thực | `chuaxacthucmail1@gmail.com`, `chuaxacthucmail2@gmail.com` | Bị chặn — lỗi kiểu "vui lòng xác thực email" |
| Phone chưa xác thực (email vẫn OK) | `chuaxacthucphone1@gmail.com`, `chuaxacthucphone2@gmail.com` | Login BÌNH THƯỜNG (chỉ phone chưa verify, không chặn login bằng email) |
| Google-only, không có password | `dangnhapgoogle@gmail.com` | Login bằng password sẽ lỗi (401) vì `password_hash=NULL` — dùng để test message lỗi phù hợp, không phải bug |
| Platform staff bị vô hiệu hóa | `kythuat3.nentang@fams.com` | Login lỗi 401/403 dù mật khẩu đúng (`is_active=false`) |

### A.3 Tenant / Platform / Gói dịch vụ

| # | Tính năng | Dữ liệu mẫu | Bước test | Kỳ vọng |
|---|---|---|---|---|
| 15 | Tạo tenant mới | Login `admin@fams.com` | `POST /tenants` với `ownerEmail` là 1 email MỚI chưa tồn tại | 201 |
| 16 | Xem danh sách tenant | `admin@fams.com` | `GET /tenants?size=20` | Thấy đủ 18 tenant |
| 17 | Cập nhật thông tin tenant | Tenant `rong-vang-holdings` (tenant rỗng, an toàn để sửa) | `PATCH /tenants/{id}` đổi `industry` | 200 |
| 18 | Cấu hình giao diện và định dạng | Tenant `acme-corp` (đã có brand color/định dạng riêng từ seed: đỏ `#B22222`) | `GET /tenants/{id}/settings` rồi `PATCH` đổi `brandPrimaryColor` | 200, xem lại đúng giá trị mới |
| 19 | Quản lý IP whitelist | Tenant `acme-corp` — ĐÃ có sẵn 2 IP whitelist (`203.113.128.0/24`, `14.161.0.0/16`) | `GET /tenants/{id}/ip-whitelists`; test chặn thật: gọi API từ IP ngoài dải này với 1 user thuộc acme-corp | Bị chặn nếu whitelist đang active — **muốn test nhanh không bị chặn khi dev**: đa số tenant khác KHÔNG bật whitelist |
| 20 | Quản lý gói dịch vụ | `admin@fams.com` | `GET /plans?activeOnly=false` | Thấy 5 gói: trial/basic/pro/enterprise (active) + `legacy_basic` (**đã deactivate — dùng để test hiển thị gói ngừng bán**) |
| 21 | Cấu hình giới hạn gói | `admin@fams.com` | `GET /plans/{trialPlanId}/limits` | `maxEmployees=5, maxSites=1` — test enforcement thật: xem A.mục "Tạo nhân viên thủ công" case Tia Sáng bên dưới |
| 22 | Gán subscription cho tenant | Tenant `rong-vang-holdings` (đang TRIAL mặc định) | `POST /tenants/{id}/subscription` gán qua `pro` | 200/201 |
| 22b | Test luồng deactivate gói có tenant đang dùng (Issue #8) | `dai-duong-fishery` — **ĐÃ được migrate tự động từ `legacy_basic` sang `basic`** khi seed deactivate gói legacy | `GET /tenants/{daiduongId}/subscription` | `planId` = basic, không phải legacy_basic — xác nhận migrate tự động hoạt động đúng |
| — | Tenant rỗng hoàn toàn (test empty-state) | `rong-vang-holdings` — chủ `owner.rongvang@gmail.com` | Login chủ này, gọi `GET .../sites`, `.../employees`, `.../workspaces` | Toàn bộ trả mảng rỗng — test UI/API xử lý empty-state đúng, không lỗi 500 |
| — | Trial sắp hết hạn | `hoa-phuong-trading` — chủ `owner.hoaphuong@gmail.com`, hết hạn ~2 ngày sau thời điểm seed | `GET /tenants/{id}/subscription` | `expiresAt` gần hiện tại — test banner cảnh báo phía FE (BE không tự khóa trước hạn) |
| — | Trial đã hết hạn, chưa nâng cấp | `nam-viet-services` — chủ `owner.namviet@gmail.com`, hết hạn 5 ngày trước thời điểm seed | Login chủ này, thử `POST .../employees` tạo nhân viên mới | Kỳ vọng: xem hệ thống có thực sự khóa tính năng khi trial hết hạn hay chỉ hiển thị cảnh báo — **đây là điểm cần xác nhận lại nghiệp vụ, chưa chắc BE đã enforce cứng** |

### A.4 RBAC (Role & Permission) & Audit

| # | Tính năng | Dữ liệu mẫu | Bước test | Kỳ vọng |
|---|---|---|---|---|
| 23 | Seed role và permission hệ thống | — | `GET /roles?tenantId=null` (hoặc không truyền tenantId) | Thấy 5 role hệ thống: PLATFORM_ADMIN, TENANT_ADMIN, HR_MANAGER, SITE_SUPERVISOR, EMPLOYEE |
| 24 | Danh sách role | `admin@fams.com` | `GET /roles` | Gồm role hệ thống + role custom (xem #25) |
| 25 | Tạo role tùy chỉnh | Login chủ `acme-corp` (`chu.hoanglong@gmail.com`) — tenant NÀY đã có sẵn 1 role custom "Kế toán trưởng" để đối chiếu, nhưng nên **tự tạo role MỚI** để test sạch | `POST /roles` `{"name":"Test Role","tenantId":"...","permissionIds":[...]}` | 201 |
| 26 | Sửa role và quyền | Role vừa tạo ở #25 (KHÔNG sửa "Kế toán trưởng"/"Trưởng ca đêm"/"Điều phối viên Kho" — 3 role demo có sẵn, sửa sẽ làm lệch dữ liệu mẫu cho người khác) | `PUT /roles/{id}` đổi tên/quyền | 200 |
| 27 | Xóa hoặc vô hiệu hóa role | Role vừa tạo ở #25 | `DELETE /roles/{id}` (nếu chưa gán ai) hoặc `PUT` với `isActive:false` | 200/204 |
| 28 | Xem permission theo nhóm | Bất kỳ user đã login | `GET /permissions` | Trả permission gom nhóm theo resource (`employees`, `sites`, `checkins`,...) |
| 29 | Gán role cho user | Platform staff `kythuat1.nentang@fams.com` (đang chỉ có PLATFORM_STAFF) + 1 trong 5 role nền tảng custom CHƯA GÁN AI: `PLATFORM_ONBOARDING_SPECIALIST`, `PLATFORM_QA_REVIEWER`, `PLATFORM_NOTIFICATION_MANAGER`, `PLATFORM_COMPLIANCE_OFFICER`, `PLATFORM_PARTNER_MANAGER` | `POST /user-roles/platform` gán 1 trong 5 role trên cho `kythuat1.nentang@fams.com` | 201 |
| 30 | Thu hồi role | Role vừa gán ở #29 | `DELETE /user-roles/{id}` | 200/204; user đó không còn quyền tương ứng — **quan trọng**: `kythuat2.nentang@fams.com` vẫn giữ nguyên `PLATFORM_STAFF` (test đúng ý "thu hồi 1 người không ảnh hưởng người khác cùng role") |
| 31 | Ghi audit cho hành động quan trọng | Đăng nhập `chu.hoanglong@gmail.com` vài lần (đúng/sai mật khẩu) | `GET /audit-logs?entityType=User` (hoặc tương đương) | Thấy log auth — **LƯU Ý đã audit ở phần audit trong báo cáo trước**: audit hiện CHỈ phủ auth, CHƯA phủ tenant/RBAC/subscription — đừng kỳ vọng thấy log khi tạo role/gán subscription, đó là gap đã biết |
| 32 | Tạo notification in-app cơ bản | Nội bộ (`POST /internal/notifications`, không cần auth — chỉ gọi được từ mạng nội bộ) | Không cần test tay riêng — đã tự động sinh trong seed lịch sử, xem A.9 |

### A.5 Nhân viên, mời, workspace, Face ID

| # | Tính năng | Dữ liệu mẫu | Bước test | Kỳ vọng |
|---|---|---|---|---|
| 33 | Mời nhân viên bằng email | Login HR `dung.pham.hr@gmail.com` (HR_MANAGER @ acme-corp) | `POST /tenants/{acmeId}/invitations` email mới | 201, xem `docker logs fams-api` lấy link (nếu mail chưa cấu hình thật) |
| 34 | Chấp nhận lời mời | **7 lời mời `pending` có sẵn, dùng token trực tiếp** — ví dụ acme-corp: `hoa.nguyen.moi@hoanglong.vn` (token trong DB `employee_invitations.token`, tra bằng `docker exec fams-postgres psql ... "SELECT token FROM employee_invitations WHERE email='hoa.nguyen.moi@hoanglong.vn'"`) | `POST /invitations/accept` `{"token":"...","password":"Admin@1234"}` | 201, tạo user + employee liên kết, trả JWT luôn |
| 35 | Hủy lời mời | Lời mời `pending` còn lại (còn 6 sau khi dùng 1 ở #34), ví dụ `phuong.dang.choloi@hoanglong.vn` | `DELETE /tenants/{id}/invitations/{invitationId}` | 200; trạng thái → cancelled (đối chiếu với 3 invitation ĐÃ cancelled sẵn: `kiet.tran.moi@hoanglong.vn`, `hung.le.moi@binhminh.vn`, `phong.vu.moi@phuongnam.vn`, và 2 đã EXPIRED sẵn: `vi.pham.het.han@hoanglong.vn`, `khanh.ly.het.han@phuongnam.vn` — đủ 4 trạng thái để đối chiếu) |
| 36 | Danh sách nhân viên | HR `dung.pham.hr@gmail.com` | `GET /tenants/{acmeId}/employees?size=50` | 30 nhân viên (15 gốc + 15 bổ sung mã `HL-X...`) |
| 37 | Xem chi tiết nhân viên | Employee code `HL-002` (Trần Thị Bình) | `GET /tenants/{id}/employees/{empId}` | Thấy role/workspace/site/Face ID status đầy đủ |
| 38 | Tạo nhân viên thủ công | HR `dung.pham.hr@gmail.com` @ acme-corp (Pro, còn nhiều chỗ trống) | `POST /tenants/{id}/employees` | 201 |
| 38b | Test enforcement giới hạn gói khi tạo | **`kimngan.tiasang@gmail.com`** @ `tia-sang-startup` — ĐANG ĐÚNG 5/5 giới hạn Trial | `POST /tenants/{tsId}/employees` thêm 1 người | 422 `PLAN_LIMIT_EXCEEDED` (đúng ý muốn test) |
| 39 | Cập nhật nhân viên | `HL-011` (Phan Văn Phúc) | `PATCH /tenants/{id}/employees/{empId}` | 200 |
| 40 | Tạm ngừng/nghỉ việc nhân viên | Nhân viên mới tạo ở #38 (không đổi trạng thái nhân viên demo có sẵn) | `PATCH .../status` `{"status":"terminated"}` | 200; xem assignment/scheduled-check liên quan tự hủy (đối chiếu case đã có sẵn: `HL-012`, `BM-014` đã `terminated` sẵn) |
| 41 | Import danh sách nhân viên | File `.xlsx` tự chuẩn bị, tenant `hoang-gia-fnb` (nhẹ, ít dữ liệu, an toàn để test import) | `POST /tenants/{id}/employees/import` multipart | 200, trả kết quả từng dòng |
| 42 | Export danh sách nhân viên | Tenant `acme-corp` | `GET /tenants/{id}/employees/export` | Trả file `.xlsx` 30 dòng |
| 43 | Tạo workspace | Tenant `rong-vang-holdings` (rỗng, an toàn) | `POST /tenants/{id}/workspaces` | 201 |
| 44 | Danh sách workspace | Tenant `acme-corp` | `GET /tenants/{id}/workspaces?size=20` và `.../workspaces/tree` | 13 workspace phẳng; xem tree có 3 cấp: vd "Đội Kỹ thuật Hà Nội" → "Nhóm Kỹ thuật Ca sáng" |
| 45 | Cập nhật workspace | Workspace "Kỹ thuật" @ acme-corp | `PUT /tenants/{id}/workspaces/{wsId}` | 200 |
| 45b | Xem workspace đã deactivate | "Pháp chế" (acme-corp), "Xuất nhập khẩu" (beta-industries), "Tài chính" (gamma-logistics) — cả 3 đã `status=inactive` sẵn | `GET` chi tiết | Xem đúng status inactive |
| 46 | Gán nhân viên vào workspace | Nhân viên `HL-011` chưa có trong workspace nào (kiểm tra trước) | `POST /tenants/{id}/workspaces/{wsId}/members` | 201 |
| 47 | Chuyển workspace cho nhân viên | Nhân viên đã ở workspace "Kỹ thuật" (vd `HL-006`) | `POST .../members/{memberId}/transfer` sang workspace khác | 200 |
| 48 | Ghi nhận đồng ý Face ID | Nhân viên có `face_profiles.consent_given=false`/chưa có hồ sơ — hoặc test lại trên nhân viên mới tạo ở #38 (100% nhân viên demo cũ đã consent sẵn) | Login chính nhân viên đó → `POST .../face-id/consent` | 200 |
| 49 | Đăng ký Face ID | Nhân viên có `face_profiles.status='not_enrolled'` và `review_status='none'` (chưa từng nộp) — query: `SELECT employee_code FROM employees e JOIN face_profiles fp ON fp.employee_id=e.id WHERE fp.status='not_enrolled' AND fp.review_status='none' AND e.tenant_id=(SELECT id FROM tenants WHERE slug='acme-corp')` | `POST .../face-id/enroll` kèm ảnh thật (cần ảnh thật để qua được ai-service — dữ liệu seed chỉ có embedding giả, KHÔNG dùng để test enroll mới) | 201, vào hàng chờ duyệt |
| 50 | HR xem trạng thái Face ID | HR `dung.pham.hr@gmail.com` | `GET /tenants/{id}/face-id/pending-review` | Thấy danh sách đang chờ duyệt (có sẵn ~10 người/tenant ở trạng thái `pending`, ~6-8 người ở trạng thái `rejected` có lý do) |
| 51 | Xóa/vô hiệu hóa Face ID | Nhân viên có `face_profiles.status='enrolled'` (đa số nhân viên demo) | `DELETE .../face-id` | 200; status → revoked (đối chiếu ~9 người đã sẵn `revoked` để so sánh) |

### A.6 Site / Geofence / Shift / Assignment

| # | Tính năng | Dữ liệu mẫu | Bước test | Kỳ vọng |
|---|---|---|---|---|
| 52 | Tạo công trình | Tenant `rong-vang-holdings` (rỗng) | `POST /tenants/{id}/sites` | 201 |
| 53 | Danh sách công trình | Tenant `acme-corp` | `GET /tenants/{id}/sites?size=20` | 13 site (3 site chính đặt tên riêng + 10 site phụ mã `HL-04..HL-13`, trải nhiều tỉnh/thành) |
| 54 | Xem chi tiết công trình | Site code `HL-HN` | `GET /tenants/{id}/sites/{siteId}` | Có geofence active, danh sách ca, số phân công |
| 55 | Cập nhật công trình | Site `HL-HCM` | `PUT /tenants/{id}/sites/{siteId}` | 200 |
| 55b | Site đã deactivate | "Công trình Hoàng Long - Nam Định" (acme-corp), "Nhà máy/Kho Bình Minh - Nam Định" (beta-industries), "Kho vận Phương Nam - Nam Định" (gamma-logistics) | `GET` chi tiết | `status=inactive` |
| 56 | Tạo geofence cho công trình | Site mới tạo ở #52 | `POST .../geofences` | 201 |
| 57 | Sửa geofence | Site `HL-HN` (đã có geofence active) | `PUT .../geofences/active` | 200, tạo bản ghi mới, bản cũ → superseded |
| 58 | Xem lịch sử geofence | Site bất kỳ trong 5 tenant giàu dữ liệu (khoảng nửa số site có sẵn 1 bản superseded) | `GET .../geofences` | Thấy ≥2 bản ghi: 1 active + 1 superseded |
| 59 | Tạo ca làm việc | Site mới #52 | `POST .../shifts` | 201 |
| 60 | Cấu hình OT và giới hạn giờ | Shift "Ca ngắn (6h)" @ `BM-MAIN` (dung sai vào sớm 45p/ra muộn 5p — cố tình lệch để dễ nhận diện khi test) | `PUT .../shifts/{id}/ot-config` | 200 |
| 61 | Danh sách ca theo site | Site `HL-HN` | `GET .../shifts` | 3 ca: Ca sáng/Ca chiều/Ca đêm |
| 62 | Cập nhật hoặc ngừng dùng ca | "Ca tối" @ BM-WH-A (đã inactive sẵn) hoặc ca khác | `PUT .../shifts/{id}` `{"status":"inactive"}` | 200 |
| 63 | Tạo phân công nhân viên vào site | Site mới #52, nhân viên mới #38 | `POST .../assignments` | 201 |
| 64 | Danh sách phân công | Site `HL-HN` | `GET .../assignments` | Thấy đủ role worker/supervisor |
| 65 | Cập nhật phân công | 1 assignment bất kỳ tại `HL-HN` | `PUT .../assignments/{id}` | 200 |
| 66 | Hủy phân công | **ĐÃ có sẵn 3 case đã hủy** (1/tenant chính, tại HL_HCM/BM_WH_A/PN_S) — muốn test tự tay thì chọn 1 assignment active khác | `DELETE .../assignments/{id}` | 200, status → cancelled |
| 66b | Phân công hết hạn TỰ NHIÊN (khác hủy chủ động) | **3 case có sẵn**: `HL-011`@HL-DN, `BM-006`@BM-WH-A, `PN-006`@PN-S — `status=active` nhưng `endDate` đã qua (2025-12-31) | `GET .../assignments?employeeId=...` | `status` vẫn `active`, không phải `cancelled` — kiểm tra chấm công/random-check KHÔNG áp dụng cho case này nữa dù DB status chưa đổi |
| 67 | Hiển thị site được phép check-in | Nhân viên `binh.tran@hoanglong.vn` | `GET /tenants/{id}/checkin/available-sites` | Chỉ thấy site có assignment hiệu lực hiện tại |

### A.7 Check-in / Check-out / Attendance

| # | Tính năng | Dữ liệu mẫu | Bước test | Kỳ vọng |
|---|---|---|---|---|
| 68 | Check-in GPS cơ bản | `giang.hoang@hoanglong.vn` (EMPLOYEE, assignment tại HL-HN) | Login → `POST /tenants/{id}/checkin` với tọa độ trong geofence HL-HN (~21.0285, 105.8542) | 201, `status=valid` |
| 69 | Check-in có Face ID | Site có config `location_face`/`location_face_liveness` — site override `HL-HN` hoặc `PN-N` (đã cấu hình `location_face` sẵn), hoặc tenant-default beta-industries (`location_face`) | Check-in bằng nhân viên có `face_profiles.status='enrolled'` | Verify async — xem `GET .../checkin/{id}` sau vài giây |
| 70 | Check-in có liveness | Tenant-default `gamma-logistics` = `location_face_liveness` | Cần `POST .../liveness-challenge` trước rồi mới check-in | 201 nếu challenge pass |
| 71 | Kiểm tra check-in sớm | Bất kỳ nhân viên nào, gọi check-in TRƯỚC giờ ca nhiều hơn `earlyCheckinMinutes` | `POST .../checkin` | 422 `CHECKIN_TOO_EARLY` |
| 72 | Check-out GPS | Sau khi có 1 checkin `valid` | `POST .../checkin/{id}/checkout` | 200 |
| 73 | Kiểm tra check-out muộn | Checkout sau `lateCheckoutMinutes` cho phép, dùng ca "Ca ngắn (6h)" @ BM-MAIN (chỉ cho phép trễ 5 phút — dễ trigger) | `POST .../checkout` trễ >5 phút | Bị cap work_minutes / có thể escalate |
| 74 | Tính work_minutes | Bất kỳ cặp checkin/out | `GET .../checkin/{id}` | `workMinutes` khớp chênh lệch thời gian |
| 75 | Check-in offline và đồng bộ | Bất kỳ nhân viên | `POST /tenants/{id}/checkin/sync` với `clientNonce` + timestamp quá khứ hợp lệ | 200/201; gọi lại CÙNG nonce → idempotent, không tạo trùng |
| 76 | Hiển thị kết quả check-in/out | Bất kỳ | `GET /checkin/{id}` | `message` tiếng Việt phù hợp trạng thái |
| 77 | Nhân viên xem lịch sử chấm công | `binh.tran@hoanglong.vn` | `GET /checkin/history` | Thấy checkin của chính mình trong ~75 ngày qua |
| 78 | HR xem danh sách check-in | `dung.pham.hr@gmail.com` | `GET /tenants/{id}/checkin?siteId=...` | Danh sách toàn site, có cả `pending_review` (~14% ngẫu nhiên) |
| 79 | HR xem chi tiết check-in | 1 checkin `pending_review` (đã có `employee_note`+`employee_photo_url` demo sẵn cho ~50% case) | `GET .../checkin/{id}/detail` | Đầy đủ bằng chứng |
| 80 | Tự động tạo attendance summary | — | `GET /attendance?siteId=...&date=...` (dữ liệu đã có sẵn từ job + backfill) | Có bản ghi mỗi ngày làm việc |
| 81 | Tính đi muộn | Bất kỳ ngày | `GET /attendance/me` (login nhân viên) | Field `isLate`/`lateMinutes` |
| 82 | Tính về sớm | — | cùng trên | `isEarlyLeave`/`earlyLeaveMinutes` |
| 83 | Tính OT | Ca có `allowOvertime=true` (vd "Ca sáng" @ HL-HN) | — | `otMinutes` > 0 một số ngày |
| 84 | Phát hiện thiếu checkout | — | `GET /attendance?...&missingCheckout=true` (nếu filter có) hoặc xem field `missingCheckout` | Có ngày `missingCheckout=true` |
| 85 | Nhân viên xem bảng công ngày/tháng | `binh.tran@hoanglong.vn` | `GET /attendance/me/monthly?month=2026-06` (dữ liệu trải từ tháng 5 → 7/2026) | Tổng hợp cả tháng: lateDays, OT, missingCheckoutDays |
| 86 | HR xem bảng công tổng hợp | `dung.pham.hr@gmail.com` | `GET /tenants/{id}/attendance/monthly?month=2026-06` | Tổng hợp toàn site/tenant |

### A.8 Notification & Random Check

| # | Tính năng | Dữ liệu mẫu | Bước test | Kỳ vọng |
|---|---|---|---|---|
| 87 | Đăng ký thiết bị nhận push | Bất kỳ user | `POST /me/devices` `{"deviceToken":"test-token-123"}` | 201 |
| 88 | Gửi push notification | Trigger qua random check manual (xem A.9) hoặc dùng device đã có sẵn `demo-fcm-token-dung-0001` (của `dung.pham.hr@gmail.com`) | Xem `notification_delivery_logs` sau khi trigger sự kiện | Có log gửi (FAILED vì token giả, nhưng chứng minh flow chạy) |
| 88b | Test device đã unregister KHÔNG nhận push | `demo-fcm-token-dung-huy-0002` — đã soft-delete sẵn | Trigger 1 event cho `dung.pham.hr@gmail.com` | Không có log gửi tới token này |
| 89 | Danh sách thông báo trong app/web | `dung.pham.hr@gmail.com` | `GET /tenants/{id}/notifications` | Có sẵn ~13 loại notification demo/tenant, mix đã đọc/chưa đọc |
| 90 | Đánh dấu đã đọc | 1 notification `isRead=false` | `PATCH .../notifications/{id}/read` | 200 |
| 90b | Test đủ 4 tổ hợp in-app/push | `dung.pham.hr@gmail.com`: `attendance.late_checkin`=(bật,bật), `attendance.ot_detected`=(tắt,tắt), `violation.raised`=(bật,bật); `truong.van.dat@gmail.com`: `randomcheck.dispatched`=(bật,tắt), `assignment.created`=(tắt,bật) | `GET /me/notification-settings` mỗi user | Xem đủ 4 tổ hợp True/False khi gộp 2 user lại |
| 91 | Tạo cấu hình random check mặc định tenant | Tenant `rong-vang-holdings` (rỗng, chưa có config) | `POST .../random-check-configs/tenant-default` | 201 |
| 92 | Tạo cấu hình override theo site | Site `HL-HN` (đã có override `location_face`, 3 checks/shift) hoặc site mới | `POST .../random-check-configs/sites/{siteId}` | 201 |
| 93 | Cấu hình số lần và khung giờ check | Config tenant-default `acme-corp` | `PUT .../random-check-configs/{id}` | 200 |
| 94 | Cấu hình mode kiểm tra | 3 tenant chính đã đặt 3 mode khác nhau: acme-corp=`location_only`, beta-industries=`location_face`, gamma-logistics=`location_face_liveness` | `PUT .../check-mode` đổi tenant bất kỳ | 200; xem scheduled_checks MỚI sinh ra dùng đúng mode mới (mode cũ vẫn giữ ở check đã snapshot) |
| 95 | Cấu hình áp dụng theo vai trò | Config bất kỳ | `PUT .../applicable-roles` | 200 |
| 96 | Tự động sinh scheduled checks đầu ca | — | Job `RandomCheckSchedulerJob` chạy 00:01 hàng ngày — **muốn test ngay**: trigger tay `POST .../scheduled-checks/generate?date=...` | Sinh check theo config |
| 97 | Snapshot config khi sinh check | 1 scheduled_check bất kỳ | `GET .../scheduled-checks/{id}` | Field `configSnapshot`/hiển thị mode đúng tại THỜI ĐIỂM sinh, không đổi dù config sau này bị sửa |
| 98 | Tạo job Redis gửi check (tương đương BullMQ) | — | Xem Redis: `docker exec fams-redis redis-cli -a redispassword123 ZRANGE fams:randomcheck:dispatch 0 -1 WITHSCORES` | Thấy các check `pending` đang chờ dispatch |
| 98b | Test dispatch + /my-pending time-bound sống | **6 scheduled_checks `pending` có sẵn** (3 tenant chính, mỗi tenant 2 dòng: 1 trong 60s kể từ lúc seed — ĐÃ QUA HẠN nếu bạn đọc guide này sau đó, 1 cách 5 tiếng) — **để test SỐNG, tốt nhất tự trigger mới**: `POST .../scheduled-checks/manual` cho 1 nhân viên đang có assignment active | Login nhân viên đó → `GET .../scheduled-checks/my-pending` | Check vừa tạo (trong 60s) → hiện; check xa hơn → ẩn (đúng hành vi đã sửa) |
| 99 | Hủy scheduled check | 1 check `pending`/`sent` | `POST .../scheduled-checks/{id}/cancel` | 200; bị xóa khỏi Redis queue |

---

## Phần B — Kịch bản test theo luồng nghiệp vụ

### B.1 Luồng nhân viên mới hoàn chỉnh (mời → chấp nhận → phân công → Face ID → chấm công)

1. HR (`dung.pham.hr@gmail.com`, HR_MANAGER @ acme-corp) mời nhân viên mới → `POST /tenants/{acmeId}/invitations`.
2. Ứng viên nhận link, chấp nhận → `POST /invitations/accept` → tự động có tài khoản `users` + `employees` liên kết, nhận JWT luôn (không cần login lại).
3. HR phân công nhân viên vào site `HL-HN`, ca "Ca sáng" → `POST .../assignments`.
4. Nhân viên tự đăng nhập → xem site được phép check-in → `GET .../checkin/available-sites` → phải thấy `HL-HN`.
5. Nhân viên đồng ý Face ID → `POST .../face-id/consent` → đăng ký → `POST .../face-id/enroll` (cần ảnh thật, xem A.5 #49) → vào hàng chờ.
6. HR duyệt → `GET .../face-id/pending-review` → `POST .../face-id/{id}/approve`.
7. Nhân viên check-in tại `HL-HN` (tọa độ ~21.0285,105.8542) trong khung giờ ca sáng (07:00-15:00, GMT+7) → `POST /checkin`.
8. Cuối ca, check-out → `POST /checkin/{id}/checkout`.
9. Xác nhận `attendance_summaries` tự sinh cho ngày đó → `GET /attendance/me`.

### B.2 Luồng đa công ty — 1 người, 2 vai trò khác nhau ở 2 tenant

Dùng sẵn **`dung.pham.hr@gmail.com`**:
1. `POST /auth/login` → chọn "vào" acme-corp (HR_MANAGER) — kiểm tra `GET /auth/me` hoặc field `roles` trả về, thấy quyền HR đầy đủ (mời nhân viên, xem toàn bộ chấm công acme-corp).
2. Cùng session/token đó, gọi API thuộc `beta-industries` (site-scoped SITE_SUPERVISOR, giới hạn tại site "Nhà máy Bình Minh") — thử `GET /tenants/{betaId}/sites` → chỉ thấy site được gán site-scope, KHÔNG thấy "Kho A".
3. Thử gọi API quản trị cấp cao ở beta-industries (vd tạo role) → phải bị từ chối (403) vì ở đây chỉ là SITE_SUPERVISOR, không phải HR_MANAGER.
4. Đối chiếu tương tự với **`truong.van.dat@gmail.com`** — EMPLOYEE ở cả `gamma-logistics` lẫn `tia-sang-startup`, quyền thấp hơn (chỉ tự chấm công/xem của mình ở cả 2 nơi).

→ Kết quả mong đợi: **cùng 1 JWT/tài khoản nhưng quyền hạn khác nhau hoàn toàn theo từng tenant** — đúng bản chất multi-tenant RBAC.

### B.3 Luồng công ty hoàn toàn mới (từ tenant rỗng `rong-vang-holdings`)

1. Login `owner.rongvang@gmail.com`.
2. Xác nhận mọi danh sách đều rỗng (site/workspace/employee/config) — test empty-state.
3. Tạo cấu hình random-check mặc định → `POST .../random-check-configs/tenant-default`.
4. Tạo workspace đầu tiên → site đầu tiên → geofence → ca làm việc.
5. Tạo nhân viên thủ công đầu tiên, phân công vào site/ca vừa tạo.
6. Mời thêm 1 nhân viên qua email, để pending (không accept ngay) — minh họa "công ty mới onboard".

→ Đây là kịch bản dựng 1 tenant hoàn toàn từ số 0, khác với các tenant demo đã có sẵn đầy đủ — dùng để test đúng trải nghiệm "công ty mới đăng ký" mà FE/Web thường bỏ sót khi luôn test trên dữ liệu có sẵn.

### B.4 Luồng nâng cấp khi trial hết hạn

1. Login `owner.namviet@gmail.com` (`nam-viet-services`, trial đã hết hạn 5 ngày).
2. Thử vài hành động (tạo nhân viên, tạo site) → ghi nhận hành vi thực tế (bị chặn cứng hay chỉ cảnh báo — đây là điểm cần đối chiếu lại với đội sản phẩm).
3. Platform Admin nâng cấp gói cho tenant này → `PATCH /tenants/{id}/subscription` sang `basic`/`pro`.
4. Lặp lại bước 2 → phải hoạt động bình thường.

### B.5 Luồng Random Check đầy đủ (cấu hình → sinh lịch → dispatch → phản hồi → vi phạm)

1. HR xem cấu hình đang áp dụng cho site `HL-HN` → `GET .../random-check-configs/sites/HL-HN/effective` → thấy `resolvedFrom=site_override`, mode `location_face`.
2. Trigger sinh check thủ công cho 1 nhân viên đang có assignment active tại `HL-HN` → `POST .../scheduled-checks/manual`.
3. Theo dõi Redis dispatch queue (`ZRANGE fams:randomcheck:dispatch 0 -1`) — job `RandomCheckDispatchJob` poll mỗi 60s sẽ tự gửi khi đến giờ.
4. Sau khi dispatch (status → `sent`), nhân viên xem `GET .../scheduled-checks/my-pending` → thấy check này.
5. Nhân viên phản hồi trong cửa sổ thời gian → `POST .../scheduled-checks/{id}/respond`.
6. Nếu KHÔNG phản hồi kịp → job hết hạn tự chuyển `no_response` → tự sinh `violations` (đối chiếu 79 case `no_response` có sẵn trong seed để biết hình dạng dữ liệu mong đợi).
7. HR xử lý vi phạm → `PATCH .../violations/{id}` xác nhận/bác bỏ (đối chiếu ~40% case đã `resolved` sẵn trong seed).

### B.6 Luồng RBAC đầy đủ (tạo role → gán quyền → user thấy đúng quyền → thu hồi)

1. Company Admin (`chu.hoanglong@gmail.com`) tạo role custom mới, ví dụ "Giám sát ca đêm 2" với quyền `['checkins','attendance']`.
2. Gán role này cho 1 nhân viên bất kỳ chưa có role đặc biệt (vd `HL-011`) → `POST /user-roles`.
3. Đăng nhập bằng chính nhân viên đó → gọi thử 1 API thuộc `checkins`/`attendance` → phải thành công; gọi thử API thuộc `employees` (không nằm trong quyền vừa gán) → phải bị 403.
4. Thu hồi role → `DELETE /user-roles/{id}` → gọi lại API `checkins` → giờ phải bị 403 (mất quyền ngay).

### B.7 Luồng bảo mật tài khoản

1. **Khóa tài khoản**: tạo 1 tài khoản test mới → đăng nhập sai mật khẩu 5 lần liên tiếp → lần 6 dù đúng mật khẩu vẫn bị khóa 60 phút (đối chiếu hành vi với `taikhoanbikhoa@gmail.com` đã bị khóa sẵn).
2. **2FA**: dùng `bat2fa1@gmail.com` — login thường (chỉ ra `pendingToken`, không đăng nhập được thẳng) → xác nhận bằng mã TOTP mới nhất từ secret → login thành công. Thử dùng mã CŨ (hết hạn 30s) → phải bị từ chối.
3. **Logout toàn bộ thiết bị**: login `dung.pham.hr@gmail.com` từ "2 thiết bị" (2 lần login riêng) → gọi `/auth/logout/all` bằng 1 trong 2 → xác nhận CẢ HAI token cũ đều không dùng lại được (không chỉ token gọi logout).
4. **Refresh token cũ bị thu hồi**: đối chiếu sẵn với `hotro1.nentang@fams.com` — refresh token đầu tiên của user này đã bị revoke chủ động trong seed, dùng để test "cố refresh bằng token cũ" → phải bị từ chối.

---

## Ghi chú vận hành khi test

- Sau mỗi lần `bash scripts/seed.sh`, secret TOTP của `bat2fa1@gmail.com`/`bat2fa2@gmail.com` **SINH MỚI** — luôn lấy secret mới nhất từ console output (dòng `TOTP 2FA đã bật thật`), đừng dùng secret cũ đã ghi trong tài liệu này.
- 2 dòng `scheduled_checks` "pending near-future" demo cho `/my-pending` chỉ còn hiệu lực ~30s-5h SAU thời điểm seed — nếu bạn test sau đó, hãy tự trigger 1 check thủ công thay vì dựa vào dữ liệu demo đã quá hạn.
- **KHÔNG chạy `tests/*/*.sh` (bộ test tự động) sau khi đã seed xong nếu muốn giữ danh sách tenant sạch 18 công ty** — các script test tự tạo tenant riêng (không set `ownerEmail`) nên sẽ để lại tenant "mồ côi" do platform admin làm chủ, làm bẩn dữ liệu mẫu. Nếu cần chạy regression, hãy **reseed lại sau đó**.
- Muốn xem lại secret/token của bất kỳ dữ liệu nào không nhớ: `docker exec fams-postgres psql -U fams_user -d fams_db -c "SELECT ... FROM ..."` — toàn bộ câu lệnh tra cứu mẫu đã liệt kê trong bảng ở Phần A.
