# Kịch bản test thủ công — Đợt vá "gap còn tồn đọng" (2026-08-19)

**Nền tảng: Backend.** Không có thay đổi frontend trong đợt này.

Đợt này xử lý đúng danh sách gap đã tổng kết cho người dùng: 4 audit log thiếu (#5, #8, #9, #11),
`/auth/me` thiếu field (#10), danh sách tenant thiếu plan (#16), 2 giới hạn kỹ thuật (SMS fallback
#140, `notification_delivery_logs` không per-tenant #144), và cột endpoint/httpStatus cho audit
trace (#138). Cộng thêm dọn 13 checkbox `BACKLOG.md` cũ bị lệch so với `MANUAL_TEST_LOG.md`.

## 🔍 Phát hiện quan trọng: 4/7 gap báo cáo trước đó KHÔNG còn tồn tại
Trước khi sửa bất kỳ dòng code nào, đã cho 1 agent đọc lại toàn bộ code hiện tại của cả 5 gap kỹ
thuật. Kết quả: `LOGOUT_ALL`, `RESET_PASSWORD`, `CHANGE_PASSWORD`, `UPDATE_PROFILE` **đã có sẵn lời
gọi `auditLogService.record(...)` trong code, đã commit từ trước** (git status sạch cho các file
này) — không phải thay đổi của đợt này. Đã live-test lại (VD: gọi `PATCH /auth/me`, query
`audit_logs` thấy đúng dòng `action='UPDATE_PROFILE'`) để xác nhận không phải code chết. Đây là
lần thứ N trong dự án gặp tình trạng "báo cáo/tài liệu lỗi thời, code thực tế đã đúng" — chỉ sửa
lại ghi chú trong `BACKLOG.md`, không đụng code.

## A. Test Backend (API + DB thật)

### 1. ✅ `GET /auth/me` — thêm totpEnabled + tenant hiện tại (#10)
- `GET /api/v1/auth/me` với token thật → response có thêm `totpEnabled` (khớp `User.totpEnabled`),
  `currentTenantId`/`currentTenantName`/`currentTenantRole` (dùng đúng `PrimaryRoleResolver` mà
  luồng login dùng để chọn tenant claim cho JWT — nhất quán với tenant mà 1 lần login mới sẽ đưa
  user vào).
- Test live: user có role `TENANT_ADMIN` ở 1 tenant → response trả đúng
  `currentTenantId`/`currentTenantName`/`currentTenantRole="TENANT_ADMIN"`.
- Regression: `test_profile.sh` 3/3, `test_profile_fields_and_avatar.sh` 8/8.

### 2. ✅ `GET /tenants` (danh sách) — thêm plan/subscription (#16)
- Thêm `planName`/`planId`/`subscriptionStatus` vào từng dòng, lấy qua batch query (2 câu SQL
  thêm cho cả trang, không phải N+1 theo từng tenant).
- Test live: `GET /tenants?page=0&size=2` → cả 2 dòng đều có `planName="trial"`,
  `subscriptionStatus="TRIAL"` khớp dữ liệu subscription thật.
- Regression: `test_list_tenants.sh` 9/9, `test_create_tenant.sh` 12/12.

### 3. ✅ Audit log — endpoint + httpStatus cho trace theo request_id (#138, gap nhỏ còn sót)
- `endpoint`: tự động lấy trong chính `AuditLogService.record()` (không cần sửa ~48 call site).
- `httpStatus`: backfill sau khi response hoàn tất, qua `RequestIdFilter` — chỉ chạy UPDATE nếu
  request đó thực sự có ghi audit (tránh query thừa cho mọi request GET).
- Test live: `PATCH /tenants/{id}/settings` → `audit_logs` có đúng dòng
  `endpoint='/api/v1/tenants/{id}/settings'`, `http_status=200`. Xác nhận cả 2 field trả đúng qua
  `GET /audit-logs`.
- Regression: `test_audit_logs.sh` 14/14.

### 4. ✅ `notification_delivery_logs` theo từng tenant (#144, gap còn sót)
- Migration V109 thêm `tenant_id` (nullable, backfill dữ liệu cũ từ `notifications.tenant_id`).
  `UserDeviceService.sendPush(...)` nay nhận thêm `tenantId`, truyền xuống mọi INSERT
  (FCM attempt + email fallback thành công/thất bại). `ScheduledJobMonitor`'s ops alert (không
  thuộc tenant nào) truyền `null` — đúng ý nghĩa, không phải thiếu sót.
- `DataRetentionJob` giờ xóa delivery log theo đúng `effectiveDays` của từng tenant trong vòng lặp
  có sẵn (dùng lại field `dataRetentionDays` per-tenant từ #144 đợt 1); sweep toàn cục cũ chỉ còn
  xử lý đúng dòng `tenant_id IS NULL`.
- Test live: đăng ký fake device (push luôn fail) → gửi notification → `notification_delivery_logs`
  có đúng `tenant_id` khớp tenant của notification, cho cả 3 device attempt.
- Regression: `test_fcm_devices.sh` 13/13, `test_fcm_retry_fallback.sh` 11/11,
  `test_data_retention.sh` 7/7.

### 5. ⏸️ SMS fallback (#140) — quyết định cùng người dùng: bỏ qua đợt này
Cần tài khoản/API key nhà cung cấp SMS thật (Twilio, AWS SNS, ESMS...) mới test sống được — không
tự quyết định thêm hạ tầng mới. Người dùng chọn "Bỏ qua SMS, chỉ fix 4 gap còn lại" khi được hỏi.

## B. Dọn tài liệu (không phải bug sản phẩm)
13 mục cũ (#2, #5, #8-13, #15-19) có checkbox `BACKLOG.md` chưa tick dù `MANUAL_TEST_LOG.md` đã
ghi nhận pass từ lâu (2026-08-13). Đối chiếu lại từng dòng, xác nhận đúng là đã pass, tick lại
checkbox. #2 (Phone OTP) giữ nguyên trạng thái PARTIAL vì Mobile App thật sự chưa test luồng OTP
thật trên thiết bị (đã ghi chú sẵn trong `BACKLOG.md`, không phải lỗi tick nhầm).

## Ghi chú
Compile sạch, rebuild + redeploy `fams-api` thành công. Chạy `tests/run_all.sh` (toàn bộ 146 test
suite tự động của dự án) sau khi deploy để xác nhận không hồi quy trên diện rộng —
**kết quả: 146/146 PASS, 0 FAIL**.
