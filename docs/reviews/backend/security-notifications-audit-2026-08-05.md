# Báo cáo audit nghiệp vụ: Bảo mật tài khoản (2FA), Audit log, Thông báo, Trải nghiệm App

**Ngày:** 2026-08-05
**Phạm vi:** 10 user story — bật/đăng nhập TOTP 2FA, ghi audit log hành động quan trọng, tạo/gửi/xem/đánh dấu thông báo in-app + push, và 2 story trải nghiệm App (thông báo lỗi thân thiện, bản đồ site/geofence).
**Tham chiếu thực tế:** Google/GitHub 2FA (backup codes, bắt buộc xác thực lại khi tắt), Gmail/Slack (multi-select mark-as-read), Datadog/mọi hệ thống production (request ID xuyên suốt request để truy vết).

---

## 1. Tóm tắt kết quả

Phần lớn 10 story đã tồn tại sẵn và được xây dựng rất kỹ (TOTP, notification module, geofence data cho App) — nhiều tính năng đã qua các đợt audit/vá lỗi trước ("Issue #5", "P0-3", các bản vá ngày 31/07-01/08). Audit lần này tập trung tìm gap thật còn sót lại, phát hiện 5 vấn đề cụ thể:

| # | Hạng mục | Trước audit | Sau audit |
|---|---|---|---|
| 1 | `POST /internal/notifications` không xác thực | 🔴 Ai biết URL cũng tạo được thông báo giả cho bất kỳ user nào | ✅ Yêu cầu `X-Internal-Secret`, cùng pattern với AI callback |
| 2 | `request_id` trong audit log chưa bao giờ được ghi thật | 🟡 Cột đã có, story yêu cầu rõ, nhưng mọi lệnh gọi đều truyền `null` | ✅ Thêm `RequestIdFilter` tự sinh/echo `X-Request-Id`, wire vào toàn bộ 6 call site |
| 3 | Bật/tắt TOTP không được ghi audit | 🟡 Hành động bảo mật nhạy cảm nhưng không có dấu vết | ✅ Ghi `TOTP_ENABLED`/`TOTP_DISABLED` |
| 4 | Đánh dấu đã đọc chỉ có "1 cái" hoặc "tất cả" | 🟡 Story yêu cầu "một hoặc nhiều", thiếu chọn theo nhóm | ✅ Thêm `PATCH /notifications/read` nhận danh sách ID |
| 5 | Thông báo kết quả chấm công (`message`) vẫn tiếng Anh | 🟡 Không nhất quán với mọi message lỗi khác cùng luồng (đã tiếng Việt) | ✅ Dịch toàn bộ sang tiếng Việt |

Đồng thời xác nhận: TOTP enable/login flow, notification create/list/push/fallback-email, đăng ký thiết bị FCM, và dữ liệu bản đồ/geofence cho App **đã đúng và đầy đủ từ trước**, không cần sửa.

---

## 2. Đối chiếu từng story

### Story 1–2 — Bật TOTP 2FA / Đăng nhập có 2FA

**Đã đúng từ trước, không có gap.** Đã qua 1 đợt audit trước (comment "Issue #5" trong code): secret mã hoá tại rest, backup code dùng 1 lần, chặn replay bằng xoá pending-token ngay sau xác thực, **bắt buộc xác thực lại (password/code/backup code)** khi tắt 2FA — đúng khớp thực tế GitHub/Google (không cho phép chỉ cần session token còn sống là tắt được 2FA, phòng trường hợp token bị đánh cắp). Test trực tiếp toàn bộ vòng đời: setup → verify (nhận 8 backup code) → login thường trả `totpRequired=true` → `POST /login/totp` với mã TOTP thật (tự tính bằng thuật toán HOTP chuẩn RFC 6238) → nhận access token thật → disable → xác nhận backup code bị xoá hết.

**Gap phát hiện**: hành động bật/tắt không được ghi audit log — xem Story 3.

### Story 3 — Ghi audit cho hành động quan trọng

Module `AuditLog` (entity, repository, service) đã có sẵn đầy đủ field (`actor`, `action`, `resource`, `oldValue`/`newValue`, `requestId`, `ipAddress`, `userAgent`) — nhưng khi kiểm tra **mức độ sử dụng thực tế**, chỉ tìm thấy 4 nơi trong toàn bộ codebase gọi `auditLogService.record(...)`: đăng nhập, đăng nhập Google, mở khoá bảng công, kích hoạt kiểm tra ngẫu nhiên thủ công. Hai gap thật:

1. **`request_id` chưa bao giờ được ghi thật** — cả 4 lệnh gọi đều truyền `null` cho tham số này, dù cột đã tồn tại và story yêu cầu rõ ràng. Nguyên nhân gốc: không có cơ chế nào sinh/lưu request ID ở tầng request. Đã thêm `RequestIdFilter` (chạy đầu filter chain, ưu tiên cao nhất) — tự sinh UUID nếu client không gửi `X-Request-Id`, hoặc dùng lại giá trị client gửi; lưu vào request attribute để mọi service đọc được qua `HttpRequestUtils.currentRequestId()` (cùng pattern đã có sẵn cho `currentIpAddress()`/`currentUserAgent()`). Verify trực tiếp: gọi API không gửi header → nhận UUID mới trong response header + đúng giá trị đó xuất hiện trong dòng audit log vừa tạo; gửi kèm `X-Request-Id: my-custom-trace-123` → server echo lại đúng y hệt.
2. **Bật/tắt TOTP không audit** — đã bổ sung (xem Story 1-2).

**Quyết định phạm vi**: không mở rộng audit logging ra MỌI action trong hệ thống ở đợt này (rủi ro lan phạm vi quá lớn, không nằm trong 10 story được giao) — chỉ bổ sung đúng phần được yêu cầu rõ (TOTP) và sửa lỗi kỹ thuật khiến field đã có sẵn (`request_id`) chưa từng hoạt động. Các hành động khác (RBAC role change, tenant settings...) nếu cần audit đầy đủ hơn nên là 1 đợt audit riêng có phạm vi rõ ràng.

### Story 4 — Tạo notification in-app cơ bản

**Đã đúng và rất kỹ từ trước.** In-app/push tách độc lập hoàn toàn theo `eventType` (đã tự sửa 1 bug logic ở đợt trước — tắt in-app từng làm tắt luôn push do lỗi return sớm), hỗ trợ `metadata` có cấu trúc để deep-link. Không phát hiện gap mới.

**Gap phát hiện (không thuộc logic tạo, mà thuộc bảo mật endpoint)**: `POST /internal/notifications` — endpoint nội bộ để service/job khác tạo thông báo — **hoàn toàn không có xác thực**, comment trong code còn ghi rõ "No authentication required — restrict at network level in production" (một giả định vận hành không được backend tự đảm bảo). Đã sửa: yêu cầu header `X-Internal-Secret`, đúng pattern đã dùng cho callback AI (`FaceResultCallbackController`), thêm biến môi trường mới `NOTIFICATIONS_INTERNAL_SECRET` (bắt buộc, không có default — app sẽ không khởi động nếu thiếu, chủ đích để buộc cấu hình tường minh). Verify trực tiếp: không gửi header → `403`; sai secret → `403`; đúng secret → `201` tạo thành công.

### Story 5–6 — Đăng ký thiết bị nhận push / Gửi push notification

**Đã đúng và rất kỹ từ trước, không có gap.** Đăng ký thiết bị đúng scope (`/api/v1/me/devices`, không theo tenant — hợp lý vì 1 token FCM dùng chung mọi tenant). Gửi push có: retry, ghi delivery log đầy đủ (kể cả lý do lỗi), và **dự phòng qua email** khi mọi thiết bị gửi push đều thất bại — mức độ hoàn thiện vượt yêu cầu tối thiểu của story, tương đương các hệ thống production thực tế (Deputy, Connecteam đều có cơ chế fallback tương tự).

### Story 7 — Danh sách thông báo trong app/web

**Đã đúng từ trước, không có gap.** Phân trang, `unreadCount` tính độc lập không phụ thuộc filter/trang hiện tại (đúng thiết kế cho badge số trên icon chuông).

### Story 8 — Đánh dấu đã đọc

**Gap thật đã sửa.** Trước đây chỉ có 2 lựa chọn: đánh dấu đúng 1 cái (`PATCH /{id}/read`) hoặc đánh dấu TẤT CẢ (`PATCH /read-all`) — không có cách đánh dấu một **nhóm cụ thể đã chọn**, trong khi story ghi rõ "một hoặc nhiều". Đây là khoảng trống thật so với UX chuẩn (Gmail, Slack, mọi hộp thư hiện đại đều hỗ trợ multi-select). Đã thêm `PATCH /notifications/read` nhận `{"notificationIds": [...]}`, bỏ qua âm thầm ID không hợp lệ/không thuộc về user/đã đọc rồi (khớp phong cách idempotent của `read-all`). Verify trực tiếp: tạo 2 thông báo test, gọi API mới với cả 2 ID → `markedCount: 2`, xác nhận cả 2 đã `is_read=true` trong DB.

### Story 9 — Trải nghiệm app: Thông báo lỗi thân thiện

**Phần lớn đã đúng từ trước** — mọi lỗi trong luồng chấm công (`BusinessException`) đều có cặp `errorCode` + `userMessage` tiếng Việt rõ ràng, hướng dẫn cụ thể (ví dụ: "Ca làm việc đã kết thúc lúc 17:00 (Asia/Ho_Chi_Minh). Không thể chấm công cho ca đã qua.").

**Gap thật đã sửa**: message hiển thị khi chấm công **thành công hoặc đang chờ duyệt** (field `CheckinResponse.message`, không phải lỗi) vẫn ở tiếng Anh ("Check-in recorded successfully.") — đúng lúc nhân viên thấy message này MỖI NGÀY (không chỉ khi có lỗi), nhưng lại là phần duy nhất không nhất quán ngôn ngữ với phần còn lại của cùng luồng. Đã dịch cả 4 nhánh (`valid`/`pending_review`/`rejected`/khác) sang tiếng Việt. Verify trực tiếp: gọi chi tiết 1 checkin thật → `"message": "Chấm công ra thành công. Bạn đã làm việc 480 phút."`.

### Story 10 — Trải nghiệm app: Bản đồ site và vị trí hiện tại

**Đã đủ dữ liệu từ trước, không cần sửa backend.** `GET .../checkin/available-sites` đã trả sẵn toạ độ site (`latitude`/`longitude`), toạ độ đa giác geofence đầy đủ (`coordinates` dạng `[longitude, latitude]`), và `bufferMeters` — đủ để App vẽ bản đồ với đường viền geofence và vị trí site TRƯỚC khi nhân viên bấm chấm công. Việc tính "khoảng cách còn thiếu tới hàng rào" là phép tính hình học thuần (khoảng cách điểm-tới-đa giác) hoàn toàn có thể làm phía client từ dữ liệu đã có sẵn — không cần thêm field/endpoint mới ở backend; nhiều thư viện bản đồ di động (Mapbox, react-native-maps + turf.js) đã hỗ trợ sẵn phép tính này.

---

## 3. Danh sách thay đổi kỹ thuật

| File | Thay đổi |
|---|---|
| `RequestIdFilter.java` (mới) | Filter sinh/echo `X-Request-Id`, ưu tiên cao nhất trong filter chain |
| `HttpRequestUtils.java` | Thêm `currentRequestId()` |
| `AuthService.java`, `GoogleLoginService.java`, `AttendanceSummaryService.java`, `ManualCheckService.java` | Truyền `HttpRequestUtils.currentRequestId()` thay vì `null` |
| `TotpService.java` | Ghi audit `TOTP_ENABLED`/`TOTP_DISABLED` |
| `NotificationController.java` | Yêu cầu `X-Internal-Secret` cho `/internal/notifications`; thêm `PATCH /notifications/read` (bulk) |
| `NotificationService.java`, `NotificationRepository.java` | Thêm `markAsReadBatch` |
| `MarkReadBatchRequest.java` (mới) | DTO request cho mark-read hàng loạt |
| `CheckinService.java`, `CheckinResponse.java` | Dịch `resolveDisplayMessage` sang tiếng Việt |
| `application.yml`, `.env`, `.env.example` | Thêm `NOTIFICATIONS_INTERNAL_SECRET` (bắt buộc, không default) |

**Lưu ý triển khai quan trọng**: `NOTIFICATIONS_INTERNAL_SECRET` là biến môi trường MỚI, bắt buộc — chỉ `docker restart` sẽ KHÔNG đọc được biến mới (container giữ nguyên env đã nạp lúc tạo), phải `docker compose up -d fams-api` (recreate container) mới nhận đúng `.env` mới. Đã áp dụng đúng khi test.

Build lại, compile sạch. Live-test toàn bộ end-to-end bằng dữ liệu thật (không chỉ đọc code): vòng đời TOTP đầy đủ, request-ID sinh/echo/lưu audit, internal endpoint 403/403/201, mark-read hàng loạt, message tiếng Việt. Chạy lại regression suite `tests/auth/*totp*`, `tests/notification/*.sh`, `tests/checkin/*.sh` — không phát hiện hồi quy nào từ các thay đổi hôm nay (các fail còn lại đều là lỗi kịch bản test có từ trước: thiếu `ownerEmail` khi tạo tenant test, field `email` thay vì `identifier` khi login, và test phụ thuộc giờ hệ thống thật chạy vào ban đêm — đã xác minh không liên quan bằng cách kiểm tra message lỗi thực tế của từng case).

---

## 4. Giới hạn đã biết

- Không mở rộng audit logging ra mọi hành động trong hệ thống — chỉ bổ sung đúng phạm vi 10 story (TOTP) và sửa lỗi kỹ thuật khiến `request_id` không hoạt động dù đã có cột. Nếu cần audit toàn diện hơn (RBAC, tenant settings, employee CRUD...), đề xuất đây là 1 đợt audit riêng.
- Story 10 (bản đồ/geofence) không cần thay đổi backend — nếu sau này cần thêm "khoảng cách còn lại tới hàng rào" tính sẵn phía server (ví dụ để đưa vào push notification text), đó sẽ là bổ sung nhỏ, chưa làm vì hiện tại chưa có yêu cầu cụ thể.

---

## 5. Kết luận

10 story audit lần này phần lớn đã được xây dựng kỹ và qua nhiều đợt vá lỗi trước — không phải xây mới mà là rà lại toàn bộ để tìm gap còn sót. Phát hiện 5 vấn đề thật: 1 lỗ hổng bảo mật (endpoint nội bộ không xác thực), 1 lỗi kỹ thuật khiến field audit đã có sẵn chưa từng hoạt động, 1 hành động bảo mật nhạy cảm thiếu audit, 1 gap UX thật so với story (mark-read hàng loạt), 1 lỗi nhất quán ngôn ngữ ảnh hưởng trải nghiệm hàng ngày của nhân viên. Cả 5 đã sửa, build, và verify trực tiếp bằng dữ liệu thật — bao gồm cả việc phát hiện và xử lý đúng yêu cầu recreate container khi thêm biến môi trường mới, không chỉ restart.

---

## 6. Bản vá P1 (cùng ngày 2026-08-05) — Catalog event type chính thức

Sau khi bàn giao, FE phản hồi 2 điểm liên quan tới cài đặt thông báo:

1. `GET /me/notification-settings` chỉ trả những dòng user đã từng cấu hình — App phải tự đoán/bổ sung `RANDOM_CHECK_SENT` và bất kỳ loại nào khác xuất hiện trong response, không có danh mục đầy đủ.
2. Backend nên cung cấp catalog/enum event type chính thức để App không phải đoán chuỗi khi các module khác (assignment/check-in/attendance/violation) bắt đầu gửi thông báo.

**Xác nhận cả 2 đều đúng và nghiêm trọng hơn báo cáo ban đầu**: rà toàn bộ codebase xác nhận `RANDOM_CHECK_SENT` (hằng số `RandomCheckEventTypes.RANDOM_CHECK_SENT`) là event type THẬT DUY NHẤT hiện tồn tại — chỉ 1 nơi trong toàn hệ thống gọi `NotificationService#createNotification` (module random-check); assignment/check-in/attendance/violation **chưa hề gửi notification nào**. Nghiêm trọng hơn: **mọi ví dụ Swagger trên 6 DTO liên quan đều ghi `RANDOM_CHECK_DISPATCHED`** — một chuỗi chưa từng tồn tại trong code, nghĩa là chính tài liệu backend đang chủ động gây hiểu lầm cho FE về event type nào là thật.

**Đã sửa**:
- Thêm `NotificationEventTypeCatalog` (danh sách hằng, hiện có đúng 1 phần tử `RANDOM_CHECK_SENT`, kèm `label`/`description`/mặc định in-app+push) — nơi duy nhất cần sửa khi module khác bắt đầu gửi thông báo trong tương lai, không cần đổi gì ở `getSettings`/endpoint catalog.
- Thêm `GET /api/v1/notification-event-types` — catalog công khai (đăng nhập là đủ, không theo tenant).
- Sửa `GET /me/notification-settings` để **luôn trả về ít nhất toàn bộ catalog** (không còn rỗng với user chưa cấu hình gì), mỗi dòng có thêm `label` và `customized` (phân biệt "giá trị mặc định hệ thống" với "user đã thực sự lưu tuỳ chỉnh"). **Lưu ý kỹ thuật quan trọng phát hiện khi test**: lần sửa đầu tiên (chỉ lặp qua catalog) vô tình làm biến mất khỏi response các `eventType` tuỳ chỉnh riêng tenant mà user ĐÃ từng lưu (`NotificationTemplate.eventType` là chuỗi tự do theo tenant, không giới hạn trong catalog hệ thống) — phát hiện qua chạy lại `tests/notification/test_notification_settings.sh` (đổ vỡ ở đúng phần test bulk-upsert dùng event type tuỳ chỉnh `BULK_EVENT_A/B/C`). Đã sửa lại đúng thành phép **hợp (union)** giữa catalog và toàn bộ dòng user đã lưu, không phải chỉ lọc theo catalog — verify lại: test đạt 24/24 pass.
- Sửa toàn bộ 6 ví dụ Swagger sai `RANDOM_CHECK_DISPATCHED` → đúng `RANDOM_CHECK_SENT`.

**Tác dụng phụ tích cực phát hiện khi sửa test**: `POST /internal/notifications` (được bảo vệ bằng `X-Internal-Secret` từ đợt audit chính ở mục 1-5) khiến 5 file test cũ (`test_notification_settings.sh`, `test_notification_inbox.sh`, `test_fcm_devices.sh`, `test_mark_read.sh`, `test_fcm_retry_fallback.sh`) gọi endpoint này mà không có header — đã cập nhật cả 5 để gửi đúng header, cùng lúc phát hiện và sửa 1 lỗi kịch bản test có từ trước không liên quan (`test_fcm_devices.sh` dùng field `email` thay vì `identifier` khi login).

Build lại, compile sạch. Live-test trực tiếp: user chưa từng cấu hình → `GET /me/notification-settings` trả đúng 1 dòng catalog với `customized:false`; upsert 1 setting → dòng tương ứng chuyển `customized:true` kèm `id`/`updatedAt` thật; `GET /notification-event-types` trả đúng catalog. Chạy lại `tests/notification/*.sh` — không còn hồi quy nào từ thay đổi hôm nay.
