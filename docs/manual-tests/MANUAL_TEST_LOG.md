# Nhật ký kiểm thử thủ công (Manual QA Log)

Đây là **nguồn sự thật duy nhất** cho câu hỏi: "tính năng X đã được người dùng tự tay test qua
giao diện thật (Web Admin / Mobile App) chưa, kết quả thế nào?" — tách biệt với checkbox `[x]`
trong `docs/BACKLOG.md` (checkbox đó chỉ phản ánh trạng thái **code/backend đã audit xong**,
không đồng nghĩa đã có người test tay qua UI thật).

**Dùng file này khi nào:**
- Trước khi sửa code của bất kỳ tính năng nào đang ở trạng thái ✅ **ĐÃ KHÓA** bên dưới — đọc kỹ
  phạm vi đã test để không vô tình gây hồi quy. Nếu bắt buộc phải sửa, phải note lại + đề nghị
  test lại đúng các case liên quan sau khi sửa.
- Sau mỗi lần bạn (chủ dự án) test xong một tính năng — báo kết quả (pass toàn bộ / pass một
  phần kèm case nào chưa test hoặc fail) — tôi cập nhật bảng bên dưới và đóng mục tương ứng.

## Chú giải trạng thái

| Ký hiệu | Ý nghĩa |
|---|---|
| ✅ **PASS — ĐÃ KHÓA** | Toàn bộ case trong kịch bản test đã pass qua UI thật. Coi là xong, tránh sửa lại trừ khi có yêu cầu mới; nếu sửa, phải test lại. |
| 🟡 **PASS MỘT PHẦN** | Một số case đã pass, còn case khác chưa test hoặc đang bị chặn (VD: thiếu môi trường/thiết bị). Chưa khóa — vẫn có thể còn thay đổi. |
| 🔴 **FAIL — CÓ BUG** | Test ra lỗi thật, đang chờ sửa. |
| ⬜ **CHƯA TEST** | Chưa ai test qua UI thật. |

---

## Bảng tổng hợp

| # | Tính năng | Web Admin | Mobile App | Trạng thái chung | Ngày | Ghi chú tồn đọng |
|---|---|---|---|---|---|---|
| 1 | Đăng nhập email/mật khẩu | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 2 | Đăng nhập bằng SĐT/OTP | ✅ Pass (Phần A) | ⬜ Chưa test | 🟡 **PASS MỘT PHẦN** | 2026-08-13 | App: chưa test luồng OTP thật (Phần B — cần cài bản EAS dev-client lên máy thật) |
| 3 | Đăng nhập Google | ⬜ Chưa test | ⬜ Chưa test | ⬜ **CHƯA TEST** | — | Toàn bộ 9 case trong kịch bản chưa chạy; đặc biệt case 4 (bỏ qua 2FA) và case 5 (không có invite-only gate) là phát hiện nghiệp vụ cần xác nhận khi test |
| 4 | Đăng xuất khỏi thiết bị hiện tại | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 5 | Đăng xuất khỏi tất cả thiết bị | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu audit log cho `LOGOUT_ALL` vẫn còn (không chặn tính năng, xem ghi chú kỹ thuật) |
| 6 | Đăng ký tài khoản người dùng | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 7 | Quên mật khẩu | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 8 | Đặt lại mật khẩu | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu audit log cho `RESET_PASSWORD` vẫn còn (không chặn tính năng, xem ghi chú kỹ thuật) |
| 9 | Đổi mật khẩu | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu audit log `CHANGE_PASSWORD` vẫn còn (không chặn khóa) |
| 10 | Xem thông tin cá nhân | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu field 2FA/tenant hiện tại ở `/auth/me` vẫn còn (không chặn khóa) |
| 11 | Cập nhật hồ sơ cá nhân | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu audit log `UPDATE_PROFILE` vẫn còn (không chặn khóa) |
| 12 | Bật TOTP 2FA | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 13 | Đăng nhập có 2FA | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 14 | Khóa tài khoản khi đăng nhập sai | ✅ Pass | — (Backend only) | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Audit `LOGIN_FAILED`/`ACCOUNT_LOCKED` đã bổ sung và test pass (chỉ ghi khi tài khoản thuộc tenant, theo quyết định nghiệp vụ) |
| 15 | Tạo tenant mới | ✅ Pass | — (không có Mobile App) | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 16 | Xem danh sách tenant | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu plan/subscription trong danh sách vẫn còn (không chặn khóa) |
| 17 | Cập nhật thông tin tenant | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 18 | Cấu hình giao diện và định dạng | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Audit `tenant_settings_updated` đã bổ sung và test pass. Gap "language/currency thuộc API #17" vẫn còn nhưng không chặn khóa (đúng kiến trúc hiện tại) |

**Cập nhật kèm theo lượt test này (2026-08-13):** đã xác nhận lại (retest) phần audit log mới bổ
sung cho #5, #8, #9, #11 (trước đó đã ĐÃ KHÓA, tạm hạ để chờ retest sau khi sửa backend) — pass,
giữ nguyên ✅ ĐÃ KHÓA. Đồng thời xác nhận UI "tìm người thao tác theo tên" (thay ô nhập UUID thô)
ở màn Nhật ký audit hoạt động đúng trên cả 2 chế độ (Platform/Company).

| 19 | Quản lý IP whitelist | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Đã đổi scope client-type → scope theo ROLE (backend + UI). Enforcement + UI xác nhận đúng qua test tay |
| 20 | Quản lý gói dịch vụ | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có gap |
| 21 | Cấu hình giới hạn gói | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Case enforcement (3-5) đã test hoặc xác nhận hoãn hợp lệ theo Sprint liên quan |
| 22 | Gán subscription cho tenant | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có gap |
| 23 | Seed role và permission hệ thống | ✅ Pass (kiểm qua DB/API) | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Idempotent xác nhận qua restart container |

---

## Chi tiết

### #1 — Đăng nhập email/mật khẩu — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-01-login.md` (mục A→D, case 1-13).
- Kết quả: toàn bộ case đã chạy và pass trên cả Web Admin lẫn Mobile App.
- **Khóa từ 2026-08-13** — không sửa lại luồng login/register/forgot-password/2FA liên quan trừ
  khi có yêu cầu tính năng mới; nếu bắt buộc chạm vào, phải test lại toàn bộ case ở file trên.

### #2 — Đăng nhập bằng SĐT/OTP — 🟡 PASS MỘT PHẦN (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-02-phone-otp-login.md`.
- **Đã pass:** Phần A (case 1-5) — backend trả 503/429 đúng khi test rate-limit, Web Admin hiện
  đúng thông báo, không crash.
- **Còn tồn đọng — CHƯA TEST:** Phần B (case 6-10) trên **Mobile App** — luồng OTP thật qua
  Firebase (dù đã có Firebase project + config), do Mobile App bắt buộc phải cài bản build
  `eas build --profile development` lên thiết bị thật/simulator mới test được (không dùng được
  Expo Go/Expo Web cho tính năng này). **Chưa khóa** tính năng này — cần hoàn tất bước build EAS
  rồi test case 6-10 trước khi đóng.
- Việc cần làm tiếp: chạy `eas build --profile development --platform android` (hoặc `ios`), cài
  lên máy, rồi test case 6 (happy path), 7 (sai OTP), 8 (đăng ký mới bằng SĐT), 9 (OTP + 2FA), 10
  (rate limit thực tế).

### #3 — Đăng nhập Google — ⬜ CHƯA TEST (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-03-google-login.md`.
- Chưa có case nào được chạy qua UI thật (cả Web Admin lẫn Mobile App).
- **Ưu tiên khi test:** case 4 (Google login có bỏ qua TOTP/2FA không — hiện code chủ đích bỏ
  qua) và case 5 (email Google hoàn toàn mới có bị chặn "chưa được mời" không — hiện code
  **không** chặn, tự tạo tài khoản mới luôn) — 2 case này xác nhận lại 2 phát hiện lệch so với
  Acceptance Criteria gốc, cần bạn quyết định có phải sửa không.

---

### #4 — Đăng xuất khỏi thiết bị hiện tại — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-04-logout.md`. Toàn bộ case pass trên cả 2 nền
  tảng. Khóa từ 2026-08-13.

### #5 — Đăng xuất khỏi tất cả thiết bị — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-05-logout-all.md`. Toàn bộ case pass trên cả 2
  nền tảng, bao gồm case đa thiết bị. **Gap còn tồn tại** (không chặn khóa tính năng, chỉ là thiếu
  sót về audit trail): `logoutAll()` không ghi audit log `LOGOUT_ALL` — nếu sau này cần bổ sung,
  hạ trạng thái xuống 🟡 trước khi sửa.

### #6 — Đăng ký tài khoản người dùng — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-06-register.md`. Cả luồng email và luồng SĐT (OTP
  nội bộ dev-mode) đều pass trên 2 nền tảng.

### #7 — Quên mật khẩu — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-07-forgot-password.md`. Toàn bộ case bảo mật/biên
  pass trên 2 nền tảng.

### #8 — Đặt lại mật khẩu — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-08-reset-password.md`. Toàn bộ case pass trên 2
  nền tảng. **Gap còn tồn tại** (không chặn khóa tính năng): `resetPassword()` không ghi audit log
  `RESET_PASSWORD` — nếu sau này cần bổ sung, hạ trạng thái xuống 🟡 trước khi sửa.

### #9 — Đổi mật khẩu — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-09-change-password.md`. Toàn bộ case pass, bao
  gồm case 4/5 (tự đăng xuất mọi phiên sau khi đổi — đúng chủ đích). Gap thiếu audit log
  `CHANGE_PASSWORD` vẫn còn, không chặn khóa.

### #10 — Xem thông tin cá nhân — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-10-view-profile.md`. Toàn bộ case pass. Gap thiếu
  field 2FA/tenant hiện tại ở `/auth/me` vẫn còn, không chặn khóa (UI tự bù đắp qua nguồn khác).

### #11 — Cập nhật hồ sơ cá nhân — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-11-update-profile.md`. Toàn bộ case pass, gồm cả
  luồng đổi phone/email cần xác thực lại. Gap thiếu audit log `UPDATE_PROFILE` vẫn còn, không
  chặn khóa.

### #12 — Bật TOTP 2FA — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-12-enable-totp.md`. Toàn bộ case pass, bao gồm
  contract mới (QR client-side, chặn bật trùng 409, invalidate secret cũ, hết hạn phiên setup).

### #13 — Đăng nhập có 2FA — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-13-login-totp.md`. Toàn bộ case pass, gồm hết 8 mã
  dự phòng và không bị bỏ qua 2FA ở lần đăng nhập kế tiếp.

---

### #14 — Khóa tài khoản khi đăng nhập sai — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-14-account-lockout.md`. Phần UI chính đã pass qua
  kịch bản #1 case 4. Audit `LOGIN_FAILED`/`ACCOUNT_LOCKED` đã bổ sung (chỉ ghi khi tài khoản
  thuộc tenant) và test pass.

### #15 — Tạo tenant mới — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-15-create-tenant.md`. Cả luồng self-serve và luồng
  Platform Admin provisioning đều pass.

### #16 — Xem danh sách tenant — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-16-list-tenants.md`. Toàn bộ case pass. Gap thiếu
  plan/subscription trong danh sách vẫn còn, không chặn khóa.

### #17 — Cập nhật thông tin tenant — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-17-update-tenant.md`. Toàn bộ case pass, gồm giới
  hạn quyền (chỉ owner sửa được).

### #18 — Cấu hình giao diện và định dạng — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-18-tenant-display-settings.md`. Toàn bộ case pass.
  Audit `tenant_settings_updated` đã bổ sung và test pass. Gap "language/currency thuộc API #17"
  vẫn còn (đúng kiến trúc hiện tại, không chặn khóa).

---

### #19 — Quản lý IP whitelist — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-19-ip-whitelist.md`. Lịch sử: bản đầu báo nhầm
  "chưa enforce" (sai) → bản 2 tìm đúng gap thật (scope theo client-type web_admin/api không thể
  thực thi, chọn "chỉ Web Admin" vẫn chặn cả app di động) → **đã sửa và test pass (2026-08-13)**:
  đổi hẳn sang scope theo ROLE. Backend: migration V90 (`tenant_ip_whitelist_roles`),
  `IpWhitelistGuard` đọc role từ JWT, chỉ entry có role khớp mới áp dụng. Frontend: form Web Admin
  đổi dropdown scope cũ thành multi-select chọn role (tái dùng danh sách role thật của tenant qua
  `useRolesQuery`), cột bảng hiện tag role hoặc "Tất cả role". Đã xác nhận qua cả API lẫn UI thật:
  role bị giới hạn thì bị chặn đúng khi sai IP; role không nằm trong entry thì không bị ảnh hưởng
  dù sai IP; tự khóa mình bị chặn khi sửa; Platform Admin luôn miễn trừ.

### #20 — Quản lý gói dịch vụ — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-20-manage-plans.md`. Toàn bộ case pass, không có
  gap.

### #21 — Cấu hình giới hạn gói — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-21-plan-limits.md`. Case cấu hình (1-2) pass; case
  enforcement (3-5) đã test hoặc xác nhận hoãn hợp lệ theo đúng Sprint liên quan (không tính là
  fail).

### #22 — Gán subscription cho tenant — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-22-assign-subscription.md`. Toàn bộ case pass,
  không có gap.

### #23 — Seed role và permission hệ thống — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-23-seed-roles-permissions.md`. Backend-only, xác
  nhận idempotent qua query DB + restart container, không đổi số lượng role/permission.

## Quy ước cập nhật file này (cho các phiên làm việc sau)

1. Mỗi khi user báo "test xong tính năng #N" kèm kết quả (pass toàn bộ / pass một phần / fail),
   cập nhật đúng dòng trong bảng tổng hợp + viết chi tiết vào mục "Chi tiết" tương ứng.
2. Chỉ đánh dấu ✅ **PASS — ĐÃ KHÓA** khi **toàn bộ** case trong kịch bản test (`sprint-*-feature-*.md`
   tương ứng) đã được xác nhận pass qua UI thật trên **tất cả** nền tảng liên quan (Web/App theo
   đúng cột "Nền tảng" ghi trong `docs/BACKLOG.md`).
3. Nếu chỉ pass một phần hoặc bị chặn bởi môi trường (thiếu thiết bị, thiếu Firebase, v.v.), dùng
   🟡 và ghi rõ case nào còn thiếu — không tự ý khóa sớm.
4. Khi 1 tính năng đã ✅ **ĐÃ KHÓA** mà sau này cần sửa (bug mới phát sinh, thay đổi nghiệp vụ),
   phải: (a) ghi rõ lý do sửa vào mục Chi tiết, (b) hạ trạng thái xuống 🟡 hoặc 🔴 cho tới khi
   test lại xong.
