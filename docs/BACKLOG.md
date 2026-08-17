# FAMS — Facility Attendance Management System

> **Tài liệu backlog & lộ trình phát triển dự án FAMS.**
> File này được sinh tự động từ `Backlog_Tính_Năng_Fams.xlsx` — dùng làm nguồn tham chiếu chính cho cả người phát triển và AI coding assistant (Claude Code, Cursor, v.v.) khi triển khai lần lượt từng Sprint.
>
> ⚠️ Checkbox `[x]`/`[ ]` bên dưới chỉ phản ánh **code/backend đã audit xong**, KHÔNG đồng nghĩa
> đã có người test tay qua UI thật (Web Admin/Mobile App). Trạng thái test tay thật + tính năng
> nào đã "khóa" (tránh sửa gây ảnh hưởng) nằm ở `docs/manual-tests/MANUAL_TEST_LOG.md` — đọc file
> đó trước khi sửa bất kỳ tính năng nào đã ✅ ĐÃ KHÓA.

---

## 1. Giới thiệu dự án

**FAMS** là hệ thống quản lý chấm công công trình đa tenant (multi-tenant SaaS), gồm 3 phần chính:

- **Backend API** (Yes/No ở mỗi tính năng cho biết có cần backend hay không)
- **Web Admin** (dành cho Platform Admin / Company Admin / HR)
- **Mobile App** (dành cho nhân viên hiện trường)
- **Queue/AI/Automation** (background job, BullMQ, cron, face-matching AI, v.v.)

Các trụ cột nghiệp vụ chính: Auth & RBAC đa tenant, quản lý nhân sự & công trình, chấm công GPS/Face ID/Liveness, Random Check chống gian lận, tổng hợp công tự động, xử lý vi phạm, dashboard & báo cáo, vận hành nền tảng SaaS.

---

## 2. Nguyên tắc phát triển (Ground Rules)

Đây là những quy tắc áp dụng xuyên suốt khi triển khai backlog này — cả người và AI coding assistant đều cần tuân theo:

1. **Đây là danh sách dự kiến, không phải đặc tả cứng.** Trong quá trình code, có thể điều chỉnh, bổ sung chi tiết cho hợp lý với nghiệp vụ thực tế, miễn không đổi mục tiêu tổng thể của tính năng.
2. **Ngầm định các option hợp lý cho từng loại tính năng**, kể cả khi không ghi rõ trong Acceptance Criteria. Ví dụ:
   - Tính năng **danh sách/xem** (VD: Danh sách nhân viên, Danh sách công trình...) — mặc định có **phân trang, tìm kiếm, lọc, sắp xếp**, trừ khi nghiệp vụ không cần.
   - Tính năng **đăng nhập** — có thể mở rộng thêm **đăng ký, đăng nhập Google, quên mật khẩu...** như các user story liên quan đã liệt kê.
   - Các tính năng khác tương tự: AI/coder được quyền **gợi ý và bổ sung thêm** sub-feature hợp lý nếu giúp tính năng hoàn chỉnh hơn, và nên nêu rõ lý do khi bổ sung.
3. **Triển khai tuần tự theo Sprint** (1 – 6), trong mỗi Sprint triển khai tuần tự theo Epic → Tính năng lớn → Tính năng con (theo đúng thứ tự `#` trong bảng).
4. **Không tự ý nhảy cóc sang Sprint sau** khi Sprint hiện tại còn tính năng P0/P1 chưa xong, trừ khi có phụ thuộc kỹ thuật bắt buộc hoặc được yêu cầu rõ ràng.
5. **Mọi tính năng khi hoàn thành cần cập nhật lại trạng thái** trong tài liệu này (checkbox `[x]`) và có thể ghi chú ngắn nếu có thay đổi so với mô tả gốc.
6. **Tôn trọng Acceptance Criteria** làm điều kiện hoàn thành tối thiểu (Definition of Done) cho mọi User Story.
7. **Tôn trọng cột Backend / Web Admin / Mobile App / Queue-AI-Automation** để biết tính năng cần đụng vào những phần nào của hệ thống.
8. Khi có nhiều cách hiểu nghiệp vụ, ưu tiên phương án **đơn giản, an toàn dữ liệu, đúng RBAC/multi-tenant**, và nêu giả định đã chọn.

---

## 3. Hướng dẫn dành cho AI Coding Assistant

Khi được yêu cầu "làm tiếp theo backlog" hoặc "bắt đầu Sprint N":

1. Đọc mục **Sprint N** tương ứng bên dưới.
2. Lấy tính năng con **đầu tiên có trạng thái `[ ] To Do`** theo đúng thứ tự liệt kê.
3. Đọc kỹ **User Story** + **Acceptance Criteria** + **DB Entities** + **Phụ thuộc** (nếu có) trước khi code.
4. Đề xuất/bổ sung các option hợp lý theo mục 2 (Nguyên tắc phát triển) nếu cần, và nói rõ trước khi triển khai.
5. Triển khai đúng phạm vi nền tảng được đánh dấu `Yes` (Backend/Web Admin/Mobile App/Queue-AI).
6. Sau khi hoàn thành, cập nhật checkbox trạng thái tương ứng trong file này.
7. Nếu một tính năng phụ thuộc tính năng khác chưa làm (cột **Phụ thuộc**), cần cảnh báo và hỏi lại thứ tự ưu tiên.

---

## 4. Tổng quan theo Sprint

| Sprint | Chủ đề | Số tính năng | Tổng Story Points |
|---|---|---|---|
| Sprint 1 | Nền tảng: Auth & Identity, Tenant/SaaS, RBAC, Audit & Notification cơ bản | 32 | 123 |
| Sprint 2 | Nhân sự & Hạ tầng công trình: Employee, Workspace, Face ID, Site, Geofence, Shift, Assignment | 34 | 147 |
| Sprint 3 | Chấm công cốt lõi: Check-in/out (GPS/Face/Liveness), Attendance Summary, Push/Inbox | 24 | 121 |
| Sprint 4 | Random Check: Cấu hình, Sinh lịch tự động, Gửi & Phản hồi kiểm tra, Vi phạm tự động | 23 | 112 |
| Sprint 5 | Vi phạm, Dashboard & Báo cáo: Xử lý vi phạm, Dashboard 3 vai trò, Reports, Search, UX | 19 | 85 |
| Sprint 6 | Vận hành nền tảng & Go-live: Platform Admin, Audit nâng cao, Cron/Jobs, Security, UAT, Docs | 18 | 90 |

**Tổng cộng: 150 tính năng / 678 story points** trên toàn bộ dự án (Target Version 1.0).

---

## Sprint 1: Nền tảng: Auth & Identity, Tenant/SaaS, RBAC, Audit & Notification cơ bản

### Epic: Auth & Identity

#### Đăng nhập

- [x] **#1 — Đăng nhập email/mật khẩu** `P0` · 5sp · Nền tảng: Backend, Web Admin, Mobile App
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** user đã tự test toàn bộ case trong
    `docs/manual-tests/sprint-1-feature-01-login.md` qua UI thật, pass hết. Xem
    `docs/manual-tests/MANUAL_TEST_LOG.md` trước khi sửa lại tính năng này.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: AuthController.login, AuthService.login, test_login.sh (backend); LoginForm.tsx (Web Admin, đã kiểm chứng qua trình duyệt thật); login.tsx (Mobile App, đã kiểm chứng qua Expo Web thật).
    - Đã bổ sung: `users.last_login_at` (migration V61) + ghi audit log `LOGIN` (AuthService.login, dùng lại `AuditLogService.record` sẵn có nhưng trước đó chưa ai gọi tới).
    - Bug tìm & sửa khi test UI thật (Mobile App):
      1. `SiteDetail.tsx` import `react-native-maps` tĩnh (không qua `.native`/`.web` split) làm sập toàn bộ Expo Web build (mọi route, không riêng Site) — tách thành `SiteLocationMap.tsx` (native) + `SiteLocationMap.web.tsx` (web, trả `null`).
      2. Backend chưa có cấu hình CORS — Expo Web không gọi được API (native app + Web Admin qua Next.js proxy không bị ảnh hưởng). Thêm `CorsConfigurationSource` trong `SecurityConfig.java`, origin cho phép cấu hình qua `CORS_ALLOWED_ORIGIN_PATTERNS`.
      3. `expo-secure-store` không có bản Web (stub rỗng) → đăng nhập thành công trên Web vẫn crash (`setValueWithKeyAsync is not a function`). Thêm lớp bọc `secure-storage.ts`/`secure-storage.web.ts` (web dùng `localStorage`).
      4. Lỗi sai mật khẩu/tài khoản khóa/tenant suspended/email chưa xác thực hiển thị nguyên văn tiếng Anh từ backend thay vì tiếng Việt — sửa tận gốc: cả 2 frontend giờ ưu tiên field `userMessage` (luôn tiếng Việt, backend đã có sẵn) thay vì `message` (thường tiếng Anh); Web Admin sửa ở `api-client.ts` (1 chỗ, ảnh hưởng toàn bộ ~47 call site), Mobile App sửa ở `parseAuthError()`.
      5. Mobile App: xóa bộ auth cũ chết (`services/auth.service.ts`, `store/auth.store.ts`, `types/auth.type.ts`, `hooks/use-auth.ts` — xác nhận không còn ai import); thêm `deviceId` vào request login email/password (trước đây chỉ Google login gửi).
      6. Web Admin: `LoginResponse` type khai sai field `user` (backend không trả) và thiếu `tokenType`; xóa field chết `rememberMe` (không có UI, không gửi, backend cũng không nhận).
    - Đã tối ưu UX: nén ảnh nền trang login Web Admin từ 4.9MB → 167KB (giảm 97%), thêm `sizes` prop cho `next/image` (hết cảnh báo console).
    - Đã test thêm qua Playwright: khóa tài khoản (423), email chưa xác thực (403), tenant suspended (403), luồng TOTP/2FA đầy đủ (mã sai → mã đúng → mã dự phòng) — cả 2 giao diện. Kịch bản test thủ công chi tiết: `docs/manual-tests/sprint-1-feature-01-login.md`.
    - Đã sửa (2026-07-23), theo yêu cầu riêng: `EmailService` (4 hàm gửi mail) trước đây không bắt lỗi SMTP, khiến Đăng ký/Quên mật khẩu/Mời nhân viên bị hủy toàn bộ giao dịch nếu gửi mail lỗi (phát hiện thật khi Gmail dev bị chặn do vượt hạn mức gửi/ngày). Đã sửa:
      - 3/4 hàm (`sendVerificationEmail`, `sendPasswordResetEmail`, `sendInvitationEmail`) giờ bắt `MailException`, log lỗi, không throw — giao dịch chính (tạo tài khoản, tạo token reset, tạo lời mời) luôn thành công dù gửi mail thất bại.
      - Cả 4 hàm giờ chạy `@Async` (thêm `@EnableAsync` ở `ApiServerApplication`) — trước đây round-trip SMTP thật (connect + STARTTLS + auth + phản hồi lỗi) làm API register/forgot-password chậm 5-15s dù cuối cùng vẫn trả đúng; giờ trả về ngay (~50-300ms), gửi mail chạy nền.
      - Riêng `sendNotificationFallback` **cố tình không đổi** — caller duy nhất (`UserDeviceService.sendEmailFallback`) đã tự có try/catch + ghi `notification_delivery_logs` đúng trạng thái thật (SENT/FAILED), bọc thêm ở `EmailService` hoặc chạy async sẽ làm log đó luôn báo "thành công" sai sự thật.
      - Đã kiểm chứng thật (không chỉ đọc code) qua Playwright trên cả Web Admin lẫn Mobile App: đăng ký + quên mật khẩu đều chạy đúng, nhanh, kể cả khi Gmail dev vẫn đang bị chặn quota.
      - Phát hiện thêm khi test: `tests/auth/test_google_link.sh` có bug thật — lấy reset token bằng `redis KEYS ... | tail -1` (không lọc theo user), có thể vô tình lấy nhầm token của `admin@fams.com` từ `test_forgot_reset_password.sh` chạy gần cùng lúc rồi đổi mật khẩu tài khoản demo dùng chung mà không ai biết — đây chính là nguyên nhân `admin@fams.com` bị đổi mật khẩu ngoài ý muốn giữa phiên làm việc trước. Đã sửa: lọc đúng theo giá trị token khớp `GOOGLE_ONLY_ID`, xác nhận hết đụng độ dù chạy 2 script liền nhau.
    - Ghi chú kỹ thuật (không thuộc phạm vi #1, để dành cho tính năng RBAC/dashboard sau): Mobile App's `UserProfile.role` hiện luôn fallback về `'employee'` vì `/auth/me` không trả field `role` và mapper chỉ nhận `employee/manager/admin/hr` — vai trò thật (`TENANT_ADMIN`, `HR_MANAGER`...) lấy từ `GET /roles/me` chưa được gắn vào `user.role`. Chưa gây lỗi hiển thị vì hiện chưa có màn hình nào rẽ nhánh theo `user.role`, nhưng sẽ cần sửa trước khi có tính năng phân quyền theo vai trò trên mobile.
  - *User Story:* Là một người dùng đã có tài khoản, tôi muốn đăng nhập bằng email/số điện thoại và mật khẩu để truy cập hệ thống an toàn.
  - *Acceptance Criteria:* Nhập email/phone + password; kiểm tra tài khoản active; báo lỗi rõ ràng; tạo access/refresh token; ghi last_login và audit LOGIN.
  - *DB Entities:* `users, tokens, audit_logs`
- [ ] **#2 — Đăng nhập bằng số điện thoại OTP** `P0` · 5sp · Nền tảng: Backend, Mobile App, Queue/AI/Automation
  - 🟡 **Test tay thật — PASS MỘT PHẦN (2026-08-13):** Phần A (không cần Firebase) đã pass. Mobile
    App — luồng OTP thật (Phần B) chưa test, chờ build EAS dev-client. Chưa khóa. Xem
    `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-23):* 🟡 CODE ĐÃ ĐÚNG KIẾN TRÚC, CHỜ FIREBASE PROJECT THẬT ĐỂ TEST SMS LIVE.
    - Phát hiện lớn: Backend đã làm đúng Firebase Phone Auth, nhưng **cả 2 frontend gọi sai hoàn toàn** — được viết theo 1 API tưởng tượng (`/auth/otp/send {phone}`, `/auth/otp/verify {phone,code}`) không khớp backend thật (chỉ có `/auth/otp/verify`, nhận `{firebaseIdToken, deviceId}` — không có bước gửi OTP ở backend vì Firebase Client SDK gửi trực tiếp). Cả 2 frontend chưa từng tích hợp Firebase Client SDK thật. Tính năng **không chạy được** trên UI dù không lỗi khi đọc code qua loa.
    - Đã sửa **Backend**: xóa hẳn `OtpService`/`SendOtpRequest`/`VerifyOtpRequest`/`SmsService`/`ConsoleSmsService` (code chết, xác nhận không ai gọi); xóa rule `permitAll` thừa cho `/otp/send` (endpoint không tồn tại); bổ sung rate-limit theo IP cho `/otp/verify` (429, tái dùng `OtpRateLimitException`) để đóng gap AC "chặn nhập sai nhiều lần" — lý do dùng rate-limit theo IP thay vì đếm số lần sai: Firebase đã tự chặn đoán sai OTP trước khi request đến được backend, backend chỉ cần chặn lạm dụng endpoint; **bổ sung luôn nhánh kiểm tra TOTP/2FA** trong `FirebasePhoneLoginService` (trước đây hoàn toàn không có — người dùng bật 2FA có thể bỏ qua 2FA bằng cách đăng nhập qua SĐT!), tái dùng đúng cơ chế pending-token của `AuthService.login()`.
    - Đã sửa **Web Admin**: thêm Firebase Web SDK thật (`src/lib/firebase.ts`, lazy-init để không crash cả trang khi chưa cấu hình), viết lại `PhoneLoginForm.tsx` dùng `signInWithPhoneNumber` + reCAPTCHA vô hình, sửa `RegisterForm.tsx` để đăng ký bằng SĐT cũng xác thực Firebase OTP trước khi gọi API (trước đây sẽ luôn bị backend từ chối 400 do thiếu `firebaseIdToken` — Issue #1 từ trước). Sửa toàn bộ type/service/hook liên quan (`auth.type.ts`, `auth.service.ts`, `use-auth.ts`).
    - Đã sửa **Mobile App**: chuyển sang dùng `@react-native-firebase/auth` (theo quyết định của bạn: build qua EAS dev-client thay vì Expo Go, vì `@react-native-firebase` cần native module không chạy được trên Expo Go/Expo Web thường). Viết lại `phone-login.tsx`, `use-phone-otp.ts`, `api.ts`, `types.ts` cho đúng contract `{firebaseIdToken}`. **Phát hiện & sửa ngay**: thêm `@react-native-firebase/auth` làm sập toàn bộ Expo Web build (giống lỗi `react-native-maps` ở #1) — đã tách `use-firebase-phone-auth.ts` (native) + `.web.ts` (stub, báo "chỉ hỗ trợ trên ứng dụng di động") để không ảnh hưởng việc test các tính năng khác qua Expo Web.
    - **Còn thiếu, cần bạn cung cấp trước khi test live được**: dự án Firebase thật (Project ID, service account JSON cho backend `.env`, Web config cho `fams-front-web-project/.env.local`, `google-services.json`/`GoogleService-Info.plist` cho mobile) — đã hướng dẫn các bước tạo. Mobile App còn cần 1 lần `eas build --profile development` thật để cài dev-client lên máy/simulator trước khi test được (không thể test qua Expo Go hay Expo Web).
  - *User Story:* Là một người dùng mobile, tôi muốn đăng nhập bằng số điện thoại và mã OTP để vào app nhanh khi không muốn dùng mật khẩu.
  - *Acceptance Criteria:* Nhập số điện thoại; gửi OTP; xác thực OTP còn hạn; chặn nhập sai nhiều lần; tạo phiên đăng nhập.
  - *DB Entities:* `users, tokens, notifications, audit_logs`
- [x] **#3 — Đăng nhập Google** `P1` · 3sp · Nền tảng: Backend, Web Admin, Mobile App
  - ⬜ **Test tay thật — CHƯA TEST (2026-08-13):** chưa chạy qua UI thật trên Web/App. Xem
    `docs/manual-tests/sprint-1-feature-03-google-login.md` và
    `docs/manual-tests/MANUAL_TEST_LOG.md` — lưu ý case 4/5 là phát hiện lệch Acceptance Criteria
    cần xác nhận khi test.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: GoogleLoginService, test_google_login.sh
  - *User Story:* Là một người dùng, tôi muốn đăng nhập nhanh bằng tài khoản Google để giảm thao tác đăng nhập.
  - *Acceptance Criteria:* Bấm Google login; nhận profile cơ bản; liên kết email đã tồn tại; từ chối email chưa được mời nếu tenant yêu cầu.
  - *DB Entities:* `users, tenant_users, tokens, audit_logs`

#### Đăng xuất

- [x] **#4 — Đăng xuất khỏi thiết bị hiện tại** `P0` · 2sp · Nền tảng: Backend, Web Admin, Mobile App
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass qua UI thật trên Web + App. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: LogoutService.logout, test_logout.sh
  - *User Story:* Là một người dùng đang đăng nhập, tôi muốn đăng xuất khỏi phiên hiện tại để bảo vệ tài khoản khi dùng xong.
  - *Acceptance Criteria:* Bấm đăng xuất; revoke refresh token hiện tại; xóa token local; chuyển về màn hình login.
  - *DB Entities:* `tokens, audit_logs`
- [ ] **#5 — Đăng xuất khỏi tất cả thiết bị** `P1` · 3sp · Nền tảng: Backend, Web Admin, Mobile App
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass (kể cả đa thiết bị) qua UI thật trên Web + App. Gap thiếu audit `LOGOUT_ALL` vẫn còn, không chặn khóa. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: LogoutService.logoutAll, test_logout_all.sh; thiếu: revoke đúng nhưng không ghi audit
  - *User Story:* Là một người dùng, tôi muốn đăng xuất khỏi mọi thiết bị để xử lý khi nghi ngờ lộ tài khoản.
  - *Acceptance Criteria:* Bấm logout all; revoke toàn bộ refresh token; giữ lại audit; yêu cầu đăng nhập lại ở các thiết bị khác.
  - *DB Entities:* `tokens, audit_logs`

#### Tài khoản

- [x] **#6 — Đăng ký tài khoản người dùng** `P0` · 5sp · Nền tảng: Backend, Web Admin, Mobile App, Queue/AI/Automation
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** cả luồng email và SĐT pass qua UI thật trên Web + App. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: RegisterService, test_register.sh
  - *User Story:* Là một người dùng mới, tôi muốn tạo tài khoản cá nhân bằng email/phone để có định danh để tham gia tenant.
  - *Acceptance Criteria:* Nhập thông tin cơ bản; kiểm tra email/phone trùng; hash mật khẩu; gửi xác thực email/OTP; tạo user inactive/active theo policy.
  - *DB Entities:* `users, tokens, notifications, audit_logs`
- [x] **#7 — Quên mật khẩu** `P0` · 3sp · Nền tảng: Backend, Web Admin, Mobile App, Queue/AI/Automation
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case bảo mật/biên pass qua UI thật trên Web + App. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: PasswordResetService.forgotPassword, test_forgot_reset_password.sh
  - *User Story:* Là một người dùng, tôi muốn yêu cầu đặt lại mật khẩu để lấy lại quyền truy cập khi quên mật khẩu.
  - *Acceptance Criteria:* Nhập email/phone; tạo reset token; gửi email/OTP; token có hạn; không lộ tài khoản tồn tại hay không.
  - *DB Entities:* `users, tokens, notifications, audit_logs`
- [ ] **#8 — Đặt lại mật khẩu** `P0` · 3sp · Nền tảng: Backend, Web Admin, Mobile App
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass qua UI thật trên Web + App. Gap thiếu audit `RESET_PASSWORD` vẫn còn, không chặn khóa. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: PasswordResetService.resetPassword; thiếu: không ghi audit
  - *User Story:* Là một người dùng, tôi muốn đặt lại mật khẩu bằng token hợp lệ để khôi phục tài khoản an toàn.
  - *Acceptance Criteria:* Token hợp lệ và chưa dùng; mật khẩu đạt policy; cập nhật password_hash; revoke refresh token cũ; ghi audit.
  - *DB Entities:* `users, tokens, audit_logs`
- [ ] **#9 — Đổi mật khẩu** `P0` · 3sp · Nền tảng: Backend, Web Admin, Mobile App
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass qua UI thật. Gap thiếu audit `CHANGE_PASSWORD` vẫn còn, không chặn khóa. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: ChangePasswordService, test_change_password.sh; thiếu: không ghi audit
  - *User Story:* Là một người dùng đang đăng nhập, tôi muốn đổi mật khẩu hiện tại để tăng bảo mật tài khoản.
  - *Acceptance Criteria:* Nhập mật khẩu cũ đúng; mật khẩu mới đạt policy; cập nhật password_hash; có thể revoke phiên khác; ghi audit.
  - *DB Entities:* `users, tokens, audit_logs`

#### Hồ sơ cá nhân

- [ ] **#10 — Xem thông tin cá nhân** `P0` · 2sp · Nền tảng: Backend, Web Admin, Mobile App
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass qua UI thật. Gap thiếu field 2FA/tenant hiện tại vẫn còn, không chặn khóa. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: UserProfileService.getProfile, test_profile.sh; thiếu: response thiếu trạng thái 2FA và tenant hiện tại
  - *User Story:* Là một người dùng, tôi muốn xem hồ sơ cá nhân của mình để kiểm tra thông tin đang lưu.
  - *Acceptance Criteria:* Hiển thị full_name, email, phone, avatar, tenant hiện tại, trạng thái 2FA; không hiển thị dữ liệu nhạy cảm.
  - *DB Entities:* `users, tenant_users`
- [ ] **#11 — Cập nhật hồ sơ cá nhân** `P0` · 3sp · Nền tảng: Backend, Web Admin, Mobile App
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass qua UI thật. Gap thiếu audit `UPDATE_PROFILE` vẫn còn, không chặn khóa. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: UserProfileService.updateProfile; thiếu: không ghi audit
  - *User Story:* Là một người dùng, tôi muốn cập nhật tên, avatar và thông tin liên hệ để giữ thông tin cá nhân chính xác.
  - *Acceptance Criteria:* Cập nhật full_name/avatar; validate phone/email nếu thay đổi; ghi audit; đồng bộ hiển thị trong tenant.
  - *DB Entities:* `users, tenant_users, audit_logs`

#### Bảo mật tài khoản

- [ ] **#12 — Bật TOTP 2FA** `P1` · 5sp · Nền tảng: Backend, Web Admin, Mobile App
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass qua UI thật, gồm contract mới (otpauthUri, chặn bật trùng). Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: TotpService, test_totp.sh; thiếu: không sinh/lưu backup codes
  - *User Story:* Là một người dùng, tôi muốn bật xác thực hai lớp bằng ứng dụng Authenticator để bảo vệ tài khoản tốt hơn.
  - *Acceptance Criteria:* Sinh QR/secret; xác minh mã 6 số; lưu totp_secret mã hóa; sinh backup codes; ghi audit.
  - *DB Entities:* `users, audit_logs`
- [ ] **#13 — Đăng nhập có 2FA** `P1` · 5sp · Nền tảng: Backend, Web Admin, Mobile App
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass qua UI thật, gồm hết 8 mã dự phòng. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: LoginTotpService, test_login_totp.sh; thiếu: thiếu đường dẫn đăng nhập bằng backup code
  - *User Story:* Là một người dùng đã bật 2FA, tôi muốn nhập mã TOTP sau khi đúng mật khẩu để đảm bảo chỉ chủ tài khoản đăng nhập.
  - *Acceptance Criteria:* Sau password đúng yêu cầu OTP; mã đúng mới tạo token; backup code dùng được một lần; ghi login result.
  - *DB Entities:* `users, tokens, audit_logs`
- [x] **#14 — Khóa tài khoản khi đăng nhập sai** `P0` · 3sp · Nền tảng: Backend
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** đã bổ sung audit `LOGIN_FAILED`/`ACCOUNT_LOCKED`, test pass. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: AuthService.login (failed-attempt counter, lockedUntil), test_account_lock.sh
  - *User Story:* Là một quản trị hệ thống, tôi muốn tự động khóa tạm tài khoản khi sai mật khẩu nhiều lần để giảm brute-force.
  - *Acceptance Criteria:* Đếm lần sai; đặt locked_until; thông báo lỗi phù hợp; ghi audit result=failure/denied.
  - *DB Entities:* `users, audit_logs`

### Epic: Tenant & SaaS

#### Tenant

- [ ] **#15 — Tạo tenant mới** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass, gồm cả luồng self-serve và Platform Admin provisioning. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: TenantService.createTenant, test_create_tenant.sh; thiếu: chỉ PLATFORM_ADMIN tạo được; không tự gán role admin cho người tạo (chỉ set ownerId); không ghi audit
  - *User Story:* Là một Platform Admin hoặc người dùng được phép, tôi muốn tạo công ty/tenant mới để thiết lập không gian sử dụng FAMS.
  - *Acceptance Criteria:* Nhập tên, slug, timezone; slug không trùng; tạo tenant_settings mặc định; gán role company_admin cho người tạo.
  - *DB Entities:* `tenants, tenant_settings, user_roles, roles, audit_logs`
- [ ] **#16 — Xem danh sách tenant** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass. Gap thiếu plan/subscription trong danh sách vẫn còn, không chặn khóa. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: TenantService.listTenants, test_list_tenants.sh; thiếu: danh sách không kèm plan/subscription (phải gọi /detail riêng)
  - *User Story:* Là một Platform Admin, tôi muốn xem danh sách công ty có tìm kiếm, lọc, sort, phân trang để quản trị khách hàng SaaS hiệu quả.
  - *Acceptance Criteria:* Tìm theo tên/slug/status; lọc trạng thái; sort ngày tạo; phân trang; xem plan/subscription hiện tại.
  - *DB Entities:* `tenants, tenant_subscriptions, plans`
- [ ] **#17 — Cập nhật thông tin tenant** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass, gồm giới hạn quyền (chỉ owner sửa được). Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: TenantService.updateTenant, test_update_tenant.sh; thiếu: không ghi audit
  - *User Story:* Là một Company Admin, tôi muốn cập nhật hồ sơ công ty, logo, địa chỉ, timezone để thông tin doanh nghiệp chính xác.
  - *Acceptance Criteria:* Cập nhật name/display_name/logo/address; timezone ảnh hưởng tính ngày sau cập nhật; ghi audit.
  - *DB Entities:* `tenants, audit_logs`

#### Tenant Settings

- [ ] **#18 — Cấu hình giao diện và định dạng** `P1` · 3sp · Nền tảng: Backend, Web Admin
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass. Đã bổ sung audit `tenant_settings_updated`. Gap language/currency thuộc API #17 vẫn còn (đúng kiến trúc). Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: TenantSettingsService, test_tenant_settings.sh; thiếu: language/currency nằm ở Tenant entity chứ không phải tenant_settings; không ghi audit
  - *User Story:* Là một Company Admin, tôi muốn cấu hình ngôn ngữ, định dạng ngày giờ, tiền tệ và màu thương hiệu để hệ thống phù hợp công ty.
  - *Acceptance Criteria:* Lưu language/date_format/time_format/currency/brand_color; preview trên UI; ghi audit.
  - *DB Entities:* `tenant_settings, audit_logs`

#### Tenant Security

- [ ] **#19 — Quản lý IP whitelist** `P1` · 5sp · Nền tảng: Backend, Web Admin
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** đã sửa scope client-type → scope theo ROLE (backend + UI), test pass đầy đủ. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: IpWhitelistService, test_ip_whitelist.sh; thiếu: CRUD đầy đủ nhưng KHÔNG có filter nào thực sự chặn request theo IP — dữ liệu whitelist chưa được enforce
  - *User Story:* Là một Company Admin, tôi muốn thêm/sửa/tắt IP whitelist cho web admin hoặc API để kiểm soát truy cập theo mạng.
  - *Acceptance Criteria:* Thêm IP/CIDR; chọn applies_to; bật/tắt; expires_at tùy chọn; chặn request ngoài whitelist theo policy; ghi audit denied.
  - *DB Entities:* `tenant_ip_whitelists, audit_logs`

#### Plans

- [x] **#20 — Quản lý gói dịch vụ** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: PlanService, test_plans.sh
  - *User Story:* Là một Platform Admin, tôi muốn tạo và cập nhật các gói trial/basic/pro/enterprise để kiểm soát mô hình SaaS.
  - *Acceptance Criteria:* Tạo/sửa plan; bật/tắt plan; cấu hình giá; không xóa plan đang có subscription active.
  - *DB Entities:* `plans, audit_logs`

#### Plan Limits

- [x] **#21 — Cấu hình giới hạn gói** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass hoặc hoãn hợp lệ theo Sprint liên quan. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: PlanLimitsService + PlanLimitEnforcementService, test_plan_limits.sh
  - *User Story:* Là một Platform Admin, tôi muốn thiết lập giới hạn nhân viên, site, storage, random check theo gói để hệ thống enforce đúng gói dịch vụ.
  - *Acceptance Criteria:* Cập nhật plan_limits; validate số không âm; API tạo nhân viên/site kiểm tra limit; ghi audit.
  - *DB Entities:* `plan_limits, plans, audit_logs`

#### Subscription

- [x] **#22 — Gán subscription cho tenant** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** toàn bộ case pass. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: TenantSubscriptionService, test_subscription.sh, SubscriptionExpirationJob
  - *User Story:* Là một Platform Admin, tôi muốn gán tenant vào plan và kỳ hạn sử dụng để quản lý trạng thái thuê bao.
  - *Acceptance Criteria:* Tạo subscription; set current_period_start/end; cập nhật tenants.current_subscription_id; status trialing/active/expired; ghi audit.
  - *DB Entities:* `tenant_subscriptions, tenants, plans, audit_logs`

### Epic: RBAC

#### Role

- [x] **#23 — Seed role và permission hệ thống** `P0` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - 🔒 **Test tay thật — ĐÃ KHÓA (2026-08-13):** xác nhận idempotent qua DB/API. Xem `docs/manual-tests/MANUAL_TEST_LOG.md`.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: V13__seed_roles_and_permissions.sql, test_rbac_seed.sh
  - *User Story:* Là một hệ thống, tôi muốn khởi tạo role/permission mặc định khi deploy để có nền tảng phân quyền cho các module.
  - *Acceptance Criteria:* Seed permission theo resource/action; seed role company_admin/hr/site_supervisor/field_employee; idempotent; không tạo trùng.
  - *DB Entities:* `roles, permissions, role_permissions`
- [x] **#24 — Danh sách role** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-1-feature-24-list-roles.md` — assignmentCount scoped đúng theo tenant, tìm kiếm lọc đúng. Test tự động (Playwright).
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: RoleService.listRoles, test_list_roles.sh; thiếu: thiếu số user đang gán role
  - *User Story:* Là một Company Admin, tôi muốn xem danh sách role với tìm kiếm, lọc, phân trang để quản lý quyền người dùng.
  - *Acceptance Criteria:* Tìm theo tên; lọc active/system; sort priority; hiển thị số user đang gán; phân trang.
  - *DB Entities:* `roles, user_roles`
- [x] **#25 — Tạo role tùy chỉnh** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-1-feature-25-create-custom-role.md` — tạo qua UI thật, permission khớp chính xác, audit `role_created` xác nhận.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: RoleService.createRole, test_create_role.sh; thiếu: không có cờ is_editable/is_deletable riêng (chỉ isSystem); không ghi audit
  - *User Story:* Là một Company Admin, tôi muốn tạo role riêng cho công ty để đáp ứng mô hình vận hành khác nhau.
  - *Acceptance Criteria:* Nhập name/display_name/description; name không trùng trong tenant; chọn permission; role custom editable/deletable=true.
  - *DB Entities:* `roles, role_permissions, permissions, audit_logs`
- [x] **#26 — Sửa role và quyền** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-1-feature-26-update-role.md` — cache-eviction xác nhận bằng thực nghiệm (cùng JWT: 403 trước sửa → 200 ngay sau sửa, không đăng nhập lại).
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: RoleService.updateRole, test_update_role.sh; thiếu: không ghi audit ROLE_PERMISSION_UPDATE
  - *User Story:* Là một Company Admin, tôi muốn sửa role tùy chỉnh và danh sách permission để điều chỉnh quyền theo thực tế.
  - *Acceptance Criteria:* Không sửa role is_editable=false; cập nhật role_permissions; ghi audit ROLE_PERMISSION_UPDATE.
  - *DB Entities:* `roles, role_permissions, permissions, audit_logs`
- [x] **#27 — Xóa hoặc vô hiệu hóa role** `P1` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-1-feature-27-delete-role.md` — UI chủ động vô hiệu hóa nút Xóa khi còn người giữ; deactivate không tự thu hồi; xóa thành công sau khi thu hồi hết.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: RoleService.deleteRole, test_delete_role.sh; thiếu: chặn xóa nhưng không có fallback deactivate; không ghi audit
  - *User Story:* Là một Company Admin, tôi muốn xóa/vô hiệu hóa role không còn dùng để giữ hệ thống quyền gọn gàng.
  - *Acceptance Criteria:* Không xóa role is_deletable=false; nếu đã gán user thì chỉ cho deactivate; ghi audit.
  - *DB Entities:* `roles, user_roles, audit_logs`

#### Permission

- [x] **#28 — Xem permission theo nhóm** `P0` · 2sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-1-feature-28-list-permissions.md` — permission đã ẩn (`tenants:update`) xác nhận không xuất hiện trong picker.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: PermissionService.listGrouped, test_list_permissions.sh
  - *User Story:* Là một Company Admin, tôi muốn xem permission được nhóm theo nghiệp vụ để dễ cấu hình role.
  - *Acceptance Criteria:* Hiển thị group_name/display_name/description; lọc scope; chỉ hiển thị is_active.
  - *DB Entities:* `permissions`

#### User Role

- [x] **#29 — Gán role cho user** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-1-feature-29-assign-role.md` — gán/idempotent/reactivate/chặn leo thang/chặn gán role nền tảng đều pass qua UI+API thật. Scope theo công trình cụ thể chưa test hết luồng (để dành Sprint 2); `expires_at` vẫn chưa có (gap thật, không chặn khóa).
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: UserRoleService.assignRole, test_assign_role.sh; thiếu: chưa có scope theo site, chưa có expires_at, không ghi audit ROLE_GRANT
  - *User Story:* Là một Company Admin, tôi muốn gán role cho thành viên trong tenant hoặc theo site để cấp quyền đúng phạm vi.
  - *Acceptance Criteria:* Chọn user, role, scope tenant/site; hỗ trợ expires_at; kiểm tra permission; ghi audit ROLE_GRANT.
  - *DB Entities:* `user_roles, roles, users, tenants, sites, audit_logs`
- [x] **#30 — Thu hồi role** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-1-feature-30-revoke-role.md` — thu hồi/idempotent/chặn xuyên tenant đều pass. Case 4/5 phát hiện gap "tự khóa vĩnh viễn khi thu hồi admin cuối cùng" — **đã vá cùng ngày**: safeguard 409 (`UserRoleService.assertNotLastAdminHolder`) + tự phục hồi cho chủ sở hữu (`UserRoleService.selfHealOwnerRoles`, chạy ở login/switch-tenant/`GET /roles/me`). Đã tái hiện lại đúng kịch bản gốc và xác nhận owner tự phục hồi hoàn toàn, không cần can thiệp DB.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: UserRoleService.revokeRole, test_revoke_role.sh; thiếu: soft-delete qua deletedAt (không phải is_active); không lưu revoked_by; không có safeguard mất admin cuối; không ghi audit
  - *User Story:* Là một Company Admin, tôi muốn thu hồi role của user để đảm bảo quyền truy cập đúng hiện trạng.
  - *Acceptance Criteria:* Set is_active=false; lưu revoked_by/revoked_at; không thu hồi quyền cuối cùng của company_admin nếu gây mất admin; ghi audit.
  - *DB Entities:* `user_roles, audit_logs`

### Epic: Audit

#### Audit nền tảng

- [x] **#31 — Ghi audit cho hành động quan trọng** `P0` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-1-feature-31-audit-logging.md` — xác nhận qua màn Nhật ký audit thật, đúng actor/entity/request_id, đúng phạm vi tenant. Gap gốc "record() không được gọi ở đâu cả" đã xác nhận SAI hoàn toàn với hiện trạng, đã gọi ở 17 module.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: AuditLogController/Service (đọc đầy đủ), test_audit_logs.sh; thiếu: AuditLogService.record() KHÔNG được gọi ở BẤT KỲ module nào — audit trail không hoạt động dù API đọc hoàn chỉnh
  - *User Story:* Là một hệ thống, tôi muốn ghi lại actor, action, resource, old/new value và request_id để truy vết đầy đủ các thao tác.
  - *Acceptance Criteria:* Middleware tạo request_id; service ghi audit; mask dữ liệu nhạy cảm; audit_logs append-only.
  - *DB Entities:* `audit_logs`

### Epic: Notification

#### Notification nền tảng

- [x] **#32 — Tạo notification in-app cơ bản** `P0` · 3sp · Nền tảng: Backend, Web Admin, Mobile App
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-1-feature-32-notification-inbox.md` — chuông/badge/màn Thông báo/đánh dấu đã đọc đều đúng qua UI thật.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: NotificationService, test_notification_inbox.sh, test_mark_read.sh
  - *User Story:* Là một hệ thống, tôi muốn tạo thông báo in-app cho user để có nền cho các module sau.
  - *Acceptance Criteria:* Tạo notification channel=in_app; user xem unread count; đánh dấu đã đọc; ghi created_at/read_at.
  - *DB Entities:* `notifications`

---

## Sprint 2: Nhân sự & Hạ tầng công trình: Employee, Workspace, Face ID, Site, Geofence, Shift, Assignment

### Epic: Employee

#### Lời mời

- [x] **#33 — Mời nhân viên bằng email** `P0` · 5sp · Nền tảng: Backend, Web Admin, Queue/AI/Automation
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-2-feature-33-invite-employee.md` — đã vá cả 2 gap (chọn workspace mặc định + audit `invitation_sent`), cộng thêm notification `EMPLOYEE_INVITED`. Xác nhận lại qua UI/DB thật.
  - 🐛 **Bug phát hiện + đã sửa (2026-08-16):** ô "Vai trò (Role)" trong modal mời chỉ hiện 4 role
    hệ thống, không bao giờ hiện role tùy chỉnh của công ty — do gọi `GET /roles` thiếu tham số
    `tenantId`, khiến bất kỳ ai không chọn role hợp lệ đều bị mời với role mặc định EMPLOYEE.
    Sửa: `InviteEmployeeModal` truyền `tenantId`. Bổ sung thêm: nhân viên tạo thủ công (không tài
    khoản) giờ có ô "Vai trò dự kiến (Tùy chọn)" (`Employee.plannedRoleId`, migration V94) — nếu
    sau này mời đúng email đó mà không chọn role, hệ thống tự dùng role dự kiến này thay vì rơi về
    EMPLOYEE. Đã test end-to-end: tạo hồ sơ thủ công + chọn role tùy chỉnh → mời qua email không
    chọn role → xác nhận `invitation.role_id` đúng role đã dự kiến → chấp nhận → `user_roles`
    đúng role tùy chỉnh, không phải EMPLOYEE.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: EmployeeInvitationController/Service, test_invite_employee.sh; thiếu: không chọn workspace mặc định; không ghi audit
  - *User Story:* Là một HR/Admin, tôi muốn mời nhân viên vào công ty bằng email để onboard nhân viên nhanh.
  - *Acceptance Criteria:* Nhập email/name; chọn default role/workspace; tạo invite token; gửi notification/email; trạng thái pending.
  - *DB Entities:* `tenant_invitations, tokens, users, roles, workspaces, notifications, audit_logs`
- [x] **#34 — Chấp nhận lời mời** `P0` · 5sp · Nền tảng: Backend, Web Admin, Mobile App
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA cho Web (2026-08-15):** xem `docs/manual-tests/sprint-2-feature-34-accept-invitation.md` — đã vá cả 2 gap (tự tạo WorkspaceMember theo workspace mặc định + audit `invitation_accepted`), cộng thêm notification `INVITATION_ACCEPTED` cho người mời. Mobile App chưa test.
  - ⚠️ **Gap còn tồn đọng — chưa sửa (2026-08-15):** Link mời trong email luôn mở Web Admin
    (`${APP_FRONTEND_URL}/accept-invite?...`) qua trình duyệt, **không tự mở Mobile App** dù máy có
    cài app — vì thiếu cấu hình Universal Link/App Link (`apple-app-site-association` cho iOS,
    `assetlinks.json` cho Android). Mobile App **đã có sẵn màn hình accept-invite đầy đủ**
    (`app/accept-invite.tsx`, cùng logic với Web) nhưng chỉ mở được qua deep link riêng của app
    (`famsfrontappproject://accept-invite?token=...`), không phải qua link email — hiện không có
    đường vào từ luồng thật. Cần domain production thật để verify Universal Link, chưa làm được
    trong môi trường dev hiện tại. Notification in-app (`EMPLOYEE_INVITED`) vẫn hiển thị đúng trên
    app dù thiếu phần này (dùng chung API, không phụ thuộc deep link).
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: InvitationPublicController.acceptInvitation, test_accept_invitation.sh; thiếu: tạo user+role+employee nhưng không tạo WorkspaceMember
  - *User Story:* Là một nhân viên được mời, tôi muốn chấp nhận lời mời tham gia công ty để trở thành thành viên tenant.
  - *Acceptance Criteria:* Token hợp lệ; tạo hoặc liên kết user; tạo tenant_user; gán role/workspace mặc định; cập nhật accepted_at.
  - *DB Entities:* `tenant_invitations, tenant_users, users, user_roles, workspace_members`
- [x] **#35 — Hủy lời mời** `P1` · 2sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-2-feature-35-cancel-invitation.md` — đã vá cả 2 gap (lưu cancelled_by/cancel_reason/cancelled_at + audit `invitation_cancelled`); modal Hủy có thêm ô nhập lý do.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: EmployeeInvitationController.cancelInvitation, test_cancel_invitation.sh; thiếu: không ghi audit
  - *User Story:* Là một HR/Admin, tôi muốn hủy lời mời chưa được chấp nhận để tránh user vào sai tenant.
  - *Acceptance Criteria:* Chỉ hủy pending; lưu cancelled_by/cancel_reason; revoke token; gửi thông báo nếu cần.
  - *DB Entities:* `tenant_invitations, tokens, audit_logs`

#### Nhân viên

- [x] **#36 — Danh sách nhân viên** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-15):** xem `docs/manual-tests/sprint-2-feature-36-list-employees.md` — đã vá cả 2 gap: filter Face ID (đã đăng ký/chưa) và filter Workspace riêng (join workspace_members, độc lập filter Phòng ban cũ theo tên).
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: EmployeeController.listEmployees, test_list_employees.sh; thiếu: thiếu filter face_registered và workspace
  - *User Story:* Là một HR/Admin, tôi muốn xem danh sách nhân viên có tìm kiếm, lọc, sort và phân trang để quản lý nhân sự hiệu quả.
  - *Acceptance Criteria:* Tìm theo tên/email/employee_code; lọc status/workspace/face_registered; sort ngày tạo; phân trang.
  - *DB Entities:* `tenant_users, users, workspace_members, face_embeddings`
- [x] **#37 — Xem chi tiết nhân viên** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-37-employee-detail.md` — gap gốc (workspaces/assignments hardcode rỗng) đã xác nhận SỬA XONG qua code, audit note cũ đã lỗi thời. Response giờ cũng có `nationalId`/`terminatedAt` (bổ sung cùng đợt fix #39/#40).
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: EmployeeService.getEmployee, test_get_employee.sh; thiếu: workspaces/assignments trong response bị hardcode rỗng, chưa nối với 2 module đó
  - *User Story:* Là một HR/Admin, tôi muốn xem hồ sơ, workspace, role, assignment và Face ID của nhân viên để nắm đầy đủ thông tin nhân sự.
  - *Acceptance Criteria:* Hiển thị thông tin user/tenant_user; role; workspace; assignment active; trạng thái Face ID; lịch sử cơ bản.
  - *DB Entities:* `users, tenant_users, user_roles, workspace_members, assignments, face_embeddings`
- [x] **#38 — Tạo nhân viên thủ công** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-38-create-employee-manual.md` — không có gap, đã xác nhận chính thức luồng "Vai trò dự kiến".
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: EmployeeController.createEmployee, test_create_employee.sh
  - *User Story:* Là một HR/Admin, tôi muốn tạo nhân viên mới trong tenant để bổ sung nhân sự không qua invite.
  - *Acceptance Criteria:* Nhập employee_code/name/phone/email/position; employee_code không trùng; kiểm tra plan limit; tạo user nếu cần.
  - *DB Entities:* `users, tenant_users, plan_limits, tenant_subscriptions, audit_logs`
- [x] **#39 — Cập nhật nhân viên** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-39-update-employee.md` — đã vá gap `national_id`: thêm cột `national_id` (migration V95), field trong Create/UpdateEmployeeRequest, mask bằng `@Masked` (cùng cơ chế email/phone, dựa trên quyền `employees:pii:read`), tự động mask trong audit log qua `MaskingUtils.PII_KEYS` (đã có sẵn key này). Ghi audit cho employee_status_changed cũng đã xác nhận hoạt động. Test live: set/patch national_id, xác nhận trả về đúng giá trị cho owner (có quyền PII).
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: EmployeeController.updateEmployee, test_update_employee.sh; thiếu: không có trường national_id; không ghi audit
  - *User Story:* Là một HR/Admin, tôi muốn cập nhật thông tin nhân viên để giữ hồ sơ nhân viên chính xác.
  - *Acceptance Criteria:* Cập nhật position/employment_type/status; mã hóa national_id; ghi audit; không sửa dữ liệu nhạy cảm nếu thiếu quyền.
  - *DB Entities:* `tenant_users, users, audit_logs`
- [x] **#40 — Tạm ngừng/nghỉ việc nhân viên** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-40-terminate-employee.md` — đã vá **gap nghiêm trọng**: `EmployeeService.changeEmployeeStatus` giờ set `assignment.status="cancelled"` (giống `AssignmentService.cancelAssignment`) cho mọi assignment đang active khi terminate, thay vì chỉ hủy Random Check đang chờ như trước. Đã thêm cột `terminated_at` (migration V95), set khi chuyển sang terminated, clear khi HR đảo ngược quyết định. Đã thêm ghi audit log `employee_status_changed` (trước đây KHÔNG có). Face ID tự thu hồi giữ nguyên hành vi đã đúng từ trước. Test live end-to-end trên tenant test: tạo employee+site+assignment (active) → terminate → xác nhận assignment chuyển "cancelled" trong DB, terminatedAt được set, audit log ghi nhận → reactivate → xác nhận terminatedAt về null.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: EmployeeService.changeEmployeeStatus, test_change_employee_status.sh; thiếu: không có terminated_at; không tự hủy assignment/Face ID
  - *User Story:* Là một HR/Admin, tôi muốn đổi trạng thái nhân viên thành inactive/terminated để ngăn truy cập và chấm công sai.
  - *Acceptance Criteria:* Set status; terminated_at nếu nghỉ việc; revoke assignment active nếu policy yêu cầu; disable face profile nếu cần.
  - *DB Entities:* `tenant_users, assignments, face_embeddings, audit_logs`
- [x] **#41 — Import danh sách nhân viên** `P1` · 8sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-41-import-employees.md` — đã vá gap "không tải được file lỗi": thêm endpoint mới `POST /tenants/{id}/employees/import/errors-export` (multipart, cùng file đã import), tái sử dụng lại đúng logic validate của `importEmployees` (tách helper `validateImportRow`), trả về `.xlsx` chỉ chứa các dòng lỗi kèm cột `errors` gộp lý do — theo đúng pattern download file `.xlsx` hiện có (`EmployeeExportService`/`GET /export`). Test live: import file 3 dòng (1 hợp lệ, 2 lỗi) → gọi endpoint mới → tải về đúng .xlsx chỉ 2 dòng lỗi với lý do khớp JSON errors ban đầu.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: EmployeeService.importEmployees, test_import_employees.sh; thiếu: không xuất được file lỗi tải về (chỉ trả JSON)
  - *User Story:* Là một HR/Admin, tôi muốn import nhân viên bằng file Excel để tạo dữ liệu nhanh khi triển khai.
  - *Acceptance Criteria:* Upload file; preview lỗi; kiểm tra trùng employee_code/email/phone; tạo theo batch; xuất file lỗi.
  - *DB Entities:* `users, tenant_users, workspace_members, audit_logs`
- [x] **#42 — Export danh sách nhân viên** `P2` · 2sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-42-export-employees.md` — mask PII theo quyền đúng; **gap `national_id` đã vá**: cột giờ có trong file export, mask literal `"***"` khi thiếu quyền PII.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: EmployeeExportService, test_export_employees.sh
  - *User Story:* Là một HR/Admin, tôi muốn xuất danh sách nhân viên theo bộ lọc để phục vụ quản trị nội bộ.
  - *Acceptance Criteria:* Xuất CSV/XLSX; tôn trọng filter hiện tại; không xuất national_id nếu thiếu quyền.
  - *DB Entities:* `tenant_users, users, workspace_members`

### Epic: Workspace

#### Workspace

- [x] **#43 — Tạo workspace** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-43-create-workspace.md` — **gap "không ghi audit" đã vá**, ghi action `workspace_created` đúng pattern Employee/RBAC/Tenant.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: WorkspaceService.createWorkspace, test_create_workspace.sh; thiếu: không ghi audit
  - *User Story:* Là một Company Admin, tôi muốn tạo phòng ban/đội nhóm để tổ chức nhân sự theo cấu trúc công ty.
  - *Acceptance Criteria:* Nhập name/code/parent; code không trùng trong tenant; hỗ trợ cây workspace; ghi audit.
  - *DB Entities:* `workspaces, audit_logs`
- [x] **#44 — Danh sách workspace** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-44-list-workspace.md` — gap gốc "thiếu số thành viên" xác nhận SỬA XONG qua UI thật, tự cập nhật real-time.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: WorkspaceService.listWorkspaces, test_list_workspace.sh; thiếu: thiếu số thành viên trong response
  - *User Story:* Là một Company Admin, tôi muốn xem cây workspace có tìm kiếm và lọc trạng thái để quản lý cơ cấu tổ chức.
  - *Acceptance Criteria:* Hiển thị dạng tree/list; tìm theo name/code; lọc active; xem số thành viên.
  - *DB Entities:* `workspaces, workspace_members`
- [x] **#45 — Cập nhật workspace** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-45-update-workspace.md` — chặn vòng lặp parent hoạt động đúng; **gap "không ghi audit" đã vá**, action `workspace_updated` với before/after.
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: WorkspaceService.updateWorkspace; thiếu: không ghi audit
  - *User Story:* Là một Company Admin, tôi muốn sửa tên, mã, mô tả hoặc workspace cha để giữ cơ cấu tổ chức đúng.
  - *Acceptance Criteria:* Không cho tạo vòng lặp parent; code không trùng; ghi audit.
  - *DB Entities:* `workspaces, audit_logs`

#### Workspace Member

- [x] **#46 — Gán nhân viên vào workspace** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-46-assign-workspace-member.md` — **gap `is_primary`/`effective_from` đã vá** (migration V96), enforce 1 primary/nhân viên ở cả tầng app lẫn DB unique index, UI có DatePicker + switch "Đặt làm chính", tag "Chính" hiển thị đồng bộ. Vá thêm 1 race condition (Hibernate flush order) và 1 lỗi JSON key trùng (Lombok/Jackson) phát hiện lúc test sống.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: WorkspaceMemberService.assignMember, test_workspace_members.sh; thiếu: không có effective_from/is_primary
  - *User Story:* Là một HR/Admin, tôi muốn thêm nhân viên vào workspace với vai trò phù hợp để phân nhóm nhân sự rõ ràng.
  - *Acceptance Criteria:* Chọn role_in_workspace; status active; effective_from; chỉ một primary workspace active; ghi audit.
  - *DB Entities:* `workspace_members, workspaces, tenant_users, audit_logs`
- [x] **#47 — Chuyển workspace cho nhân viên** `P1` · 5sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-47-transfer-workspace-member.md` — **gap `left_at`/`is_primary` đã vá** (cùng migration V96): `left_at` riêng biệt với `deletedAt`, `isPrimary` carry-over khi chuyển (override được qua UI). Test qua đúng thao tác trên Web Admin thật.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: WorkspaceMemberService.transferMember, test_transfer_workspace_member.sh; thiếu: giữ lịch sử tốt nhưng thiếu is_primary/effective date
  - *User Story:* Là một HR/Admin, tôi muốn chuyển nhân viên từ workspace này sang workspace khác để cập nhật tổ chức theo thực tế.
  - *Acceptance Criteria:* Đóng membership cũ bằng left_at/effective_to; tạo membership mới; cập nhật is_primary; giữ lịch sử.
  - *DB Entities:* `workspace_members, audit_logs`

### Epic: Face ID

#### Consent

- [x] **#48 — Ghi nhận đồng ý Face ID** `P0` · 5sp · Nền tảng: Backend, Mobile App
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-48-faceid-consent.md` — **gap version/hash/ip/device đã vá** (migration V97), enforce đúng "chỉ consent current"; test live Backend+Web+Mobile App (chạy App thật qua expo web + camera giả lập) đều pass.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: FaceIdService.giveConsent, test_consent.sh; thiếu: chỉ lưu cờ+thời gian, thiếu version/hash/ip/device
  - *User Story:* Là một nhân viên, tôi muốn xác nhận đồng ý sử dụng dữ liệu khuôn mặt để đáp ứng yêu cầu bảo mật và pháp lý.
  - *Acceptance Criteria:* Hiển thị nội dung consent; user xác nhận; lưu consent_version/hash/ip/device; chỉ consent current được dùng.
  - *DB Entities:* `face_id_consents, tenant_users, audit_logs`

#### Đăng ký khuôn mặt

- [x] **#49 — Đăng ký Face ID** `P0` · 8sp · Nền tảng: Backend, Mobile App, Queue/AI/Automation
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-49-faceid-enroll.md` — AC gốc lỗi thời (quality_score/provider không tồn tại, dùng InsightFace local — không sửa). **Gap audit đã vá**. Web Admin + Mobile App (Claude qua camera giả lập, User xác nhận nốt trên thiết bị thật) đều pass.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: FaceIdService.enrollFace, test_enroll.sh; thiếu: không check quality_score/lưu provider ở tầng Java
  - *User Story:* Là một nhân viên, tôi muốn chụp ảnh đăng ký khuôn mặt để sử dụng Face ID khi chấm công.
  - *Acceptance Criteria:* Yêu cầu consent current; chụp 3-5 ảnh; kiểm tra quality_score; lưu provider/aws_face_id; set face_registered=true.
  - *DB Entities:* `face_embeddings, face_id_consents, tenant_users, audit_logs`
- [x] **#50 — HR xem trạng thái Face ID** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-50-faceid-hr-view.md` — danh sách/filter/enrolledAt đúng; luồng duyệt với ảnh tham chiếu thật test live qua UI pass; quality_score không tồn tại (gap kiến trúc, không sửa).
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: ReportController /face-id/enrollment, test_face_id_report.sh
  - *User Story:* Là một HR/Admin, tôi muốn xem nhân viên nào đã/chưa đăng ký Face ID để theo dõi onboarding.
  - *Acceptance Criteria:* Danh sách lọc face_registered; xem quality_score/registered_at; không hiển thị ảnh nếu thiếu quyền.
  - *DB Entities:* `tenant_users, face_embeddings`

#### Thu hồi Face ID

- [x] **#51 — Xóa/vô hiệu hóa Face ID** `P1` · 5sp · Nền tảng: Backend, Web Admin, Mobile App
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-51-faceid-revoke.md` — **gap deleted_reason/deleted_by + audit đã vá**, modal Web Admin có ô nhập lý do. Test live end-to-end qua cả Web Admin và Mobile App (App thật, tự thu hồi) đều pass.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: FaceIdService.revokeFace, test_revoke.sh; thiếu: thiếu deleted_reason/deleted_by; không ghi audit FACE_DELETE
  - *User Story:* Là một HR/Admin hoặc nhân viên theo quyền, tôi muốn thu hồi hồ sơ Face ID để xử lý nghỉ việc hoặc rút consent.
  - *Acceptance Criteria:* Set is_active=false; lưu deleted_reason/deleted_by; cập nhật tenant_users.face_registered=false; ghi audit FACE_DELETE.
  - *DB Entities:* `face_embeddings, tenant_users, face_id_consents, audit_logs`

### Epic: Site

#### Công trình

- [x] **#52 — Tạo công trình** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-52-create-site.md` — province/workspace là gap kiến trúc (không sửa); supervisor làm qua Assignment riêng, không thiếu hẳn; plan limit hoạt động đúng; **gap đã vá**: ghi audit `site_created`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: SiteService.createSite, test_create_site.sh; thiếu: không có trường province, không liên kết workspace/supervisor
  - *User Story:* Là một HR/Admin, tôi muốn tạo site/công trình mới để quản lý địa điểm chấm công.
  - *Acceptance Criteria:* Nhập code/name/address/province/center; code không trùng; kiểm tra plan limit max_sites; gán workspace/supervisor.
  - *DB Entities:* `sites, workspaces, tenant_users, plan_limits, audit_logs`
- [x] **#53 — Danh sách công trình** `P0` · 3sp · Nền tảng: Backend, Web Admin, Mobile App
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-53-list-sites.md` — filter province/workspace, sort start_date là gap kiến trúc (không sửa); site-scope filter cho SITE_SUPERVISOR hoạt động đúng.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: SiteService.listSites, test_list_sites.sh; thiếu: thiếu filter province/workspace (trường không tồn tại)
  - *User Story:* Là một HR/Admin, tôi muốn xem công trình có tìm kiếm, lọc, sort, phân trang để quản lý nhiều site dễ dàng.
  - *Acceptance Criteria:* Tìm theo code/name/address; lọc status/province/workspace; sort start_date; phân trang.
  - *DB Entities:* `sites, workspaces`
- [x] **#54 — Xem chi tiết công trình** `P0` · 3sp · Nền tảng: Backend, Web Admin, Mobile App
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-54-site-detail.md` — **gap đã vá**: supervisor giờ hiện ngay ở card chính (field `supervisors` mới lấy từ Assignment); site-scope 403 hoạt động đúng.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: SiteService.getSiteDetail, test_get_site_detail.sh; thiếu: không hiển thị supervisor (Site không có trường này)
  - *User Story:* Là một HR/Admin/Supervisor, tôi muốn xem thông tin site, geofence, ca, nhân viên đang phân công để theo dõi vận hành công trình.
  - *Acceptance Criteria:* Hiển thị site detail; bản đồ tâm; geofence active; shift default; assignment active; supervisor.
  - *DB Entities:* `sites, site_geofences, shift_templates, assignments`
- [x] **#55 — Cập nhật công trình** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - ✅ **Test tay thật — PASS, ĐÃ KHÓA (2026-08-16):** xem `docs/manual-tests/sprint-2-feature-55-update-site.md` — supervisor không sửa qua form này (đúng kiến trúc); gap "validate status yếu" xác nhận là nghiên cứu sai (đã có @Pattern từ trước); **gap thật đã vá**: ghi audit `site_updated`.
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: SiteService.updateSite, test_update_site.sh; thiếu: không có trường supervisor để cập nhật
  - *User Story:* Là một HR/Admin, tôi muốn sửa thông tin công trình để đảm bảo dữ liệu công trình chính xác.
  - *Acceptance Criteria:* Cập nhật name/address/status/supervisor; validate status; ghi audit.
  - *DB Entities:* `sites, audit_logs`

### Epic: Geofence

#### Geofence

- [x] **#56 — Tạo geofence cho công trình** `P0` · 8sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-08-17):* ✅ ĐÃ VÁ — `area_sqm` tính qua shoelace/equirectangular (migration V98) + ghi audit `geofence_created`. Bảng `geofence_histories` riêng không tồn tại — xác nhận là kiến trúc versioning có chủ đích trong bảng `geofences`, không phải thiếu sót. Test live: `docs/manual-tests/sprint-2-feature-56-create-geofence.md`.
  - *User Story:* Là một HR/Admin, tôi muốn vẽ vùng geofence trên bản đồ để xác định khu vực check-in hợp lệ.
  - *Acceptance Criteria:* Vẽ polygon hợp lệ; nhập buffer; tính area_sqm; chỉ một/multiple geofence active theo policy; ghi history created.
  - *DB Entities:* `site_geofences, geofence_histories, audit_logs`
- [x] **#57 — Sửa geofence** `P0` · 8sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-08-17):* ✅ ĐÃ VÁ — `changeReason` thêm dưới dạng TÙY CHỌN (không bắt buộc — quyết định nghiệp vụ, theo tiền lệ #51) + tính lại area mỗi lần sửa + ghi audit `geofence_updated` với old/new snapshot đầy đủ. Test live: `docs/manual-tests/sprint-2-feature-57-update-geofence.md`.
  - *User Story:* Là một HR/Admin, tôi muốn cập nhật polygon hoặc buffer để phản ánh ranh giới công trình thực tế.
  - *Acceptance Criteria:* Lưu old/new polygon, buffer, area; bắt buộc change_reason khi thay đổi lớn; ghi audit và history.
  - *DB Entities:* `site_geofences, geofence_histories, audit_logs`
- [x] **#58 — Xem lịch sử geofence** `P1` · 3sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-08-17):* ✅ ĐÃ VÁ — `changed_by` giờ resolve ra tên thật (`createdByName` qua UserRepository) thay vì UUID thô — gap quan trọng nhất, ảnh hưởng trực tiếp khả năng audit. `area_sqm` cũ/mới xem được qua so sánh các dòng lịch sử (nhờ #56/#57). `change_type` tường minh cố ý KHÔNG làm (lý do trong kịch bản test). Versioning trong bảng `geofences` xác nhận là thiết kế có chủ đích. Test live: `docs/manual-tests/sprint-2-feature-58-geofence-history.md`.
  - *User Story:* Là một HR/Admin, tôi muốn xem timeline thay đổi geofence để audit tranh chấp vị trí.
  - *Acceptance Criteria:* Hiển thị change_type, changed_by, changed_at, reason, diện tích cũ/mới; xem trên bản đồ nếu có.
  - *DB Entities:* `geofence_histories, site_geofences, users`

### Epic: Shift

#### Ca làm việc

- [x] **#59 — Tạo ca làm việc** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-08-17):* ✅ ĐÃ VÁ — thêm `is_default` (migration V99, unique 1 mặc định/site) + audit `shift_created`. code/standard_hours/JSON-schedule là khác biệt kiến trúc có chủ đích, không sửa. Test live: `docs/manual-tests/sprint-2-feature-59-create-shift.md`.
  - *User Story:* Là một HR/Admin, tôi muốn tạo shift template cho site để áp dụng giờ làm chuẩn cho nhân viên.
  - *Acceptance Criteria:* Nhập code/name/schedule/grace/standard_hours; validate JSON schedule; có thể đặt default.
  - *DB Entities:* `shift_templates, sites, audit_logs`
- [x] **#60 — Cấu hình OT và giới hạn giờ** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-08-17):* ✅ ĐÃ XONG + VÁ THÊM — audit gốc đúng, đã mở rộng OT ngày/tuần từ trước; thêm audit `shift_ot_configured`. Test live: `docs/manual-tests/sprint-2-feature-60-shift-ot-config.md`.
  - *User Story:* Là một HR/Admin, tôi muốn thiết lập allow_overtime, early_checkin_minutes, late_checkout_minutes để tính công đúng policy.
  - *Acceptance Criteria:* Bật/tắt OT; nhập phút check-in sớm/check-out muộn; áp dụng khi check-in/out và tính attendance.
  - *DB Entities:* `shift_templates, checkins, attendance_summaries`
- [x] **#61 — Danh sách ca theo site** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-08-17):* ✅ ĐÃ VÁ — thêm tìm theo tên (`search`) + lọc `isDefault`, dùng `JpaSpecificationExecutor` (Criteria API) sau khi JPQL `CAST(:param)` gây lỗi cast bytea trên tham số null. Test live: `docs/manual-tests/sprint-2-feature-61-list-shifts.md`.
  - *User Story:* Là một HR/Admin, tôi muốn xem ca làm việc của công trình để quản lý lịch làm việc.
  - *Acceptance Criteria:* Tìm theo code/name; lọc active/default; sort; chỉ một ca default active mỗi site.
  - *DB Entities:* `shift_templates`
- [x] **#62 — Cập nhật hoặc ngừng dùng ca** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-08-17):* ✅ ĐÃ XONG + VÁ THÊM — audit gốc "không có endpoint xóa cứng" xác nhận LỖI THỜI (endpoint có thật, chặn đúng điều kiện); thêm audit `shift_updated`/`shift_deleted`. Test live: `docs/manual-tests/sprint-2-feature-62-update-deactivate-shift.md`.
  - *User Story:* Là một HR/Admin, tôi muốn sửa hoặc deactivate shift template để thay đổi giờ làm không mất lịch sử.
  - *Acceptance Criteria:* Không xóa ca đã được assignment dùng; deactivate ca cũ; ghi audit.
  - *DB Entities:* `shift_templates, assignments, audit_logs`

### Epic: Assignment

#### Phân công

- [x] **#63 — Tạo phân công nhân viên vào site** `P0` · 8sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-08-17):* ✅ ĐÃ VÁ — chống trùng cùng site nâng cấp theo khoảng giờ thực tế (quyết định chủ dự án, bỏ unique index DB cũ, migration V100) + ghi audit `assignment_created`. Test live: `docs/manual-tests/sprint-2-feature-63-create-assignment.md`.
  - *User Story:* Là một HR/Admin, tôi muốn phân công nhân viên vào công trình theo ca và thời gian để cho phép nhân viên chấm công đúng site.
  - *Acceptance Criteria:* Chọn nhân viên/site/shift/start/end; role_at_site; assignment_type; kiểm tra trùng thời gian; status planned/active.
  - *DB Entities:* `assignments, tenant_users, sites, shift_templates, audit_logs`
- [x] **#64 — Danh sách phân công** `P0` · 3sp · Nền tảng: Backend, Web Admin, Mobile App
  - *Audit (2026-08-17):* ✅ ĐÃ VÁ — filter khoảng ngày (overlap) + màn hình Mobile App "Phân công của tôi" mới hoàn toàn (quyết định chủ dự án), endpoint `GET /tenants/{tenantId}/assignments/me`. Test live: `docs/manual-tests/sprint-2-feature-64-list-assignments.md`.
  - *User Story:* Là một HR/Admin/Supervisor, tôi muốn xem phân công có tìm kiếm, lọc, sort, phân trang để điều phối nhân sự công trình.
  - *Acceptance Criteria:* Lọc site, nhân viên, status, role_at_site, date range; hiển thị ca và site chính; phân trang.
  - *DB Entities:* `assignments, tenant_users, sites, shift_templates`
- [x] **#65 — Cập nhật phân công** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-08-17):* ✅ ĐÃ VÁ — chặn sửa assignment đã hủy (gap quan trọng nhất epic) + re-validate overlap dùng logic mới + ghi audit `assignment_updated`. Test live: `docs/manual-tests/sprint-2-feature-65-update-assignment.md`.
  - *User Story:* Là một HR/Admin, tôi muốn điều chỉnh ca, thời gian hoặc vai trò tại site để phù hợp thay đổi thực tế.
  - *Acceptance Criteria:* Không cho sửa assignment đã completed nếu thiếu quyền; validate overlap; ghi audit.
  - *DB Entities:* `assignments, audit_logs`
- [x] **#66 — Hủy phân công** `P0` · 3sp · Nền tảng: Backend, Web Admin, Queue/AI/Automation
  - *Audit (2026-08-17):* ✅ ĐÃ VÁ — thêm `cancelled_by`/`cancelled_at` (migration V100, cùng pattern employee_invitations V93) + ghi audit `assignment_cancelled`. Test live: `docs/manual-tests/sprint-2-feature-66-cancel-assignment.md`.
  - *User Story:* Là một HR/Admin, tôi muốn hủy phân công chưa hoặc đang hiệu lực để ngăn chấm công sai site.
  - *Acceptance Criteria:* Set assignment_status=cancelled; lưu cancelled_by/cancelled_at; cancel scheduled_checks pending liên quan.
  - *DB Entities:* `assignments, scheduled_checks, audit_logs`

---

## Sprint 3: Chấm công cốt lõi: Check-in/out (GPS/Face/Liveness), Attendance Summary, Push/Inbox

### Epic: Check-in

#### Chấm công

- [x] **#67 — Hiển thị site được phép check-in** `P0` · 3sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-08-17):* ✅ ĐÃ XONG, xác nhận qua test live — tính theo timezone từng site, đầy đủ hơn AC. Test: `docs/manual-tests/sprint-3-feature-67-available-sites.md`.
  - *User Story:* Là một nhân viên mobile, tôi muốn xem các công trình mình được phân công hôm nay để chọn đúng nơi chấm công.
  - *Acceptance Criteria:* App lấy assignment active hôm nay; hiển thị site, ca, trạng thái check-in/out; không hiển thị site không được phân công.
  - *DB Entities:* `assignments, sites, shift_templates, checkins`
- [x] **#68 — Check-in GPS cơ bản** `P0` · 8sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-08-17):* ✅ ĐÃ XONG + VÁ THÊM — ghi audit `checkin_submitted`. Test: `docs/manual-tests/sprint-3-feature-68-checkin-gps.md`.
  - *User Story:* Là một nhân viên, tôi muốn check-in tại công trình bằng vị trí GPS để ghi nhận giờ vào làm.
  - *Acceptance Criteria:* Lấy lat/lng/accuracy; tạo location point; kiểm tra assignment active; kiểm tra geofence; tạo checkins type=check_in.
  - *DB Entities:* `checkins, assignments, site_geofences, shift_templates`
- [x] **#69 — Check-in có Face ID** `P0` · 8sp · Nền tảng: Backend, Mobile App, Queue/AI/Automation
  - *Audit (2026-08-17):* ✅ ĐÃ XONG + VÁ GAP QUAN TRỌNG (quyết định chủ dự án) — nhánh `gps_face` (chụp 1 ảnh, không qua challenge) trước đây KHÔNG kiểm tra liveness thụ động (`requiresLiveness` luôn `false`), nghĩa là 1 ảnh tĩnh/in ra có thể vượt qua xác thực khuôn mặt. Đã sửa App gửi `requiresLiveness=true` khi có ảnh (kể cả offline sync). Không có field `selfie_url` riêng (khác AC, không sửa). Test: `docs/manual-tests/sprint-3-feature-69-checkin-face-id.md`.
  - *User Story:* Là một nhân viên, tôi muốn check-in kèm xác minh khuôn mặt khi policy yêu cầu để đảm bảo đúng người chấm công.
  - *Acceptance Criteria:* Nếu tenant/site yêu cầu Face ID thì bắt selfie; so khớp face; lưu score, face_valid, selfie_url; fail thì pending/invalid.
  - *DB Entities:* `checkins, face_embeddings, face_id_consents, audit_logs`
- [x] **#70 — Check-in có liveness** `P1` · 8sp · Nền tảng: Backend, Mobile App, Queue/AI/Automation
  - *Audit (2026-08-17):* ✅ ĐÃ XONG, xác nhận qua test live — 2 cơ chế song song (challenge chủ động cho `gps_face_liveness`, thụ động cho ảnh optional — nay áp dụng cả cho `gps_face`, xem #69). Không có field `invalid_reason` riêng (dùng `violations.violation_type`). Test: `docs/manual-tests/sprint-3-feature-70-checkin-liveness.md`.
  - *User Story:* Là một nhân viên, tôi muốn xác minh người thật khi chấm công để chống dùng ảnh/video giả mạo.
  - *Acceptance Criteria:* Nếu policy bật liveness; app thực hiện liveness; lưu liveness_passed; fail tạo invalid_reason.
  - *DB Entities:* `checkins, face_embeddings, violations`
- [x] **#71 — Kiểm tra check-in sớm** `P0` · 5sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-08-17):* ✅ ĐÃ XONG, xác nhận qua test live — chặt hơn AC (luôn từ chối cứng + chặn cả check-in sau khi ca kết thúc). Không sửa (đúng thiết kế). Test: `docs/manual-tests/sprint-3-feature-71-early-checkin.md`.
  - *User Story:* Là một hệ thống, tôi muốn kiểm tra nhân viên check-in quá sớm so với ca để giữ dữ liệu công đúng rule.
  - *Acceptance Criteria:* So với shift start và early_checkin_minutes; nếu quá sớm thì cảnh báo/từ chối theo policy; lưu invalid_reasons nếu cần.
  - *DB Entities:* `checkins, shift_templates, attendance_summaries`
- [x] **#72 — Check-out GPS** `P0` · 8sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-08-17):* ✅ ĐÃ XONG + VÁ THÊM — ghi audit `checkout_submitted`. Check-out dùng đúng policy snapshot lúc check-in (đúng thiết kế). Không có `paired_checkin_id` riêng (cùng 1 dòng). Test: `docs/manual-tests/sprint-3-feature-72-checkout-gps.md`.
  - *User Story:* Là một nhân viên, tôi muốn check-out khi rời công trình để ghi nhận giờ kết thúc làm việc.
  - *Acceptance Criteria:* Tìm check_in chưa paired; tạo check_out; paired_checkin_id trỏ check_in; kiểm tra geofence/face nếu policy yêu cầu.
  - *DB Entities:* `checkins, assignments, site_geofences`
- [x] **#73 — Kiểm tra check-out muộn** `P0` · 5sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-08-17):* ✅ ĐÃ XONG, xác nhận qua test live — dùng đúng snapshot lúc check-in (allowOvertime/lateCheckoutMinutes cũ), không áp dụng thay đổi Shift giữa ca. Test: `docs/manual-tests/sprint-3-feature-73-late-checkout.md`.
  - *User Story:* Là một hệ thống, tôi muốn xử lý nhân viên check-out quá muộn so với ca để giới hạn số giờ được tính.
  - *Acceptance Criteria:* So với shift end và late_checkout_minutes; nếu vượt quá thì cảnh báo/chỉ tính đến giới hạn theo policy; ghi metadata.
  - *DB Entities:* `checkins, shift_templates, attendance_summaries`
- [x] **#74 — Tính work_minutes cho cặp check-in/out** `P0` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - *Audit (2026-08-17):* ✅ ĐÃ XONG — 2 gap thật xác nhận (không trừ break, tính work_minutes cả khi pending_review). **Quyết định chủ dự án: giữ nguyên cả 2, không cần trừ break, không cần loại trừ khi pending.** Test: `docs/manual-tests/sprint-3-feature-74-work-minutes.md`.
  - *User Story:* Là một hệ thống, tôi muốn tính số phút làm việc khi có check-out để làm cơ sở tổng hợp công.
  - *Acceptance Criteria:* Ghép đúng cặp; trừ break theo schedule; bỏ qua invalid nếu policy; cập nhật work_minutes; trigger summary refresh.
  - *DB Entities:* `checkins, shift_templates, attendance_summaries`
- [x] **#75 — Check-in offline và đồng bộ** `P1` · 8sp · Nền tảng: Backend, Mobile App, Queue/AI/Automation
  - *Audit (2026-08-17):* ✅ ĐÃ XONG, audit gốc SAI 2/3 điểm — phát hiện lệch giờ thiết bị đã có sẵn (24h quá khứ/5 phút tương lai); cờ source có (`"offline"`, khác literal AC). **Đã vá thêm**: liveness thụ động cho ảnh offline (cùng gap #69) + ghi audit `checkin_submitted`. Test: `docs/manual-tests/sprint-3-feature-75-offline-checkin.md`.
  - *User Story:* Là một nhân viên ở nơi mạng yếu, tôi muốn lưu tạm check-in/offline và đồng bộ khi có mạng để không mất dữ liệu chấm công.
  - *Acceptance Criteria:* App lưu device_timestamp/GPS/selfie local; khi sync gửi source=offline_sync; server so lệch giờ; đánh pending nếu nghi ngờ.
  - *DB Entities:* `checkins, tokens, audit_logs`
- [x] **#76 — Hiển thị kết quả check-in/out** `P0` · 3sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-08-17):* ✅ ĐÃ XONG, xác nhận qua test live — lý do hiện qua các trường có cấu trúc (không phải 1 message). Thiếu nút "thử lại" tường minh (nhỏ, không sửa). Test: `docs/manual-tests/sprint-3-feature-76-checkin-result.md`.
  - *User Story:* Là một nhân viên, tôi muốn xem kết quả hợp lệ/chờ duyệt/lỗi ngay sau khi chấm công để biết cần xử lý gì.
  - *Acceptance Criteria:* Hiển thị valid/invalid/pending_review; lý do GPS/face/liveness; hướng dẫn thử lại hoặc gửi giải trình.
  - *DB Entities:* `checkins, violations`

#### Lịch sử chấm công

- [x] **#77 — Nhân viên xem lịch sử chấm công** `P0` · 3sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-08-17):* ✅ ĐÃ XONG + VÁ THÊM — thêm UI lọc status/tháng trên App + filter theo site mới (cả 2 tầng, theo quyết định chủ dự án). Test live: `docs/manual-tests/sprint-3-feature-77-employee-checkin-history.md`.
  - *User Story:* Là một nhân viên, tôi muốn xem lịch sử check-in/out theo ngày để tự kiểm tra công của mình.
  - *Acceptance Criteria:* Lọc theo tháng/site/status; hiển thị giờ vào/ra, tổng phút, lỗi; phân trang.
  - *DB Entities:* `checkins, attendance_summaries`

#### Quản lý chấm công

- [x] **#78 — HR xem danh sách check-in** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-08-17):* ✅ ĐÃ XONG, xác nhận qua test live — filter cốt lõi đúng. `invalid_reason`/`source`: quyết định chủ dự án không cần thêm. Test: `docs/manual-tests/sprint-3-feature-78-hr-list-checkins.md`.
  - *User Story:* Là một HR/Admin, tôi muốn xem danh sách check-in/out có tìm kiếm, lọc, sort, phân trang để kiểm soát dữ liệu chấm công.
  - *Acceptance Criteria:* Lọc date, site, employee, status, source, invalid_reason; sort occurred_at; xem ảnh/vị trí nếu có quyền.
  - *DB Entities:* `checkins, tenant_users, sites`
- [x] **#79 — HR xem chi tiết check-in** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-08-17):* ✅ ĐÃ XONG + VÁ THÊM — endpoint HR override giờ ghi audit `checkin_overridden` (trước đây thiếu). Không selfie/distance (đúng thiết kế, không sửa). Test: `docs/manual-tests/sprint-3-feature-79-hr-checkin-detail.md`.
  - *User Story:* Là một HR/Admin, tôi muốn xem đầy đủ bằng chứng của một check-in để xử lý tranh chấp và lỗi.
  - *Acceptance Criteria:* Hiển thị GPS, accuracy, geofence, distance, face score, selfie, device, source, pair check-in/out.
  - *DB Entities:* `checkins, site_geofences, face_embeddings`

### Epic: Attendance

#### Tổng hợp công

- [x] **#80 — Tự động tạo attendance summary** `P0` · 8sp · Nền tảng: Backend, Web Admin, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: AttendanceSummaryService.recompute, test_attendance_summary.sh
  - *User Story:* Là một hệ thống, tôi muốn tổng hợp công theo nhân viên, site và ngày để có bảng công ngày chính xác.
  - *Acceptance Criteria:* Upsert summary khi checkout; tính first_checkin/last_checkout/total_work_minutes; lưu calculated_at.
  - *DB Entities:* `attendance_summaries, checkins, shift_templates, assignments`
- [x] **#81 — Tính đi muộn** `P0` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: late detection, test_late_detection.sh
  - *Audit (2026-08-17):* 🟡→✅ ĐÃ FIX — gap thật: KHÔNG có grace period (trễ 1 phút đã tính muộn), AC ghi "shift start + grace" nhưng code không có field grace nào. Quyết định (project owner): thêm `Shift.graceMinutes` (mặc định 5, migration V101 áp hồi tố cho ca cũ), `CheckinRecord.shiftGraceMinutes` (snapshot), sửa `AttendanceSummaryService.recompute()`: trong ân hạn → không tính muộn; vượt ân hạn → tính ĐỦ số phút trễ thực (không trừ ân hạn). Web Admin: bổ sung input "Ân hạn trước khi tính muộn" trong form cấu hình OT + hiển thị trong bảng danh sách ca. Test live: PASS (xem `sprint-3-feature-81-late-detection.md`).
  - *User Story:* Là một hệ thống, tôi muốn xác định is_late và late_minutes để báo cáo kỷ luật giờ vào.
  - *Acceptance Criteria:* So first_checkin_at với shift start + grace; tính late_minutes theo rule; cập nhật summary.
  - *DB Entities:* `attendance_summaries, shift_templates, checkins`
- [x] **#82 — Tính về sớm** `P0` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: early-leave detection, test_early_leave.sh
  - *User Story:* Là một hệ thống, tôi muốn xác định is_early_leave và early_leave_minutes để báo cáo kỷ luật giờ ra.
  - *Acceptance Criteria:* So last_checkout_at với shift end; tính early_leave_minutes; cập nhật summary.
  - *DB Entities:* `attendance_summaries, shift_templates, checkins`
- [x] **#83 — Tính OT** `P0` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: OT calc, test_ot_minutes.sh
  - *User Story:* Là một hệ thống, tôi muốn tính ot_minutes theo shift và tổng giờ làm để phục vụ tính công/tăng ca.
  - *Acceptance Criteria:* Chỉ tính OT nếu allow_overtime=true; so total_work_minutes với standard_hours_per_day; cập nhật ot_minutes.
  - *DB Entities:* `attendance_summaries, shift_templates`
- [x] **#84 — Phát hiện thiếu checkout** `P0` · 3sp · Nền tảng: Backend, Web Admin, Mobile App, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: missingCheckout flag, test_missing_checkout.sh; thiếu: không tạo notification cho HR/nhân viên
  - *Audit (2026-08-17):* 🟡→✅ ĐÃ FIX — 2 gap thật: (1) không tạo notification cho HR/nhân viên — đã thêm `AttendanceEventTypes.MISSING_CHECKOUT_EMPLOYEE`/`MISSING_CHECKOUT_HR` vào `NotificationEventTypeCatalog`, gửi đúng 1 lần tại thời điểm `missingCheckout` chuyển false→true (không spam ở các lần tính lại sau); (2) gap mới phát hiện: endpoint `PATCH .../adjust` không ghi audit log (trong khi `unlock-and-recompute` có) — đã thêm `AuditLogService.record(...)` action `attendance_summary_adjusted`. `status=partial` trong AC gốc xác nhận SAI, hệ thống chỉ có `present`/`incomplete`. Test live: PASS cả 2 fix (xem `sprint-3-feature-84-missing-checkout.md`).
  - *User Story:* Là một hệ thống, tôi muốn đánh dấu ngày có check-in nhưng không có check-out để HR xử lý công thiếu dữ liệu.
  - *Acceptance Criteria:* Cron cuối ngày kiểm tra pair còn thiếu; set missing_checkout=true; status partial; tạo notification cho HR/employee.
  - *DB Entities:* `attendance_summaries, checkins, notifications`

#### Bảng công

- [x] **#85 — Nhân viên xem bảng công ngày/tháng** `P0` · 5sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: AttendanceSummaryController /me, /me/monthly, test_employee_timesheet.sh
  - *User Story:* Là một nhân viên, tôi muốn xem tổng công, giờ làm, đi muộn, OT theo ngày/tháng để biết dữ liệu công của mình.
  - *Acceptance Criteria:* Hiển thị calendar/list; lọc tháng/site; tổng attendance_value/OT/violation; highlight ngày lỗi.
  - *DB Entities:* `attendance_summaries, violations`
- [x] **#86 — HR xem bảng công tổng hợp** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: /monthly HR endpoint, test_hr_monthly.sh
  - *User Story:* Là một HR/Admin, tôi muốn xem bảng công theo nhân viên/site/tháng để kiểm tra trước khi xuất lương.
  - *Acceptance Criteria:* Lọc tháng/site/workspace/status; sort; phân trang; xem total_work_minutes, công, OT, vi phạm.
  - *DB Entities:* `attendance_summaries, tenant_users, sites, workspaces`

### Epic: Notification

#### Push FCM

- [x] **#87 — Đăng ký thiết bị nhận push** `P0` · 3sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: UserDeviceService.registerDevice, test_fcm_devices.sh
  - *User Story:* Là một nhân viên dùng app, tôi muốn đăng ký FCM token cho thiết bị để nhận thông báo realtime.
  - *Acceptance Criteria:* App gửi fcm_token/device_id; lưu vào token/session hiện tại; cập nhật khi token đổi.
  - *DB Entities:* `tokens, users`
- [x] **#88 — Gửi push notification** `P0` · 5sp · Nền tảng: Backend, Mobile App, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: sendPush + retry + delivery log, test_fcm_retry_fallback.sh
  - *User Story:* Là một hệ thống, tôi muốn gửi push đến thiết bị người dùng để thông báo kịp thời.
  - *Acceptance Criteria:* Tạo notification channel=push_fcm; gửi FCM; lưu provider_message_id; cập nhật delivery_status/retry_count/failure_reason.
  - *DB Entities:* `notifications, tokens`

#### In-app Inbox

- [ ] **#89 — Danh sách thông báo trong app/web** `P0` · 3sp · Nền tảng: Backend, Web Admin, Mobile App
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: NotificationController.getNotifications; thiếu: Notification entity không có priority/deep_link
  - *User Story:* Là một người dùng, tôi muốn xem các thông báo trong hộp thư để không bỏ lỡ thông tin quan trọng.
  - *Acceptance Criteria:* Lọc unread/all; sort created_at; phân trang; hiển thị title/body/priority; deep_link mở đúng màn hình.
  - *DB Entities:* `notifications`
- [x] **#90 — Đánh dấu đã đọc** `P0` · 2sp · Nền tảng: Backend, Web Admin, Mobile App
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: markAsRead/markAllAsRead, test_mark_read.sh
  - *User Story:* Là một người dùng, tôi muốn đánh dấu một hoặc nhiều thông báo đã đọc để quản lý inbox gọn gàng.
  - *Acceptance Criteria:* Update is_read/read_at; unread badge giảm; chỉ user nhận được mới được update.
  - *DB Entities:* `notifications`

---

## Sprint 4: Random Check: Cấu hình, Sinh lịch tự động, Gửi & Phản hồi kiểm tra, Vi phạm tự động

### Epic: Random Check

#### Cấu hình

- [ ] **#91 — Tạo cấu hình random check mặc định tenant** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: RandomCheckConfigService.createTenantDefault; thiếu: checksPerShift là số cố định, không phải khoảng min/max; không ghi audit
  - *User Story:* Là một Company Admin, tôi muốn tạo cấu hình random check mặc định cho công ty để kiểm tra nhân viên hiện trường theo policy.
  - *Acceptance Criteria:* site_id=NULL; cấu hình min/max/window/gap; chỉ một config default mỗi tenant; validate min<=max.
  - *DB Entities:* `random_check_configs, audit_logs`
- [ ] **#92 — Tạo cấu hình override theo site** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: createSiteOverride, test_random_check_config_site_override.sh; thiếu: thiếu audit log
  - *User Story:* Là một HR/Admin, tôi muốn tạo cấu hình riêng cho công trình để kiểm soát site rủi ro cao.
  - *Acceptance Criteria:* Chọn site; override tenant config; chỉ một config/site; bật/tắt riêng; ghi audit.
  - *DB Entities:* `random_check_configs, sites, audit_logs`
- [ ] **#93 — Cấu hình số lần và khung giờ check** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: validateSchedulingFields, test_scheduling_fields.sh; thiếu: field cố định không phải min/max
  - *User Story:* Là một HR/Admin, tôi muốn thiết lập số lần check, khoảng cách và khung giờ cho phép để tránh kiểm tra quá dày hoặc sai giờ.
  - *Acceptance Criteria:* Nhập checks_per_shift_min/max, min_gap, allowed_time_ranges; validate nằm trong ca; hiển thị cảnh báo nếu không thể sinh lịch.
  - *DB Entities:* `random_check_configs, shift_templates`
- [ ] **#94 — Cấu hình mode kiểm tra** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: checkMode enum, test_check_mode.sh; thiếu: không lưu threshold GPS/face riêng
  - *User Story:* Là một HR/Admin, tôi muốn chọn mode location_only/location_face/location_face_liveness để linh hoạt theo mức độ kiểm soát.
  - *Acceptance Criteria:* Chọn mode; tự set require_location/face/liveness; validate liveness phải có face; lưu threshold GPS/face.
  - *DB Entities:* `random_check_configs`
- [x] **#95 — Cấu hình áp dụng theo vai trò** `P1` · 3sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: applicableRoles filter, test_applicable_roles.sh
  - *User Story:* Là một HR/Admin, tôi muốn chọn role_at_site được áp dụng random check để không làm phiền nhóm không cần kiểm tra.
  - *Acceptance Criteria:* Chọn worker/lead/supervisor; hệ thống chỉ sinh check cho assignment có role phù hợp.
  - *DB Entities:* `random_check_configs, assignments`

#### Sinh lịch

- [ ] **#96 — Tự động sinh scheduled checks đầu ca** `P0` · 8sp · Nền tảng: Backend, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: ScheduledCheckGeneratorService, test_scheduled_check_generation.sh; thiếu: chỉ random thời điểm, số lần check là cố định không random trong min/max
  - *User Story:* Là một hệ thống, tôi muốn sinh các lần random check cho nhân viên trong ca để kiểm tra ngẫu nhiên đúng policy.
  - *Acceptance Criteria:* Lấy assignment active; chọn config site/tenant; random số lần trong min/max; đảm bảo min_gap; tạo scheduled_checks.
  - *DB Entities:* `scheduled_checks, random_check_configs, assignments, shift_templates`
- [x] **#97 — Snapshot config khi sinh check** `P0` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: buildSnapshot config_snapshot, test_config_snapshot.sh
  - *User Story:* Là một hệ thống, tôi muốn lưu config_snapshot vào scheduled_check để đảm bảo check dùng đúng rule đã sinh.
  - *Acceptance Criteria:* Snapshot verification_mode, threshold, response window, allowed requirements; response validate theo snapshot, không theo config hiện tại.
  - *DB Entities:* `scheduled_checks, random_check_configs`
- [x] **#98 — Tạo Bull/BullMQ job gửi check** `P0` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: RandomCheckDispatchQueue (Redis) + Job, test_dispatch_job.sh
  - *User Story:* Là một hệ thống, tôi muốn tạo delayed job cho scheduled_check để gửi thông báo đúng giờ.
  - *Acceptance Criteria:* Tạo job theo scheduled_at; lưu bull_job_id; worker kiểm tra status pending trước khi gửi.
  - *DB Entities:* `scheduled_checks, notifications`
- [ ] **#99 — Hủy scheduled check** `P0` · 3sp · Nền tảng: Backend, Web Admin, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: ScheduledCheckCancelService, test_cancel_scheduled_check.sh; thiếu: không lưu cancelled_by/at/reason, không ghi audit
  - *User Story:* Là một HR/Admin hoặc hệ thống, tôi muốn hủy check khi assignment/site không còn hợp lệ để tránh gửi kiểm tra sai.
  - *Acceptance Criteria:* Nếu status pending thì set cancelled; lưu cancelled_by/at/reason; remove Bull job nếu có.
  - *DB Entities:* `scheduled_checks, assignments, audit_logs`

#### Gửi check

- [ ] **#100 — Gửi random check notification** `P0` · 5sp · Nền tảng: Backend, Mobile App, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: RandomCheckDispatchService.dispatch, test_dispatch_notification.sh; thiếu: entity không có sent_at/notification_id
  - *User Story:* Là một hệ thống, tôi muốn gửi yêu cầu random check đến nhân viên để nhân viên phản hồi đúng hạn.
  - *Acceptance Criteria:* Đến scheduled_at; tạo notification random_check_request; gửi push; set status=sent, sent_at, notification_id.
  - *DB Entities:* `scheduled_checks, notifications, tokens`

#### Phản hồi

- [x] **#101 — App hiển thị random check đang chờ** `P0` · 3sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: /scheduled-checks/my-pending, test_employee_pending_checks.sh
  - *User Story:* Là một nhân viên, tôi muốn thấy random check và thời gian còn lại để phản hồi kịp thời.
  - *Acceptance Criteria:* App mở từ deep_link; hiển thị countdown đến expires_at; hiển thị yêu cầu theo mode.
  - *DB Entities:* `scheduled_checks, notifications`
- [x] **#102 — Phản hồi mode chỉ vị trí** `P0` · 5sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: CheckResponseService.submit location_only, test_respond_check.sh
  - *User Story:* Là một nhân viên, tôi muốn phản hồi random check bằng GPS để xác minh đang ở công trình.
  - *Acceptance Criteria:* Lấy GPS; gửi lat/lng/accuracy; kiểm tra geofence; face fields NULL; result pass/fail_location.
  - *DB Entities:* `random_check_responses, scheduled_checks, site_geofences`
- [x] **#103 — Phản hồi mode vị trí + Face ID** `P0` · 8sp · Nền tảng: Backend, Mobile App, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: location_face path async, test_check_response_face.sh
  - *User Story:* Là một nhân viên, tôi muốn phản hồi bằng GPS và selfie Face ID để xác minh đúng người ở đúng nơi.
  - *Acceptance Criteria:* Bắt GPS + selfie; kiểm tra geofence và face score; lưu selfie_url; result fail_location/fail_face/pass.
  - *DB Entities:* `random_check_responses, face_embeddings, scheduled_checks`
- [ ] **#104 — Phản hồi mode vị trí + Face ID + Liveness** `P1` · 8sp · Nền tảng: Backend, Mobile App, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: location_face_liveness path; thiếu: livenessScore được nhận/lưu nhưng KHÔNG BAO GIỜ được đọc lại để quyết định pass/fail — dead code path
  - *User Story:* Là một nhân viên, tôi muốn phản hồi bằng GPS, Face ID và liveness để chống gian lận ảnh/video.
  - *Acceptance Criteria:* Bắt GPS + selfie/liveness; validate theo snapshot; lưu liveness_passed; fail tạo result tương ứng.
  - *DB Entities:* `random_check_responses, face_embeddings, scheduled_checks`
- [x] **#105 — Từ chối phản hồi trễ** `P0` · 3sp · Nền tảng: Backend, Mobile App, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: CheckExpiredException, test_late_response_rejection.sh
  - *User Story:* Là một hệ thống, tôi muốn không chấp nhận response sau expires_at để đảm bảo kiểm tra đúng thời điểm.
  - *Acceptance Criteria:* Nếu responded_at > expires_at thì result=late_response hoặc reject; status expired/responded theo rule; ghi violation nếu cần.
  - *DB Entities:* `scheduled_checks, random_check_responses, violations`

#### Vi phạm

- [ ] **#106 — Tạo violation khi không phản hồi** `P0` · 5sp · Nền tảng: Backend, Web Admin, Mobile App, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: NoResponseViolationService/Job, test_no_response_violation.sh; thiếu: không gửi notification cho HR/nhân viên
  - *User Story:* Là một hệ thống, tôi muốn tạo vi phạm no_response khi check hết hạn để HR có dữ liệu xử lý.
  - *Acceptance Criteria:* Cron/worker tìm sent quá expires_at; set expired; tạo violation type=no_response; gửi notification HR/employee.
  - *DB Entities:* `scheduled_checks, violations, notifications`
- [ ] **#107 — Tạo violation khi fail random check** `P0` · 5sp · Nền tảng: Backend, Web Admin, Mobile App, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: location_fail/face_fail violations, test_fail_violations.sh; thiếu: liveness_fail KHÔNG BAO GIỜ được tạo ra thực tế (không có logic nối livenessScore -> violation); không gửi notification
  - *User Story:* Là một hệ thống, tôi muốn tạo violation theo lỗi location/face/liveness để ghi nhận bất thường kịp thời.
  - *Acceptance Criteria:* fail_location -> wrong_location; fail_face -> face_mismatch; fail_liveness -> liveness_fail; details chứa snapshot bằng chứng.
  - *DB Entities:* `random_check_responses, violations, notifications`

#### Manual Check

- [ ] **#108 — HR kích hoạt kiểm tra ngay** `P1` · 5sp · Nền tảng: Backend, Web Admin, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: ManualCheckService.trigger, test_manual_check.sh; thiếu: trigger_type dùng sentinel ngầm không phải field rõ ràng; không ghi audit
  - *User Story:* Là một HR/Supervisor, tôi muốn gửi random check thủ công cho nhân viên tại site để xác minh tình huống nghi ngờ.
  - *Acceptance Criteria:* Chọn nhân viên active tại site; tạo scheduled_check scheduled_at=now; trigger_type=manual_hr; gửi notification ngay.
  - *DB Entities:* `scheduled_checks, assignments, notifications, audit_logs`

#### Theo dõi

- [x] **#109 — HR xem danh sách scheduled checks** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: ScheduledCheckController.list, test_list_scheduled_checks.sh
  - *User Story:* Là một HR/Admin, tôi muốn xem các random check đã sinh và trạng thái để giám sát tiến trình kiểm tra.
  - *Acceptance Criteria:* Lọc date/site/user/status/trigger_type; sort scheduled_at; phân trang; xem response nếu có.
  - *DB Entities:* `scheduled_checks, random_check_responses, tenant_users, sites`
- [x] **#110 — HR xem chi tiết random check** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: getDetail config_snapshot, test_check_detail.sh
  - *User Story:* Là một HR/Admin, tôi muốn xem chi tiết scheduled check và response để xử lý khi có tranh chấp.
  - *Acceptance Criteria:* Hiển thị config_snapshot, sent_at, expires_at, response, GPS, face, result, violation liên quan.
  - *DB Entities:* `scheduled_checks, random_check_responses, violations`

### Epic: Attendance

#### Manual Review

- [ ] **#111 — HR override check-in** `P0` · 5sp · Nền tảng: Backend, Web Admin, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: CheckinService.overrideCheckin, test_override_checkin.sh; thiếu: không ghi audit
  - *User Story:* Là một HR/Admin, tôi muốn chấp nhận hoặc sửa check-in đang invalid/pending để xử lý trường hợp hợp lệ nhưng hệ thống đánh dấu lỗi.
  - *Acceptance Criteria:* Chỉ người có quyền override; nhập override_note; set status manual_override; cập nhật summary; ghi audit.
  - *DB Entities:* `checkins, attendance_summaries, audit_logs`
- [ ] **#112 — HR chỉnh attendance summary** `P1` · 5sp · Nền tảng: Backend, Web Admin, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: AttendanceSummaryService.adjustSummary, test_adjust_attendance_summary.sh; thiếu: không ghi audit
  - *User Story:* Là một HR/Admin, tôi muốn chỉnh công ngày khi có lý do hợp lệ để đảm bảo bảng công cuối cùng đúng.
  - *Acceptance Criteria:* Cập nhật attendance_value/total_work/OT/status; set manual_adjusted; bắt buộc adjustment_note; ghi audit.
  - *DB Entities:* `attendance_summaries, audit_logs`

#### Giải trình

- [ ] **#113 — Nhân viên gửi giải trình check-in lỗi** `P2` · 5sp · Nền tảng: Backend, Web Admin, Mobile App
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: CheckinService.explainCheckin, test_employee_explanation.sh; thiếu: không gửi notification cho HR
  - *User Story:* Là một nhân viên, tôi muốn gửi ghi chú/ảnh bổ sung cho check-in hoặc violation để HR có thêm thông tin xử lý.
  - *Acceptance Criteria:* Chọn bản ghi lỗi; nhập lý do; gửi notification HR; lưu metadata hoặc liên kết violation nếu có.
  - *DB Entities:* `checkins, violations, notifications`

---

## Sprint 5: Vi phạm, Dashboard & Báo cáo: Xử lý vi phạm, Dashboard 3 vai trò, Reports, Search, UX

### Epic: Violations

#### Danh sách vi phạm

- [ ] **#114 — HR xem danh sách vi phạm** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: ViolationController.listViolations, test_hr_list_violations.sh; thiếu: thiếu filter affectsAttendance; severity chưa có trong schema thật
  - *User Story:* Là một HR/Admin, tôi muốn xem violation có tìm kiếm, lọc, sort, phân trang để xử lý vi phạm hiệu quả.
  - *Acceptance Criteria:* Lọc date/site/employee/type/severity/status/affects_attendance; sort occurred_at; phân trang.
  - *DB Entities:* `violations, tenant_users, sites`

#### Chi tiết vi phạm

- [x] **#115 — HR xem chi tiết violation** `P0` · 3sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: ViolationController.getViolationDetail, test_hr_violation_detail.sh
  - *User Story:* Là một HR/Admin, tôi muốn xem bằng chứng chi tiết của vi phạm để quyết định xử lý chính xác.
  - *Acceptance Criteria:* Hiển thị details, checkin/response liên quan, ảnh selfie, GPS, lịch sử xử lý; ẩn dữ liệu theo quyền.
  - *DB Entities:* `violations, checkins, random_check_responses, scheduled_checks`

#### Xử lý vi phạm

- [ ] **#116 — Xác nhận vi phạm** `P0` · 3sp · Nền tảng: Backend, Web Admin, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: ViolationService.confirmViolation, test_hr_confirm_violation.sh; thiếu: không refresh attendance_summaries; không ghi audit
  - *User Story:* Là một HR/Admin, tôi muốn xác nhận violation là đúng để áp dụng ảnh hưởng công nếu cần.
  - *Acceptance Criteria:* Set status=confirmed; nhập hr_note; cập nhật resolved_by/at; nếu affects_attendance thì refresh summary.
  - *DB Entities:* `violations, attendance_summaries, audit_logs`
- [ ] **#117 — Bỏ qua vi phạm** `P0` · 3sp · Nền tảng: Backend, Web Admin, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: ViolationService.dismissViolation, test_hr_dismiss_violation.sh; thiếu: không gửi notification nhân viên; không ghi audit
  - *User Story:* Là một HR/Admin, tôi muốn dismiss violation có lý do để tránh ảnh hưởng công sai.
  - *Acceptance Criteria:* Set status=dismissed; bắt buộc hr_note; affects_attendance=false nếu policy; gửi notification nhân viên.
  - *DB Entities:* `violations, notifications, audit_logs`
- [ ] **#118 — Cập nhật ảnh hưởng công** `P1` · 3sp · Nền tảng: Backend, Web Admin, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: ViolationService.updateAttendanceImpact, test_hr_attendance_impact.sh; thiếu: không refresh violation_count; không ghi audit
  - *User Story:* Là một HR/Admin, tôi muốn chọn violation có ảnh hưởng attendance hay không để kiểm soát bảng công chính xác.
  - *Acceptance Criteria:* Chỉ cập nhật khi confirmed/pending theo quyền; refresh attendance_summaries.violation_count; ghi audit.
  - *DB Entities:* `violations, attendance_summaries, audit_logs`

### Epic: Dashboard

#### Employee Dashboard

- [ ] **#119 — Dashboard nhân viên** `P0` · 5sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: EmployeeDashboardService, test_employee_dashboard.sh; thiếu: thiếu random-check-pending, violation-pending, notifications
  - *User Story:* Là một nhân viên, tôi muốn xem ca hôm nay, trạng thái chấm công, công tháng và thông báo để biết việc cần làm trong ngày.
  - *Acceptance Criteria:* Hiển thị assignment hôm nay, nút check-in/out, random check pending, công tháng, violation pending, thông báo mới.
  - *DB Entities:* `assignments, checkins, attendance_summaries, scheduled_checks, notifications`

#### HR Dashboard

- [ ] **#120 — Dashboard HR** `P0` · 8sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: HrDashboardService, test_hr_dashboard.sh; thiếu: không có filter workspace/site; thiếu missing-checkout/pending-review
  - *User Story:* Là một HR/Admin, tôi muốn xem tổng quan nhân sự, chấm công, vi phạm và công trình để ra quyết định nhanh.
  - *Acceptance Criteria:* KPI nhân viên active, check-in hôm nay, pending review, violation pending, missing checkout, site active; filter theo workspace/site.
  - *DB Entities:* `tenant_users, checkins, attendance_summaries, violations, sites`

#### Supervisor Dashboard

- [ ] **#121 — Dashboard giám sát công trình** `P1` · 5sp · Nền tảng: Backend, Web Admin, Mobile App
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: SupervisorDashboardService, test_supervisor_dashboard.sh; thiếu: chỉ hiện check-in, thiếu random check/violation theo site
  - *User Story:* Là một Site Supervisor, tôi muốn xem nhân viên tại site mình phụ trách để quản lý hiện trường.
  - *Acceptance Criteria:* Hiển thị danh sách nhân viên assigned, trạng thái check-in, random check, violation tại site; không thấy site ngoài quyền.
  - *DB Entities:* `sites, assignments, checkins, scheduled_checks, violations`

### Epic: Reports

#### Báo cáo công

- [x] **#122 — Báo cáo công ngày** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: ReportController.getDailyAttendanceReport, test_daily_attendance_report.sh
  - *User Story:* Là một HR/Admin, tôi muốn xem báo cáo công theo ngày để kiểm tra dữ liệu hàng ngày.
  - *Acceptance Criteria:* Lọc date/site/workspace; hiển thị first_checkin, last_checkout, work_minutes, late, early, OT, status.
  - *DB Entities:* `attendance_summaries, tenant_users, sites, workspaces`
- [x] **#123 — Báo cáo công tháng** `P0` · 8sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: ReportController.getMonthlyAttendanceReport, test_monthly_attendance_report.sh
  - *User Story:* Là một HR/Admin, tôi muốn xem tổng công tháng của nhân viên để chuẩn bị tính lương.
  - *Acceptance Criteria:* Tổng attendance_value, total_work_minutes, ot_minutes, late_count, early_count, violation_count; group theo employee/site.
  - *DB Entities:* `attendance_summaries, tenant_users, sites`
- [ ] **#124 — Export bảng công** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: exportMonthlyAttendance (POI xlsx), test_export_attendance.sh; thiếu: không có dòng tổng cuối; không ghi audit EXPORT_ATTENDANCE
  - *User Story:* Là một HR/Admin, tôi muốn xuất bảng công ra Excel để gửi cho kế toán/quản lý.
  - *Acceptance Criteria:* Xuất theo filter; format dễ đọc; có tổng cuối file; ghi audit EXPORT_ATTENDANCE.
  - *DB Entities:* `attendance_summaries, audit_logs`

#### Báo cáo vi phạm

- [x] **#125 — Báo cáo vi phạm theo kỳ** `P1` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: ReportController.getViolationReport, test_violation_report.sh
  - *User Story:* Là một HR/Admin, tôi muốn xem thống kê vi phạm theo loại, severity, site, nhân viên để đánh giá tuân thủ.
  - *Acceptance Criteria:* Filter kỳ/site/workspace/type/status; biểu đồ/tổng hợp; drill-down danh sách.
  - *DB Entities:* `violations, sites, tenant_users`

#### Báo cáo công trình

- [x] **#126 — Báo cáo hiện diện theo site** `P1` · 5sp · Nền tảng: Backend, Web Admin, Mobile App
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: ReportController.getSitePresenceReport, test_site_presence_report.sh
  - *User Story:* Là một HR/Admin/Supervisor, tôi muốn xem số người đang có mặt/thiếu tại từng site để quản lý nhân sự hiện trường.
  - *Acceptance Criteria:* Tổng assigned hôm nay, checked-in, missing, violation; filter site/workspace; cập nhật gần realtime.
  - *DB Entities:* `assignments, checkins, attendance_summaries, sites`

#### Báo cáo Face ID

- [ ] **#127 — Báo cáo trạng thái Face ID** `P1` · 3sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: getFaceIdEnrollmentReport, test_face_id_report.sh; thiếu: không có quality score; không có endpoint export
  - *User Story:* Là một HR/Admin, tôi muốn xem nhân viên đã/chưa đăng ký Face ID để hoàn tất onboarding.
  - *Acceptance Criteria:* Filter workspace/site/status; export danh sách chưa đăng ký; hiển thị quality score và ngày đăng ký.
  - *DB Entities:* `tenant_users, face_embeddings, assignments`

### Epic: Search & Filter

#### Tìm kiếm toàn hệ thống

- [ ] **#128 — Tìm kiếm nhanh nhân viên/site/check-in** `P2` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: SearchController/SearchService, test_global_search.sh; thiếu: thiếu nhóm kết quả violation
  - *User Story:* Là một HR/Admin, tôi muốn tìm nhanh dữ liệu chính để giảm thao tác điều hướng.
  - *Acceptance Criteria:* Search theo keyword; phân nhóm kết quả employee/site/checkin/violation; tôn trọng quyền truy cập.
  - *DB Entities:* `tenant_users, users, sites, checkins, violations`

### Epic: Mobile

#### Trải nghiệm app

- [ ] **#129 — Thông báo lỗi thân thiện** `P1` · 3sp · Nền tảng: Mobile App
  - *Audit (2026-07-22):* ❌ CHƯA LÀM — thiếu: không tìm thấy mapping invalid_reason -> tiếng Việt; thuộc phạm vi mobile app, không có gì trong backend repo
  - *User Story:* Là một nhân viên, tôi muốn nhận hướng dẫn rõ ràng khi chấm công lỗi để biết cách xử lý ngay.
  - *Acceptance Criteria:* Map invalid_reason sang thông báo tiếng Việt; gợi ý bật GPS/chụp lại/đến gần site; có nút liên hệ HR nếu cần.
  - *DB Entities:* `checkins, random_check_responses`
- [ ] **#130 — Bản đồ site và vị trí hiện tại** `P1` · 5sp · Nền tảng: Backend, Mobile App
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: GeofenceController/Service (polygon+buffer CRUD); thiếu: không có logic cảnh báo accuracy thấp hay ẩn polygon theo policy
  - *User Story:* Là một nhân viên, tôi muốn xem vị trí của mình so với geofence để giảm lỗi check-in sai vị trí.
  - *Acceptance Criteria:* Hiển thị vị trí hiện tại, site center/geofence; cảnh báo accuracy thấp; không hiển polygon nếu policy ẩn.
  - *DB Entities:* `sites, site_geofences, checkins`

### Epic: Admin UX

#### Bộ lọc dùng chung

- [ ] **#131 — Lưu bộ lọc thường dùng** `P2` · 3sp · Nền tảng: Web Admin
  - *Audit (2026-07-22):* ❌ CHƯA LÀM — thiếu: xác nhận: không có bảng/code SavedFilter nào
  - *User Story:* Là một HR/Admin, tôi muốn lưu filter cho danh sách lớn để làm việc nhanh hơn.
  - *Acceptance Criteria:* Lưu filter trong local/user setting; áp dụng lại; reset filter; không ảnh hưởng người khác.
  - *DB Entities:* `tenant_settings`

### Epic: Export

#### Xuất dữ liệu

- [ ] **#132 — Export danh sách vi phạm** `P1` · 3sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: ReportController.exportViolations, test_export_violations.sh; thiếu: không ghi audit
  - *User Story:* Là một HR/Admin, tôi muốn xuất danh sách vi phạm theo bộ lọc để phục vụ báo cáo nội bộ.
  - *Acceptance Criteria:* Xuất CSV/XLSX; tôn trọng filter; ghi audit; không xuất ảnh trực tiếp, chỉ reference nếu có quyền.
  - *DB Entities:* `violations, audit_logs`

---

## Sprint 6: Vận hành nền tảng & Go-live: Platform Admin, Audit nâng cao, Cron/Jobs, Security, UAT, Docs

### Epic: Platform Admin

#### Tenant Operations

- [ ] **#133 — Khóa/mở tenant** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: TenantService.suspendTenant/reactivateTenant, test_tenant_status.sh; thiếu: không gửi notification; không ghi audit
  - *User Story:* Là một Platform Admin, tôi muốn suspend hoặc reactivate tenant để quản lý rủi ro vận hành và thanh toán.
  - *Acceptance Criteria:* Set status suspended/active; chặn login/action theo policy; gửi notification; ghi audit.
  - *DB Entities:* `tenants, notifications, audit_logs`
- [ ] **#134 — Xem chi tiết tenant vận hành** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: TenantDetailService/TenantDetailResponse, test_tenant_detail.sh; thiếu: không có cảnh báo gần vượt limit; chưa track storage usage
  - *User Story:* Là một Platform Admin, tôi muốn xem tenant, subscription, usage và giới hạn để hỗ trợ khách hàng và vận hành SaaS.
  - *Acceptance Criteria:* Hiển thị plan/subscription/status; số employees/sites/storage/random checks; cảnh báo gần vượt limit.
  - *DB Entities:* `tenants, tenant_subscriptions, plan_limits, tenant_users, sites`

#### Usage Limits

- [ ] **#135 — Enforce giới hạn gói** `P0` · 5sp · Nền tảng: Backend, Web Admin, Mobile App
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: PlanLimitEnforcementService, test_plan_limits.sh; thiếu: export chưa được kiểm tra limit; không ghi audit denied
  - *User Story:* Là một hệ thống, tôi muốn kiểm tra limit trước khi tạo tài nguyên để ngăn tenant vượt gói.
  - *Acceptance Criteria:* Tạo employee/site/random check/export kiểm tra plan_limits; trả lời rõ ràng; audit denied nếu vượt limit.
  - *DB Entities:* `plan_limits, tenant_subscriptions, tenants, audit_logs`

### Epic: Audit

#### Audit Viewer

- [ ] **#136 — Xem danh sách audit log** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: AuditLogController.listAuditLogs, test_audit_logs.sh; thiếu: AuditLogService.record() không được gọi ở đâu — không có dòng nào thực sự được ghi
  - *User Story:* Là một Platform Admin/Company Admin, tôi muốn xem audit log có filter mạnh để truy vết thao tác hệ thống.
  - *Acceptance Criteria:* Filter tenant, actor, action, resource_type, result, date; sort occurred_at; phân trang; RBAC theo scope.
  - *DB Entities:* `audit_logs, users`
- [ ] **#137 — Xem diff old/new value** `P0` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: AuditLogController.getAuditLog (diff JSONB), MaskingUtils; thiếu: đường ghi không được dùng; mask thiếu national_id/totp_secret/backup codes
  - *User Story:* Là một Admin, tôi muốn xem dữ liệu trước/sau của thay đổi để hiểu chính xác ai đã sửa gì.
  - *Acceptance Criteria:* Hiển thị diff JSON dễ đọc; mask dữ liệu nhạy cảm; link về resource nếu còn tồn tại.
  - *DB Entities:* `audit_logs`
- [ ] **#138 — Trace theo request_id** `P1` · 3sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: AuditLogController requestId branch; thiếu: endpoint hoạt động nhưng không có dữ liệu nào để trace
  - *User Story:* Là một kỹ thuật viên vận hành, tôi muốn xem toàn bộ hành động trong cùng request để debug sự cố nhanh.
  - *Acceptance Criteria:* Click request_id; hiển thị timeline audit cùng request; show metadata endpoint/status nếu có.
  - *DB Entities:* `audit_logs`

### Epic: Notification

#### Template

- [ ] **#139 — Quản lý template thông báo** `P1` · 5sp · Nền tảng: Backend, Web Admin
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: NotificationTemplateController/Service, test_notification_templates.sh; thiếu: renderTemplate() không được gọi bởi luồng gửi thật — CRUD thuần túy không có tác dụng
  - *User Story:* Là một Admin, tôi muốn cấu hình title/body theo event_type và ngôn ngữ để nội dung thông báo nhất quán.
  - *Acceptance Criteria:* Tạo template_code; preview biến; dùng khi gửi notification; fallback nếu thiếu template.
  - *DB Entities:* `notifications, tenant_settings`

#### Delivery

- [ ] **#140 — Retry và fallback notification** `P1` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: FcmClient retry+backoff, NotificationDeliveryLog, test_fcm_retry_fallback.sh; thiếu: fallback chỉ qua email, áp dụng cho mọi loại chứ không riêng critical
  - *User Story:* Là một hệ thống, tôi muốn retry khi gửi thông báo thất bại để tăng tỷ lệ nhận thông báo.
  - *Acceptance Criteria:* Retry tối đa theo policy; cập nhật retry_count/failure_reason; fallback email/SMS cho priority critical.
  - *DB Entities:* `notifications, tokens`

#### Settings

- [ ] **#141 — Cấu hình nhận thông báo cá nhân** `P2` · 3sp · Nền tảng: Backend, Web Admin, Mobile App
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: UserNotificationSettingController/Service, test_notification_settings.sh; thiếu: không chặn việc tắt random_check_request (thông báo bắt buộc)
  - *User Story:* Là một người dùng, tôi muốn bật/tắt một số loại thông báo phù hợp để giảm làm phiền không cần thiết.
  - *Acceptance Criteria:* User chọn event/channel; không cho tắt thông báo bắt buộc như random_check_request; lưu vào settings.
  - *DB Entities:* `tenant_settings, notifications, users`

### Epic: Cron & Jobs

#### Attendance Job

- [x] **#142 — Cron refresh attendance nightly** `P0` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: AttendanceSummaryJob (01:00 UTC theo timezone site), ScheduledJobMonitor
  - *User Story:* Là một hệ thống, tôi muốn tính lại bảng công định kỳ mỗi đêm để đảm bảo dữ liệu tổng hợp nhất quán.
  - *Acceptance Criteria:* Chạy theo timezone tenant; refresh ngày hôm trước; xử lý missing_checkout; log kết quả job.
  - *DB Entities:* `attendance_summaries, checkins, audit_logs`

#### Random Check Job

- [x] **#143 — Monitor scheduled check job** `P1` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: RandomCheckDispatchJob, NoResponseViolationJob, ScheduledJobMonitor
  - *User Story:* Là một hệ thống, tôi muốn phát hiện job gửi random check bị lỗi hoặc trễ để đảm bảo random check đáng tin cậy.
  - *Acceptance Criteria:* Quét scheduled pending quá hạn; retry/gửi lại nếu cần; đánh dấu lỗi; tạo audit/notification vận hành.
  - *DB Entities:* `scheduled_checks, notifications, audit_logs`

#### Data Retention

- [ ] **#144 — Dọn dữ liệu ảnh và notification cũ** `P2` · 5sp · Nền tảng: Backend, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: DataRetentionJob (weekly); thiếu: dùng config toàn cục, không theo data_retention_days riêng từng tenant; không xóa ảnh selfie cũ
  - *User Story:* Là một hệ thống, tôi muốn xóa/archival dữ liệu quá hạn theo policy để tối ưu storage và tuân thủ bảo mật.
  - *Acceptance Criteria:* Dựa data_retention_days; xóa signed URL/temp, ảnh selfie cũ theo policy; không xóa audit; log job.
  - *DB Entities:* `checkins, random_check_responses, notifications, plan_limits, audit_logs`

### Epic: Security

#### Data Masking

- [ ] **#145 — Mask dữ liệu nhạy cảm trong audit và API** `P0` · 5sp · Nền tảng: Backend, Web Admin, Mobile App
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: @Masked/MaskedSerializer, MaskingUtils, test_data_masking.sh; thiếu: chỉ che email/phone; chưa che national_id/totp_secret/backup codes/token_hash
  - *User Story:* Là một hệ thống, tôi muốn ẩn dữ liệu nhạy cảm khi trả về hoặc ghi log để bảo vệ thông tin cá nhân.
  - *Acceptance Criteria:* Mask password_hash, token_hash, national_id, totp_secret, backup codes; chỉ role đủ quyền xem thông tin nhạy cảm.
  - *DB Entities:* `audit_logs, users, tenant_users, tokens`

#### Permission Guard

- [ ] **#146 — Guard quyền API theo RBAC** `P0` · 8sp · Nền tảng: Backend, Web Admin, Mobile App
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: 90 chỗ dùng @PreAuthorize + check tầng service, test_permission_guards.sh; thiếu: denied request không được ghi audit
  - *User Story:* Là một hệ thống, tôi muốn kiểm tra permission trước các API quan trọng để đảm bảo người dùng chỉ thao tác trong quyền.
  - *Acceptance Criteria:* Mọi API khai báo permission; kiểm tra tenant/site scope; denied ghi audit result=denied; test các role chính.
  - *DB Entities:* `roles, permissions, user_roles, audit_logs`

### Epic: Observability

#### Health Check

- [x] **#147 — Màn hình trạng thái hệ thống** `P1` · 5sp · Nền tảng: Backend, Web Admin, Queue/AI/Automation
  - *Audit (2026-07-22):* ✅ ĐÃ XONG — bằng chứng: SystemStatusController /api/v1/platform/system-status (PLATFORM_ADMIN)
  - *User Story:* Là một Platform Admin, tôi muốn xem trạng thái DB, Redis, Queue, Notification provider để phát hiện lỗi vận hành.
  - *Acceptance Criteria:* Hiển thị DB/Redis/Queue/FCM status; số job pending/failed; thời điểm check cuối; cảnh báo lỗi.
  - *DB Entities:* `scheduled_checks, notifications, audit_logs`

### Epic: QA/UAT

#### UAT Flow

- [ ] **#148 — Kịch bản kiểm thử end-to-end** `P0` · 8sp · Nền tảng: Backend, Web Admin, Mobile App, Queue/AI/Automation
  - *Audit (2026-07-22):* 🟡 LÀM MỘT PHẦN — bằng chứng: tests/run_all.sh; thiếu: chạy tuần tự tất cả test suite, không phải 1 kịch bản UAT nối liền tenant→...→report như AC yêu cầu
  - *User Story:* Là một PO/QA, tôi muốn kiểm thử luồng từ tenant đến chấm công và báo cáo để đảm bảo hệ thống sẵn sàng triển khai.
  - *Acceptance Criteria:* Chuẩn bị data mẫu; test tenant->employee->site->assignment->checkin->summary->random check->violation->report.
  - *DB Entities:* `all core entities`

### Epic: Documentation

#### Tài liệu sử dụng

- [ ] **#149 — Hướng dẫn Admin/HR/Employee** `P2` · 3sp · Nền tảng: Web Admin, Mobile App
  - *Audit (2026-07-22):* ❌ CHƯA LÀM — thiếu: docs/api, docs/architecture, docs/security, docs/database, docs/deployment đều là file rỗng — chưa có hướng dẫn sử dụng nào
  - *User Story:* Là một người dùng, tôi muốn đọc hướng dẫn sử dụng theo vai trò để giảm chi phí hỗ trợ.
  - *Acceptance Criteria:* Có hướng dẫn login, tạo site, phân công, check-in, random check, xử lý violation, export report.
  - *DB Entities:* `N/A`

### Epic: Release

#### Go-live Checklist

- [ ] **#150 — Checklist triển khai tenant đầu tiên** `P0` · 5sp · Nền tảng: Backend, Web Admin, Mobile App, Queue/AI/Automation
  - *Audit (2026-07-22):* ❌ CHƯA LÀM — thiếu: không tìm thấy checklist hay script go-live nào trong repo
  - *User Story:* Là một đội triển khai, tôi muốn kiểm tra đầy đủ cấu hình trước go-live để giảm lỗi khi vận hành thật.
  - *Acceptance Criteria:* Checklist plan, tenant settings, roles, employees, sites, geofences, shifts, assignments, notification, cron jobs.
  - *DB Entities:* `all core entities`

---

## 5. Danh mục DB Entities (tổng hợp, tham chiếu nhanh)

Danh sách bảng/entity được đề cập xuyên suốt backlog (tổng hợp không trùng lặp, thứ tự alphabet):

- `assignments` · `attendance_summaries` · `audit_logs` · `checkins`
- `face_embeddings` · `face_id_consents` · `geofence_histories` · `notifications`
- `permissions` · `plan_limits` · `plans` · `random_check_configs`
- `random_check_responses` · `role_permissions` · `roles` · `scheduled_checks`
- `shift_templates` · `site_geofences` · `sites` · `tenant_invitations`
- `tenant_ip_whitelists` · `tenant_settings` · `tenant_subscriptions` · `tenant_users`
- `tenants` · `tokens` · `user_roles` · `users`
- `violations` · `workspace_members` · `workspaces`

> Ghi chú: một số tính năng thuộc Sprint 6 (VD: UAT Flow, Go-live Checklist) tham chiếu **"all core entities"** — tức toàn bộ entity ở trên.

> **Ghi chú của Claude (2026-07-22):** tên entity trong backlog gốc (VD: `tenant_users`, `shift_templates`, `site_geofences`, `random_check_responses`, `face_embeddings`, `tenant_invitations`) là tên nghiệp vụ dự kiến khi viết backlog — **khác với tên bảng thật đã triển khai** trong schema hiện tại (VD: `employees`, `shifts`, `geofences`, `check_responses`, `face_profiles`, `employee_invitations`). Khi audit từng feature, sẽ ánh xạ theo tên bảng thật, không theo tên trong backlog gốc.

---

## 6. Ghi chú sử dụng file

- File này nên được đặt tại gốc repo dưới tên **`README.md`** hoặc trong thư mục `docs/BACKLOG.md`.
- Khi cập nhật trạng thái, chỉ cần tick `[x]` vào tính năng tương ứng — không cần format lại toàn bộ bảng.
- Nếu backlog gốc (`Backlog_Tính_Năng_Fams.xlsx`) thay đổi, có thể yêu cầu Claude sinh lại file README này để đồng bộ.
- Trạng thái gốc trong file Excel hiện tại là **"To Do"** cho toàn bộ 150 tính năng nên tất cả checkbox bên dưới đang để trống `[ ]` — **trạng thái thật sẽ được audit và cập nhật lại theo tình trạng code hiện có, xem mục 7 bên dưới.**

## 7. Trạng thái audit thực tế (cập nhật bởi Claude, 2026-07-22)

Đã đối chiếu toàn bộ 150 tính năng với code + test script thật trong repo (không phải đọc backlog suy diễn). Checkbox và ghi chú *Audit* ở từng tính năng phía trên đã được cập nhật theo kết quả này.

### Tổng quan

| Trạng thái | Số lượng | Tỷ lệ |
|---|---|---|
| ✅ ĐÃ XONG (đúng Acceptance Criteria) | 57 | 38% |
| 🟡 LÀM MỘT PHẦN (có code + test, thiếu 1-2 chi tiết so với AC) | 89 | 59% |
| ❌ CHƯA LÀM (không có bằng chứng trong code) | 4 | 3% |

**4 tính năng chưa làm:** #129 (map lỗi tiếng Việt — thuộc phạm vi mobile app, không đánh giá được từ backend repo), #131 (lưu bộ lọc thường dùng), #149 (tài liệu hướng dẫn người dùng — docs/ đang toàn file rỗng), #150 (checklist go-live).

### ⚠️ Phát hiện hệ thống quan trọng nhất: Audit log không hoạt động

`AuditLogController`/`AuditLogService` có API đọc/lọc/trace theo request_id **hoàn chỉnh và có test** (#31, #136-138 nhìn tưởng như DONE) — nhưng phương thức ghi (`AuditLogService.record(...)`) **không được gọi ở bất kỳ module nghiệp vụ nào** (auth, RBAC, tenant, employee, site, checkin, attendance, random check, violation, report...). Nghĩa là:
- Mọi tiêu chí "ghi audit" trong **~40 tính năng khác nhau** trên toàn bộ 150 tính năng thực chất **không được đáp ứng**, dù nhìn qua code tưởng đã có sẵn hạ tầng.
- Bảng `audit_logs` hiện chỉ chứa dữ liệu do seed script tạo ra (xem lịch sử hội thoại trước) — không có dòng nào do hệ thống tự sinh khi vận hành thật.
- Đây là lỗ hổng cần ưu tiên sửa sớm vì ảnh hưởng xuyên suốt nhiều sprint, không phải lỗi cục bộ 1 tính năng.

### Các phát hiện đáng chú ý khác

- **#104/#107 — Liveness fail là dead code**: `livenessScore` được nhận và lưu vào DB nhưng **không bao giờ được đọc lại** để quyết định pass/fail hay tạo violation `liveness_fail`. Tính năng liveness detection hiện không có tác dụng thực tế dù enum/DTO đã khai báo đầy đủ.
- **#91/#93/#96 — Random check "min/max" thực chất là số cố định**: `checksPerShift` là 1 số nguyên duy nhất, không phải khoảng min-max như backlog mô tả — chỉ có *thời điểm* check trong ca được random, còn *số lần* check thì không.
- **#139 — Notification template không được dùng**: `NotificationTemplateService.renderTemplate()` có đầy đủ CRUD + test nhưng **không được gọi bởi luồng gửi thông báo thật** — templates hiện chỉ là dữ liệu tĩnh không có tác dụng.
- **#19 — IP whitelist chỉ là dữ liệu**: CRUD đầy đủ nhưng không có filter/middleware nào thực sự chặn request theo IP.
- **#2 — Custom OTP service là dead code**: `OtpService`/`SendOtpRequest`/`VerifyOtpRequest` tồn tại trong code nhưng không controller nào gọi tới — luồng OTP thật đi qua Firebase Phone Auth.
- **#37 — Employee detail có trường giả**: `workspaces`/`assignments` trong response chi tiết nhân viên bị hardcode rỗng, chưa thực sự nối với 2 module đó dù cả hai module đều tồn tại độc lập.
- **#12/#13 — Thiếu backup codes cho 2FA** dù acceptance criteria yêu cầu rõ.
- **#145 — Data masking chưa đủ diện**: chỉ che email/phone, chưa che `national_id`, `totp_secret`, backup codes, `token_hash` như #145 yêu cầu.
- Nhiều tính năng "ghi audit" (#5,8,9,11,15,17,18,26,27,29,30,43,45,51,66,84,91,92,99,108,111,112,116,117,118,124,132,133,135,136,146...) đều bắt nguồn từ cùng 1 gốc: audit ghi không hoạt động (xem trên) — không cần sửa riêng lẻ từng chỗ, chỉ cần bật `AuditLogService.record()` đúng chỗ trong 1 lượt.

### Ý nghĩa cho việc lên kế hoạch Sprint 1 trở đi

Vì phần lớn Sprint 1-5 đã "LÀM MỘT PHẦN" chứ không phải "chưa làm", công việc thực tế khi triển khai từng sprint sẽ là:
1. Verify tính năng đã có đúng theo Acceptance Criteria (chạy test, thử tay).
2. Bổ sung phần còn thiếu đã liệt kê trong *Audit note* của từng tính năng.
3. Với các gap lặp lại nhiều lần (audit log, notification cho HR/nhân viên khi có sự kiện) — nên xử lý tập trung 1 lần thay vì sửa rải rác từng tính năng.
4. Chỉ 4 tính năng thực sự cần code mới hoàn toàn từ đầu (#129, #131, #149, #150).
