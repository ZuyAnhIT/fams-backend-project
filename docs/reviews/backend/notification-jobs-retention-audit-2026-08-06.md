# Báo cáo audit nghiệp vụ: Template thông báo, Retry/Fallback, Cài đặt cá nhân, Cron chấm công, Monitor Random Check, Data Retention

**Ngày:** 2026-08-06
**Phạm vi:** 6 user story — quản lý template thông báo theo event_type/ngôn ngữ, retry/fallback khi gửi thông báo thất bại, cấu hình nhận thông báo cá nhân, cron tính lại bảng công mỗi đêm, monitor job gửi random check, dọn dữ liệu ảnh/notification quá hạn.
**Tham chiếu thực tế:** Twilio/SendGrid (template render là một bước bắt buộc tại điểm gửi, không phải tính năng cấu hình riêng biệt không liên kết gì), Slack/GitHub (một số loại thông báo bảo mật không thể tắt hoàn toàn), AWS/GCP (self-healing reconciliation loop chạy định kỳ, không chỉ một lần lúc khởi động), mọi hệ thống production đa khách hàng (dead-letter/delivery-log phải có nơi xem, không chỉ nằm trong DB).

---

## 1. Tóm tắt kết quả

| # | Hạng mục | Trước audit | Sau audit |
|---|---|---|---|
| 1 | **Quản lý template thông báo** | 🔴 CRUD đầy đủ nhưng **hoàn toàn không có tác dụng** — sửa/xoá template không ảnh hưởng gì tới nội dung thực tế gửi đi | ✅ Đã nối vào đúng điểm gửi (`NotificationService.createNotification`) — sửa template giờ thực sự đổi nội dung |
| 2 | **Retry/Fallback** | 🟡 Đã hoạt động tốt (3 lần retry, fallback email đúng lúc) nhưng **admin không có cách xem** log gửi thất bại ngoài query DB trực tiếp | ✅ Thêm `GET /api/v1/platform/notifications/delivery-logs` (lọc theo status/channel/thời gian) |
| 3 | Cấu hình nhận thông báo cá nhân | ✅ Đã đúng và enforce đầy đủ ở điểm gửi (in-app/push độc lập) | Không đổi — chỉ ghi nhận 1 giới hạn thiết kế cần lưu ý (mục 2.3) |
| 4 | Cron refresh attendance nightly | ✅ Đã có sẵn, đúng nghiệp vụ, dùng chung logic với backfill thủ công | Không đổi |
| 5 | **Monitor scheduled check job** | 🔴 Self-healing (re-populate hàng đợi Redis) chỉ chạy **một lần lúc khởi động app** — check bị rớt khỏi hàng đợi giữa lúc app đang chạy (do Redis evict bộ nhớ) có thể **kẹt vĩnh viễn ở trạng thái `pending`**, không job monitor nào phát hiện được | ✅ Thêm job định kỳ 5 phút chạy lại đúng cơ chế self-healing đó |
| 6 | Data Retention | ✅ Đã có sẵn job dọn dẹp hàng tuần cho delivery log/notification đã đọc/embedding khuôn mặt đã thu hồi/ảnh chấm công cũ | Không đổi — 1 gap thật (ảnh khuôn mặt gốc của hồ sơ đã thu hồi) cần fix ở service AI riêng, đề xuất ở mục 4 |

**Phát hiện quan trọng nhất đợt này**: mục 1 (template hoàn toàn "trang trí", không có tác dụng thật) — và trong lúc sửa, phát hiện thêm **1 lỗi nghiêm trọng do chính bản thân bản vá gây ra** (không phải lỗi cũ), đã bắt được và sửa ngay trong cùng đợt kiểm thử trước khi bàn giao — xem mục 2.1.

---

## 2. Đối chiếu từng story

### Story 1 — Quản lý template thông báo

**Phát hiện gap nghiêm trọng nhất đợt này.** `NotificationTemplate` (entity, migration, unique key theo `tenant_id + event_type + locale`) và CRUD API (`POST/GET/PUT/DELETE /api/v1/tenants/{tenantId}/notification-templates`) đã tồn tại đầy đủ, thậm chí đã có sẵn cả bộ template mẫu qua seed data (6 event type × 2 ngôn ngữ mỗi tenant chính). Nhưng khi rà tới **điểm thực sự gửi thông báo** (`NotificationService.createNotification` — nơi DUY NHẤT tạo `Notification`/gửi push trong toàn hệ thống), phát hiện `NotificationTemplateService.renderTemplate(...)` **chưa từng được gọi ở bất kỳ đâu** ngoài định nghĩa của chính nó. Nhà cung cấp duy nhất hiện có (`RandomCheckDispatchService`) hardcode thẳng chuỗi tiếng Việt "Kiểm tra ngẫu nhiên" + body trong code Java — nghĩa là **Admin sửa/xoá template qua API không có bất kỳ tác dụng nào tới nội dung thông báo thực tế nhận được** — đúng loại lỗi "tính năng trang trí" (cosmetic feature) nguy hiểm vì Admin tưởng đã cấu hình xong nhưng thực chất chưa hề.

**Đã sửa**: nối `NotificationTemplateService` vào thẳng `NotificationService.createNotification` — đây là chốt chặn (choke point) DUY NHẤT mọi thông báo (hiện tại và tương lai) đều đi qua, nên chỉ cần sửa 1 chỗ, mọi nhà cung cấp thông báo tự động được hưởng lợi mà không cần sửa từng nơi gọi. Logic: nếu tenant có cấu hình template cho đúng `eventType + locale` (locale lấy từ `tenants.locale` của tenant, mặc định "vi" nếu tenant không có/không tìm thấy — đúng cùng mặc định với `NotificationTemplate` khi tạo), title/body của template sẽ **ghi đè** lên title/body do code truyền vào; nếu không có template nào khớp, giữ nguyên hành vi cũ (dùng text hardcode làm mặc định) — không yêu cầu phải seed template cho mọi event type mới tránh rủi ro gửi thất bại. Biến `{tên}` trong template được thay bằng đúng dữ liệu `metadata` mà nơi gọi truyền vào (ví dụ `checkId`, `siteId`, `expiresAt` của random check) — tái dùng luôn logic chuẩn hoá payload push đã có sẵn, không viết thêm cơ chế mới.

**Lỗi tự phát hiện trong lúc test (đã sửa trước khi bàn giao)**: bản vá đầu tiên gọi thẳng `renderTemplate` (bản throw exception khi không tìm thấy template) và bọc trong `try/catch` để fallback về text mặc định khi không có template — tưởng chừng an toàn, nhưng **live-test phát hiện ngay: MỌI thông báo không có template tuỳ chỉnh (tức là gần như toàn bộ hệ thống) đều lỗi 500** với `UnexpectedRollbackException`, dù `try/catch` bắt được exception logic bình thường. Nguyên nhân: đây là một "bẫy" kinh điển của Spring AOP — khi một method `@Transactional` khác (dù là nested call trong cùng transaction) ném exception, Spring đánh dấu **toàn bộ transaction vật lý là rollback-only NGAY LẬP TỨC**, trước khi exception kịp bay tới `try/catch` của method gọi nó — nghĩa là bắt được exception ở tầng Java không cứu được transaction ở tầng DB, dẫn tới lỗi "rollback silently" ngay lúc commit dù toàn bộ logic phía trên nhìn như chạy thành công. **Sửa**: đổi sang một phương thức tra cứu KHÔNG ném exception (`renderTemplateIfExists`, trả `Optional`) — loại bỏ hoàn toàn việc throw/catch giữa 2 transactional bean trong cùng 1 giao dịch. Đây chính xác là lý do quy trình "sửa → build → live-test bằng dữ liệu thật → sửa tiếp nếu phát hiện lỗi" được áp dụng nghiêm ngặt suốt các đợt audit — lỗi loại này (transaction rollback-only) hầu như không thể phát hiện chỉ bằng đọc code, chỉ lộ ra khi gọi API thật.

Live-test cuối cùng (sau khi sửa lỗi trên): tenant có locale `en`, tạo template `RANDOM_CHECK_SENT`/`vi` → gửi thông báo → đúng như thiết kế, KHÔNG áp dụng (sai locale) → fallback về text mặc định, không lỗi. Tạo tiếp template cùng `eventType`/`en` (khớp locale tenant) → gửi lại → title/body đúng theo template, thay thế hoàn toàn text mặc định của `RandomCheckDispatchService`. Test cả trường hợp event type hoàn toàn không có template nào (tenant/eventType lạ) → không lỗi, dùng đúng text mặc định của caller. Dọn sạch dữ liệu test sau khi xong. Chạy lại `tests/notification/test_notification_settings.sh` (24/24 pass) xác nhận không có hồi quy ở luồng cài đặt cá nhân — luồng này gọi chung `createNotification` nên là bài test hồi quy tốt nhất cho thay đổi này.

### Story 2 — Retry và fallback notification

**Phần lớn đã đúng và khá kỹ từ trước.** `FcmClient` retry 3 lần với backoff luỹ thừa (1s, 2s) mỗi thiết bị; `UserDeviceService.sendPush` gửi tới TẤT CẢ thiết bị active của user, chỉ kích hoạt fallback email khi **toàn bộ** thiết bị đều thất bại (đúng thiết kế — nếu 1/3 thiết bị nhận được push thành công thì không cần email, tránh làm phiền). Mỗi lần thử đều ghi `NotificationDeliveryLog` (status `SUCCESS`/`FAILED`/`FALLBACK_EMAIL_SENT`/`FALLBACK_EMAIL_FAILED`).

**Gap thật đã sửa**: dữ liệu delivery log kể trên **không có nơi nào xem được** ngoài query DB trực tiếp — không có endpoint, không có dashboard. Với 1 hệ thống production thực tế, đây chính là dữ liệu "dead-letter" quan trọng nhất để trả lời câu hỏi "tại sao user X không nhận được thông báo" khi có khiếu nại. Đã thêm `GET /api/v1/platform/notifications/delivery-logs` (PLATFORM_ADMIN, lọc theo `status`/`channel`/khoảng thời gian, phân trang) — đặt ở `SystemStatusController` cùng nhóm platform-admin hiện có (không tenant-scope được vì bản thân bảng `notification_delivery_logs` không lưu `tenant_id` — chỉ lưu `notification_id`/`device_token`). Device token được **che bớt** (chỉ hiện 6 ký tự cuối) trong response để giảm rủi ro lộ định danh thiết bị dù chỉ Platform Admin xem được. Live-test: lọc `status=FAILED` trả đúng 14 dòng, `status=SUCCESS` trả đúng 65 dòng, tổng khớp `79` dòng không filter.

**Giới hạn đã biết, không sửa trong đợt này** (nêu rõ để không gây hiểu nhầm là bỏ sót): retry hiện chạy đồng bộ (block) trong chính request/luồng đang xử lý (tối đa ~3 giây/thiết bị), không có job nền tách riêng; `NotificationDeliveryLog.status` là `String` tự do (không phải enum Java) — dễ gõ sai giá trị dù ảnh hưởng nhỏ vì có ít điểm ghi. Cả 2 đều là cải tiến kỹ thuật (không phải lỗi nghiệp vụ), có thể làm ở đợt sau nếu cần.

### Story 3 — Cấu hình nhận thông báo cá nhân

**Đã đúng và enforce đầy đủ, không cần sửa.** `GET/PUT /me/notification-settings` trả union giữa catalog hệ thống và cấu hình đã lưu của user; quan trọng hơn — đã xác nhận trực tiếp trong code rằng cấu hình này **thực sự được kiểm tra tại điểm gửi** (`isInAppEnabled`/`isPushEnabled` gọi trước khi tạo `Notification`/gửi push), và 2 kênh in-app/push được kiểm tra **độc lập** (đã có sẵn từ đợt vá lỗi trước, xem `security-notifications-audit-2026-08-05.md`).

**Giới hạn thiết kế cần lưu ý** (không phải lỗi hiện tại, vì chưa có event type nào cần, nhưng nên biết trước khi thêm event type mới): hiện KHÔNG có khái niệm event type "bắt buộc bật" — nếu sau này thêm 1 loại thông báo bảo mật thật sự quan trọng (ví dụ "phát hiện đăng nhập từ thiết bị lạ"), user có thể tắt hoàn toàn qua API hiện tại mà backend không có cơ chế chặn, khác với Slack/GitHub (luôn có nhóm thông báo bảo mật không thể tắt). **Không thêm cơ chế này ngay** vì hiện chưa có event type nào thực sự cần — thêm ngay bây giờ sẽ là thiết kế cho nhu cầu giả định, chưa có use case thật để kiểm chứng đúng/sai. Đề xuất: khi thêm event type bảo mật đầu tiên vào catalog, xử lý cùng lúc.

### Story 4 — Cron refresh attendance nightly

**Đã đúng và đầy đủ từ trước, không cần sửa.** `AttendanceSummaryJob` chạy mỗi đêm 01:00 UTC, gọi đúng `AttendanceSummaryService.recomputeForDate(hôm qua)` — dùng chung 100% công thức tính với recompute theo từng lượt chấm công (real-time) và với endpoint backfill thủ công `POST /api/v1/platform/attendance/backfill` (đã xác nhận qua code: cả 3 đường đều gọi cùng `recomputeForDate`/`recomputeForCheckin`, không có công thức SQL riêng nào lặp lại) — đúng nguyên tắc "một nguồn sự thật" tránh 3 nơi tính ra 3 kết quả khác nhau theo thời gian nếu công thức thay đổi. Có báo cáo lỗi/thành công qua `ScheduledJobMonitor` giống mọi job khác.

### Story 5 — Monitor scheduled check job

**Phát hiện gap thật thứ 2 của đợt audit này, đã sửa.** Hệ thống giám sát random-check đã khá đầy đủ từ trước: `RandomCheckJobHealthIndicator`/`RandomCheckQueueHealthIndicator` (Spring Actuator health, phát hiện job không chạy/trễ hoặc hàng đợi Redis bị chậm), `ScheduledJobMonitor` (tự động gửi FCM cảnh báo cho mọi Platform Admin khi có job lỗi), `GET /api/v1/platform/system-status` (dashboard tổng hợp) — tốt hơn dự kiến ban đầu.

**Gap thật**: hàng đợi dispatch (Redis sorted set) là nơi DUY NHẤT quyết định check nào sẽ được gửi thông báo — nhưng cơ chế tự phục hồi hàng đợi này (`RandomCheckQueueReconciliationRunner`) **chỉ chạy đúng 1 lần lúc khởi động app**. Trong khi đó, nguyên nhân THẬT SỰ khiến hàng đợi mất dữ liệu — Redis evict key do `maxmemory-policy=allkeys-lru` dưới áp lực bộ nhớ — xảy ra **trong lúc app đang chạy bình thường**, không phải lúc khởi động. Hậu quả: 1 check bị rớt khỏi hàng đợi giữa chừng sẽ kẹt vĩnh viễn ở trạng thái `pending`, không bao giờ chuyển sang `sent` — và vì `NoResponseViolationJob` chỉ quét các check ở trạng thái `sent` đã hết hạn, check kẹt ở `pending` này **hoàn toàn vô hình** với mọi cơ chế giám sát hiện có (health indicator chỉ theo dõi thời gian chạy job/độ trễ hàng đợi, không theo dõi các dòng Postgres bị mồ côi không có entry tương ứng trong Redis). Nhân viên liên quan sẽ không bao giờ nhận được yêu cầu kiểm tra ngẫu nhiên đó, và cũng không bị tính vi phạm "không phản hồi" — một lỗi hệ thống bị che giấu hoàn toàn, đúng loại rủi ro mà story "Monitor scheduled check job" yêu cầu phải phát hiện được.

**Đã sửa**: tách logic tái nạp hàng đợi ra `RandomCheckQueueReconciliationService` dùng chung, thêm `RandomCheckQueueReconciliationJob` chạy định kỳ mỗi 5 phút (cấu hình qua `fams.randomcheck.reconciliation.poll-rate-ms`, mặc định 300000ms) gọi đúng logic tự phục hồi đó — không chỉ lúc khởi động nữa. An toàn để chạy lặp lại nhiều lần (ZADD trên Redis sorted set với member đã tồn tại chỉ cập nhật score, không tạo trùng). Live-test: sau khi rebuild, job chạy ngay lúc khởi động và ghi nhận `OK` trong `scheduled_job_status`, xác nhận qua `system-status` endpoint.

### Story 6 — Data Retention (dọn dữ liệu ảnh và notification cũ)

**Đã có sẵn `DataRetentionJob` khá đầy đủ, chạy hàng tuần (Chủ nhật 3h sáng)**: dọn delivery log cũ (30 ngày), notification ĐÃ ĐỌC cũ (90 ngày — chủ đích không đụng vào notification CHƯA đọc dù cũ, tránh mất thông tin user chưa kịp xem), xoá embedding khuôn mặt của hồ sơ đã bị thu hồi (`revokedAt != null`), và dọn ảnh chấm công/random-check cũ (30 ngày, gọi qua AI service).

**Đính chính (2026-08-06, sau khi người dùng hỏi lại)**: kết luận ban đầu ở đây SAI — lúc đầu tôi chỉ đọc phía Java gọi sang (`AiServiceClient.deleteEmbedding` → `DELETE /embeddings/{id}`) mà không lật sang xem code Python (`ai-service/app/routers/enroll.py`) để xác nhận. Sau khi đọc trực tiếp: endpoint `DELETE /enroll/{employeeId}` (gọi bởi `AiServiceClient.revokeFace`, kích hoạt **NGAY LÚC** admin thu hồi hồ sơ, đồng bộ — không phải job chạy sau) đã gọi `storage_service.delete_enrollment_photos()` ngay dòng đầu tiên — xoá thật (`shutil.rmtree`) toàn bộ thư mục ảnh enrollment của nhân viên đó trên đĩa (`ai-service/storage/enrollments/{tenantId}/{employeeId}/`), cùng lúc với việc cập nhật `status='revoked'` trong DB. `DELETE /embeddings/{id}` mà tôi tưởng là cơ chế xoá chính chỉ là lớp dự phòng chạy hàng tuần cho embedding (không phải ảnh) trong trường hợp lệnh revoke đồng bộ ở trên bị lỗi mạng/timeout tại thời điểm đó — có docstring ghi rõ "Self-healing counterpart to DELETE /enroll/{employee_id}".

**Kết luận đúng: KHÔNG có gap** — ảnh khuôn mặt gốc đã được xoá thật ngay lúc thu hồi, đồng bộ, không cần chờ job dọn dẹp hàng tuần. Cách làm này tốt hơn nhiều hệ thống thực tế (xoá đồng bộ ngay lúc thu hồi, thay vì chỉ dựa vào batch job định kỳ — đáp ứng tốt hơn yêu cầu kiểu GDPR/BIPA "xoá không trì hoãn bất hợp lý" sau khi rút đồng ý). Mục 4 dưới đây (đề xuất "xoá ảnh enrollment khi thu hồi") bị rút lại — không còn là gap thật.

**Lưu ý về kiến trúc, để tránh hiểu lầm đã xảy ra**: `ai-service` không phải bên thứ 3/repo ngoài — nó là thư mục con `ai-service/` ngay trong CHÍNH repo này, build từ Dockerfile trong repo, chạy container nội bộ `fams-ai` cùng docker-compose với `fams-api`/`fams-postgres`, giao tiếp qua HTTP nội bộ + secret riêng (`AI_INTERNAL_SECRET`), không đi qua internet, không gửi dữ liệu ra ngoài. Tách Java/Python là kiến trúc microservice bình thường (Python cho xử lý ML khuôn mặt), không phải rủi ro bảo mật. Ảnh gốc lưu trên đĩa local của container `fams-ai`; vector embedding lưu trong cùng cụm PostgreSQL dùng chung toàn hệ thống — không có kho dữ liệu bên ngoài nào khác.

---

## 3. Danh sách thay đổi kỹ thuật

| File | Thay đổi |
|---|---|
| `NotificationService.java` | Nối template rendering vào `createNotification` (chốt chặn duy nhất mọi thông báo đi qua); thêm `resolveLocale` (đọc `tenants.locale`, mặc định "vi") |
| `NotificationTemplateService.java` | Thêm `renderTemplateIfExists` (không throw exception) — dùng thay `renderTemplate` khi gọi từ trong 1 transaction khác, tránh bẫy rollback-only của Spring AOP |
| `NotificationDeliveryLogRepository.java` | Thêm `JpaSpecificationExecutor` |
| `NotificationDeliveryLogSpecification.java` (mới) | Lọc theo status/channel/khoảng thời gian |
| `NotificationDeliveryLogResponse.java` (mới) | DTO response, che device token |
| `SystemStatusController.java` | Thêm `GET /api/v1/platform/notifications/delivery-logs` |
| `RandomCheckQueueReconciliationService.java` (mới) | Tách logic tái nạp hàng đợi Redis từ pending rows, dùng chung cho cả runner lúc khởi động và job định kỳ |
| `RandomCheckQueueReconciliationRunner.java` | Refactor để gọi service dùng chung, không đổi hành vi lúc khởi động |
| `RandomCheckQueueReconciliationJob.java` (mới) | Job định kỳ 5 phút, tự phục hồi hàng đợi khi app đang chạy (không chỉ lúc khởi động) |

Build lại, compile sạch. Live-test toàn bộ bằng dữ liệu thật như mô tả ở từng mục trên (bao gồm cả việc tự phát hiện và sửa lỗi rollback-only trước khi bàn giao). Chạy lại `tests/notification/test_notification_settings.sh` (24/24 pass, không hồi quy). 2 script khác trong cùng thư mục (`test_notification_inbox.sh`, `test_mark_read.sh`) fail ở bước setup vì lỗi kịch bản test có từ trước (thiếu `ownerEmail` khi tạo tenant) — đã xác minh không liên quan tới thay đổi hôm nay bằng cách đọc đúng payload lỗi.

---

## 4. Đề xuất nâng cấp (cần xác nhận trước khi làm)

- ~~Xoá ảnh enrollment khuôn mặt gốc khi hồ sơ bị thu hồi~~ — **rút lại, không còn là đề xuất**: đã xác nhận trực tiếp trong code Python (`ai-service/app/routers/enroll.py`, hàm `revoke_face`) rằng ảnh gốc ĐÃ được xoá thật, đồng bộ, ngay lúc thu hồi — xem đính chính ở Story 6 phía trên. Kết luận ban đầu của tôi (dựa trên chỉ đọc code Java gọi sang, không lật sang xem code Python thực thi) là sai.
- **Retention policy theo từng tenant** (không phải toàn hệ thống): hiện `deliveryLogDays`/`notificationDays`/`biometricPhotoDays` là hằng số toàn hệ thống, không cấu hình được riêng theo tenant (ví dụ tenant trả phí cao muốn giữ dữ liệu lâu hơn). Đây là thay đổi chính sách/gói dịch vụ (business decision), cần xác nhận trước khi thêm field vào `TenantSettings`.
- **Event type "bắt buộc bật"** cho thông báo bảo mật (Story 3): chưa cần ngay vì catalog hiện chưa có event type nào thuộc loại này — làm cùng lúc khi thêm event type bảo mật đầu tiên.

---

## 5. Kết luận

6 story đợt này chia làm 2 nhóm: 2 story (cấu hình cá nhân, cron chấm công đêm) đã đúng và đầy đủ, xác nhận không cần sửa. 4 story còn lại đều phát hiện gap thật, nghiêm trọng nhất là template thông báo — CRUD tồn tại nhưng không có tác dụng thật, đã sửa bằng cách nối vào đúng 1 chốt chặn duy nhất mọi thông báo đi qua, và quan trọng không kém, **tự phát hiện và sửa một lỗi nghiêm trọng do chính bản vá đầu tiên gây ra** (transaction rollback-only) trước khi bàn giao, nhờ quy trình live-test bắt buộc bằng dữ liệu thật thay vì chỉ đọc code. Gap thứ 2 (self-healing hàng đợi random-check chỉ chạy lúc khởi động) là một lỗ hổng giám sát âm thầm — nhân viên có thể không bao giờ nhận được yêu cầu kiểm tra ngẫu nhiên mà không ai biết — đã sửa bằng job định kỳ. Gap thứ 3 (dead-letter delivery log không có nơi xem) đã có endpoint admin mới. 1 đề xuất (xoá ảnh enrollment gốc) cần phối hợp với AI service, đã nêu rõ ở mục 4 để xác nhận trước khi triển khai.
