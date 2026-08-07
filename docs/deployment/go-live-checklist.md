# Go-live Checklist — Triển khai tenant đầu tiên

**Ngày viết:** 2026-08-06
**Đối tượng đọc:** Đội triển khai (deployment/DevOps), Platform Admin.
**Mục đích**: giảm lỗi vận hành thật do thiếu cấu hình — mỗi mục dưới đây từng là nguyên nhân của ít nhất 1 sự cố/gap phát hiện qua các đợt audit trong dự án này (dẫn nguồn cụ thể ở từng mục).

> Dùng cùng với `docs/testing/manual-test-scenarios.md` mục **B.8** (luồng UAT end-to-end từ tạo tenant tới báo cáo) — chạy trọn luồng B.8 trên môi trường staging/production TRƯỚC khi bàn giao cho khách hàng thật.

---

## 1. Biến môi trường (`.env`) — bắt buộc đầy đủ trước khi khởi động

| Nhóm | Biến | Lưu ý |
|---|---|---|
| Database | `DB_NAME`, `DB_USER`, `DB_PASSWORD` | Không dùng giá trị mặc định của môi trường dev |
| Redis | `REDIS_PASSWORD` | |
| Auth | `JWT_SECRET`, `JWT_ACCESS_TTL_MINUTES`, `JWT_REFRESH_TTL_DAYS`, `TOTP_ENCRYPTION_KEY` | `JWT_SECRET`/`TOTP_ENCRYPTION_KEY` phải là giá trị ngẫu nhiên mạnh, **khác hoàn toàn** giá trị trong `.env.example`/môi trường dev |
| Email | `GMAIL_USERNAME`, `GMAIL_APP_PASSWORD` | Dùng cho email mời nhân viên, quên mật khẩu, fallback thông báo — thiếu sẽ khiến các luồng này âm thầm không gửi được (chỉ log lỗi, không chặn API) |
| Đăng nhập Google | `GOOGLE_CLIENT_ID` | Bỏ qua nếu khách hàng không dùng đăng nhập Google |
| Push notification | `FCM_PROJECT_ID`, `FCM_SERVICE_ACCOUNT_JSON` | **Kiểm tra bằng health check** (mục 4) sau khi cấu hình — nếu sai định dạng JSON, `FcmHealthIndicator` sẽ báo `DOWN` ngay, đừng đợi user báo cáo "không nhận được thông báo" |
| Nội bộ | `AI_INTERNAL_SECRET`, `NOTIFICATIONS_INTERNAL_SECRET` | Bắt buộc, không có giá trị mặc định — app **sẽ không khởi động được** nếu thiếu (chủ đích, ép cấu hình tường minh). Phải giống hệt giá trị cấu hình phía `fams-ai`/dịch vụ gọi `/internal/notifications` |
| Storage | `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_PUBLIC_URL` | Ảnh đại diện, tài liệu đính kèm |
| CORS | `CORS_ALLOWED_ORIGIN_PATTERNS` | Phải khớp đúng domain thật của Web/App production, không để sót `localhost` trong danh sách production |
| App | `APP_BASE_URL`, `APP_FRONTEND_URL` | Dùng để build link trong email (mời nhân viên, reset password) — sai giá trị này khiến link trong email không mở được |

**Lưu ý triển khai riêng cho biến môi trường MỚI thêm sau này**: nếu deploy bằng cách chỉ restart container mà không recreate, biến môi trường mới thêm vào `.env` sẽ **không được đọc** (image/container giữ nguyên env đã nạp lúc tạo) — phải dùng lệnh recreate container (`docker compose up -d <service>`), không chỉ `restart`. Đã gặp thật trong dự án này khi thêm `NOTIFICATIONS_INTERNAL_SECRET`.

---

## 2. Database — migration và dữ liệu

- [ ] Chạy Flyway migration đầy đủ trên DB production (`flyway_schema_history` phải khớp version mới nhất trong `api-server/src/main/resources/db/migration/`).
- [ ] **Không seed dữ liệu demo** (`scripts/seed.sh`) vào DB production — script này tạo tenant/tài khoản mẫu chỉ dùng cho dev/staging.
- [ ] Nếu triển khai bằng cách nhân bản từ môi trường staging: xác nhận đã xoá sạch toàn bộ tenant demo trước khi mở cho khách hàng thật (tránh khách hàng thấy dữ liệu công ty khác trong danh sách tenant nếu vô tình có quyền Platform Admin thử nghiệm).
- [ ] Xác nhận `tenant_ip_whitelists` của khách hàng (nếu khách hàng yêu cầu chặn theo IP) đã đúng dải IP thật của họ trước khi bật `is_active=true` — bật nhầm sẽ khoá luôn chính khách hàng.

---

## 3. Tạo tenant đầu tiên đúng quy trình

- [ ] `POST /api/v1/tenants` **phải có `ownerEmail`** — thiếu field này là nguyên nhân lỗi phổ biến nhất gặp trong toàn bộ quá trình test dự án (không phải lỗi hệ thống, là lỗi thao tác).
- [ ] Gán đúng gói dịch vụ (subscription plan) đã thoả thuận với khách hàng — xác nhận giới hạn gói (`maxEmployees`/`maxSites`/`maxRandomChecksPerMonth`) đúng với hợp đồng trước khi khách hàng bắt đầu mời nhân viên hàng loạt.
- [ ] Chạy thử **toàn bộ luồng B.8** (`docs/testing/manual-test-scenarios.md`) trên chính tenant thật này trước khi bàn giao: tạo site → geofence → ca → mời nhân viên → Face ID → chấm công → báo cáo → export.

---

## 4. Kiểm tra sức khoẻ hệ thống trước khi bàn giao

Gọi `GET /api/v1/platform/system-status` (Platform Admin), xác nhận **tất cả** đều `UP`:

- [ ] `db` — kết nối PostgreSQL
- [ ] `redis` — kết nối Redis + độ trễ hàng đợi dispatch/face-verify ở mức thấp
- [ ] `fcm` — credentials Firebase hợp lệ, khởi tạo thành công
- [ ] `aiService` — dịch vụ nhận diện khuôn mặt (fams-ai) phản hồi được (bổ sung 2026-08-06 — trước đây không có cách nào biết dịch vụ này lỗi ngoài quan sát log rải rác)
- [ ] Toàn bộ job nền (`jobs[]` trong response) có `lastStatus=OK`, không có job nào `lastStatus=ERROR` hoặc chưa từng chạy lần nào

Nếu bất kỳ mục nào `DOWN`, **không bàn giao cho khách hàng** cho tới khi xử lý xong — một dịch vụ down ngay từ đầu sẽ khiến những ngày đầu vận hành thật đầy lỗi, ảnh hưởng trực tiếp tới niềm tin của khách hàng mới.

---

## 5. Kiểm tra bảo mật/phân quyền (RBAC + Data Masking)

- [ ] Xác nhận vai trò mặc định của khách hàng (Owner/Admin đầu tiên) có đúng bộ quyền kỳ vọng — không tự ý gán `PLATFORM_ADMIN` hoặc quyền xuyên-tenant cho tài khoản của khách hàng.
- [ ] Test nhanh: đăng nhập bằng 1 tài khoản HR bình thường (không giữ quyền `users:create`) → gọi `GET /employees/{id}` và `GET /employees/export` → xác nhận email/số điện thoại đều bị che (`a***@...`, `***xxx`) **giống nhau ở cả 2 nơi** — đã xác nhận qua audit 2026-08-06 rằng trước đây file Excel export bị lộ dữ liệu thô trong khi màn danh sách đã che, đã sửa nhưng nên xác nhận lại 1 lần trên môi trường thật.
- [ ] Test nhanh: dùng 1 tài khoản có vai trò ở công ty A, thử gọi API với `tenantId` của công ty B trong đường dẫn (ví dụ đổi UUID trong URL) → phải bị từ chối (403/404), không được phép thao tác dữ liệu công ty khác dù giữ đúng tên quyền.
- [ ] Nếu khách hàng có nhiều công ty con dùng chung nền tảng: xác nhận audit log của Platform Admin xem được xuyên toàn bộ, nhưng Company Admin/HR mỗi công ty chỉ xem được đúng phạm vi công ty mình.

---

## 6. Thông báo và nội dung gửi khách hàng

- [ ] Nếu khách hàng yêu cầu tuỳ chỉnh nội dung thông báo (ví dụ đổi văn phong lời nhắc kiểm tra ngẫu nhiên), cấu hình template trước ngày go-live — xác nhận template mới thực sự áp dụng bằng cách kích hoạt thử 1 kiểm tra ngẫu nhiên thật và xem nội dung nhận được đúng như đã cấu hình chưa.
- [ ] Xác nhận `GET /notification-event-types` trả đúng danh mục sự kiện hệ thống hỗ trợ — dùng làm căn cứ khi khách hàng hỏi "có thể tắt loại thông báo nào".

---

## 7. Sau go-live — theo dõi tuần đầu

- [ ] Theo dõi `GET /api/v1/platform/notifications/delivery-logs?status=FAILED` trong tuần đầu để phát hiện sớm nếu nhiều nhân viên không nhận được thông báo (token FCM sai, app chưa đăng ký thiết bị đúng cách...).
- [ ] Theo dõi số lượng vi phạm "không phản hồi kiểm tra ngẫu nhiên" bất thường trong tuần đầu — có thể là dấu hiệu cấu hình thời gian phản hồi quá ngắn so với thực tế công trường (sóng yếu, nhân viên đang thao tác tay không cầm được điện thoại...), cần điều chỉnh cấu hình thay vì để nhân viên chịu vi phạm oan.
- [ ] Xác nhận job tính lại bảng công đêm đầu tiên chạy thành công (`system-status` → `AttendanceSummaryJob` → `lastStatus=OK`) trước khi HR chốt bảng công tuần/tháng đầu tiên.

---

## 8. Rollback / phương án dự phòng

- [ ] Xác nhận có bản backup DB **trước khi** tạo tenant đầu tiên thật (không phải chỉ backup định kỳ chung) — nếu go-live gặp lỗi nghiêm trọng cần khôi phục nhanh mà không ảnh hưởng dữ liệu demo/tenant khác.
- [ ] Có kênh liên lạc trực tiếp (không qua ticket thông thường) với đội kỹ thuật trong 48 giờ đầu go-live.
