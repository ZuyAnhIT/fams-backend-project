# Sprint 1 — Mobile App review

## #1 — Đăng nhập email/mật khẩu

**Trạng thái: ✅ Đã sửa xong (2026-07-22, kiểm chứng qua Playwright thật trên Expo Web `localhost:8082`)**

### Đã sửa
1. **Đã xóa bộ auth cũ chết** (`services/auth.service.ts`, `store/auth.store.ts`, `types/auth.type.ts`, `hooks/use-auth.ts`) — xác nhận chắc chắn bằng grep toàn bộ `app/`+`src/`, không còn chỗ nào import, `tsc`/`lint` sạch sau khi xóa.
2. **Đã thêm `deviceId`** vào request đăng nhập email/password (`loginWithEmail` trong `api.ts`) — tái dùng đúng `getDeviceId()` đã có sẵn (đang dùng cho Google login), trước đây chỉ có Google login gửi deviceId còn login thường thì không.
3. **403 TENANT_SUSPENDED** giờ hiển thị đúng message tiếng Việt — sửa tận gốc ở `parseAuthError()` (`utils.ts`): ưu tiên field `userMessage` (luôn tiếng Việt) từ backend thay vì đoán riêng theo từng status code. Cũng sửa luôn lỗi cũ: sai mật khẩu (401) và email chưa xác thực (403) trước đó hiện nguyên văn tiếng Anh do đọc nhầm field `message` thay vì `userMessage`.
4. Sập toàn bộ Expo Web build do `react-native-maps` import tĩnh trong `SiteDetail.tsx` — tách thành `SiteLocationMap.tsx`/`.web.tsx`.
5. `expo-secure-store` không hỗ trợ web (stub rỗng) khiến đăng nhập thành công vẫn crash — thêm `secure-storage.ts`/`.web.ts`.
6. Backend chưa có CORS — chặn hoàn toàn API call từ Expo Web — thêm CORS config phía backend.

### Đã test thật qua Playwright (không chỉ đọc code)
Đúng mật khẩu, sai mật khẩu, form rỗng, tài khoản khóa (423, banner đếm ngược đẹp — làm mẫu tốt hơn Web), email chưa xác thực (403), luồng TOTP/2FA đầy đủ (mã sai → mã đúng → vào Home) — tất cả hiển thị đúng tiếng Việt.

### Còn lại (không thuộc phạm vi #1, ghi nhận cho sau)
- Chưa có test suite tự động — biết trước (`CLAUDE.md`), ngoài phạm vi.
- `UserProfile.role` luôn fallback `'employee'` vì `/auth/me` không trả `role` và `VALID_ROLES` không khớp tên role thật của backend (`TENANT_ADMIN`, `HR_MANAGER`...) — chưa gây lỗi vì chưa màn hình nào rẽ nhánh theo `role`, cần sửa trước khi có tính năng phân quyền UI trên mobile.
- Đăng ký tài khoản mới có thể trả 500 nếu quota gửi email Gmail hết hạn mức/ngày — lỗi hạ tầng ngoài code, xem chi tiết ở `docs/reviews/web/sprint-1.md`.

### Code hiện tại
- Route: `app/(auth)/login.tsx` (tab email/phone) → `LoginForm` (`src/features/auth/components/LoginForm.tsx`).
- API call: `loginWithEmail()` (`src/features/auth/api.ts:42-45`) → `POST /auth/login`.
- Token lưu ở `expo-secure-store` (`src/features/auth/store.ts:25-29`, đúng chuẩn bảo mật cho mobile — không dùng AsyncStorage thường cho token).
- TOTP: điều hướng route thật `router.push('/(auth)/2fa-verify')` khi `requires_2fa && temp_token` (`hooks/use-login.ts:26-31`).
- Xử lý lỗi 423 rất tốt: `parseAuthError()` (`utils.ts:26-97`) có nhánh riêng đọc `locked_until`, hiển thị đếm ngược qua `AccountLockedBanner` — **tốt hơn Web Admin**, nên dùng làm mẫu khi sửa Web.

### Vấn đề phát hiện
1. **Tồn tại 2 bộ auth song song** trong `src/features/auth/`: bộ cũ (`services/auth.service.ts`, `store/auth.store.ts`, `types/auth.type.ts`, `hooks/use-auth.ts`) có vẻ là code chết không còn được dùng, và bộ đang thực sự chạy (`api.ts`, `store.ts`, `types.ts`, `session.ts`, `utils.ts`, `hooks/use-login.ts`). Cần xác nhận với bạn: xóa bộ cũ hay đang dùng dở cho việc gì khác?
2. **Không gửi `deviceId`** khi login (`types.ts:3-6` chỉ có `{ email, password }`), trong khi Web Admin có gửi. Nếu nghiệp vụ cần theo dõi thiết bị đăng nhập (vd để hỗ trợ "đăng xuất khỏi thiết bị hiện tại" — Feature #4 — phân biệt đúng thiết bị), mobile hiện không cung cấp thông tin này.
3. **Xử lý 403 chung chung**: switch trong `parseAuthError` (dòng 75-96) map 403 → "Tài khoản không có quyền truy cập" — không phân biệt `TENANT_SUSPENDED` với các lỗi 403 khác (RBAC). Cùng loại gap như Web, nhưng ở đây hạ tầng xử lý lỗi đã tốt sẵn (theo `error_code`), chỉ cần thêm 1 case nữa là xong — dễ sửa hơn Web.
4. **Không có test nào** (đã biết trước qua `CLAUDE.md` của project — không có script `test`).

### Đề xuất sửa
1. Thêm nhánh `error_code === 'TENANT_SUSPENDED'` vào `parseAuthError()` với message riêng — tái dùng đúng pattern đã có cho `ACCOUNT_LOCKED`.
2. Thêm `deviceId` vào request login — cần bạn xác nhận nguồn giá trị (`expo-device`/`expo-constants` sinh ID ổn định, hay dùng ID đã có sẵn ở đâu đó trong app cho push notification).
3. Hỏi bạn về bộ auth cũ trước khi xóa — không tự ý xóa code khi chưa chắc chắn không ai còn tham chiếu.

### Roadmap test thủ công trên Expo Go (Metro đang chạy tại `http://localhost:8082`)
1. Đăng nhập đúng → vào màn hình chính, kiểm tra token đã lưu (không thể xem trực tiếp SecureStore qua DevTools như web, nhưng có thể kiểm tra qua việc tắt/mở lại app vẫn giữ phiên đăng nhập nhờ `hydrateFromSecureStore()`).
2. Sai mật khẩu 5 lần → xác nhận banner khóa tài khoản hiện đúng thời gian đếm ngược.
3. Đăng nhập tài khoản `dong-a-jsc` (suspended) → hiện tại: message chung "không có quyền truy cập"; sau khi sửa: message rõ ràng doanh nghiệp bị tạm dừng.
4. Bật TOTP cho 1 tài khoản test → xác nhận điều hướng đúng sang màn `2fa-verify`.

### Cập nhật 2026-07-23 — Sửa lỗi độ bền gửi email (theo yêu cầu riêng, ảnh hưởng cả Đăng ký/Quên mật khẩu)
Cùng lỗi/cùng sửa như phía Web Admin (xem `docs/reviews/web/sprint-1.md`) — backend `EmailService` giờ không hủy giao dịch khi gửi mail lỗi, và chạy nền (`@Async`) nên API trả nhanh. Đã kiểm chứng thật qua Playwright trên Expo Web: đăng ký (`/register`) và quên mật khẩu (`/forgot-password`) đều chạy đúng, nhanh, kể cả khi Gmail dev vẫn đang bị chặn quota.

## #2 — Đăng nhập bằng số điện thoại OTP

**Trạng thái: 🟡 Code đã sửa đúng — chờ Firebase project thật + 1 lần build EAS để test live (2026-07-23)**

Cùng vấn đề như Web Admin: `phone-login.tsx` gọi `/auth/otp/send`/`/auth/otp/verify` với shape sai hoàn toàn (`{phone,code}` thay vì `{firebaseIdToken}`), chưa tích hợp Firebase SDK. Theo quyết định của bạn, đã chuyển sang `@react-native-firebase/auth` (yêu cầu build qua EAS dev-client, không chạy được trên Expo Go thường — `eas.json` đã có sẵn profile `development`). Đã sửa `phone-login.tsx`, `use-phone-otp.ts`, `api.ts`, `types.ts` theo đúng contract thật. **Phát hiện & tự sửa ngay**: thêm `@react-native-firebase/auth` làm sập toàn bộ Expo Web build (giống lỗi `react-native-maps` ở #1) — đã tách `use-firebase-phone-auth.ts` (native) + `.web.ts` (stub báo "chỉ hỗ trợ trên ứng dụng di động") để không ảnh hưởng việc test các tính năng khác qua Expo Web trong các phiên sau. Cần bạn: cung cấp `google-services.json`/`GoogleService-Info.plist`, và chạy `eas build --profile development` một lần để có dev-client cài lên máy thật/simulator — @react-native-firebase không thể test qua Expo Go hay Expo Web. Chi tiết đầy đủ: `docs/BACKLOG.md` mục #2.
