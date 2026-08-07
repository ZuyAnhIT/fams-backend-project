# Báo cáo xử lý phản hồi Frontend: System Health, Data Masking/Permission Guard, UAT/Go-live

**Ngày:** 2026-08-06
**Phạm vi:** Phản hồi P1 từ đội Web (System Health) + 3 điểm về Data Masking/Permission Guard + 2 điểm về UAT/Go-live Checklist, theo sau đợt audit `data-masking-permission-health-uat-golive-audit-2026-08-06.md` cùng ngày.

---

## 1. Tóm tắt kết quả

| # | Phản hồi FE | Đánh giá | Xử lý |
|---|---|---|---|
| 1 | `healthComponents` trộn `status` chuỗi lẫn object | ✅ Đúng, lỗi thiết kế DTO thật | Đã chuẩn hoá — bỏ key `status` dư thừa, mọi entry đồng nhất `{status, details}` |
| 2 | `jobs` chỉ trả job đã từng chạy, thiếu `NEVER_RUN` | ✅ Đúng, gap thật | Thêm catalog đầy đủ (7 job), union với DB — job chưa chạy hiện `NEVER_RUN` thay vì biến mất |
| 3 | Xác nhận Masking/Permission Guard vẫn ở Backend | ✅ Đúng như FE hiểu | Không đổi — xác nhận lại kiến trúc, không có thay đổi nào làm suy yếu điều này |
| 4 | `users:create` quá rộng cho việc xem PII | ✅ Hợp lý, đúng nguyên tắc least-privilege | Thêm permission chuyên biệt `employees:pii:read`, migrate tự động không mất quyền ai |
| 5 | Thiếu metadata `piiMasked`/`maskedFields` | ✅ Hợp lý | Thêm field `piiMasked: boolean` vào `EmployeeResponse`/`EmployeeDetailResponse` |
| 6 | Employee create/update chưa audit | ✅ Đúng, đã tự nêu trong báo cáo trước | Đã thêm audit đầy đủ (actor, old/new value đã che PII, request_id, IP, user-agent) |
| 7 | Checklist UAT không lưu được, cần model/API | ✅ Hợp lý, nhu cầu compliance thật | Xây mới module `go-live-records` (entity, migration, API đầy đủ CRUD + approve/reject) |
| 8 | System status thiếu `expectedNextRunAt`/duration/stale threshold/lịch sử | 🟡 Phần lớn hợp lý | Đã thêm `expectedNextRunAt`, `lastRunDurationMs`, `staleThresholdMinutes`, `stale`. **Lịch sử nhiều lượt chạy (không chỉ lần gần nhất) đề xuất riêng** — xem mục 4 |

---

## 2. Chi tiết xử lý

### 2.1–2.2 — System Health (P1, chặn Web dựng UI)

**`healthComponents` trộn kiểu dữ liệu**: xác nhận đúng — `flattenHealth()` cũ nhét `map.put("status", ...)` vào CHUNG map với các component `{name: {status, details}}`, tạo ra `{"status":"UP","db":{...},"redis":{...}}`. Đã sửa: bỏ hẳn key `status` cấp cao nhất (dữ liệu này dư thừa — trùng `overallHealth` đã có sẵn ở cấp response), đổi kiểu trả về từ `Map<String,Object>` sang `Map<String,HealthComponentStatus>` (DTO có kiểu rõ ràng: `{status: String, details: Map}`) — không còn khả năng lẫn kiểu dữ liệu ở tầng compile-time, không chỉ tầng runtime.

**`jobs` thiếu job chưa chạy**: xác nhận đúng, và trong lúc sửa phát hiện thêm 1 vấn đề nghiêm trọng hơn báo cáo ban đầu — `SubscriptionExpirationJob` (job tự động khoá tenant hết hạn gói, chạy hàng ngày) **chưa từng gọi `ScheduledJobMonitor`** dù đã tồn tại từ trước, nghĩa là dù chạy đúng mỗi ngày, nó vẫn hoàn toàn vô hình với `/system-status` — nếu chỉ thêm catalog mà không phát hiện+sửa job này, nó sẽ hiện `NEVER_RUN` mãi mãi dù thực ra chạy bình thường, một false-positive nguy hiểm hơn cả gap ban đầu. Đã sửa: thêm `ScheduledJobCatalog` (7 job), `SystemStatusController` union catalog với `scheduled_job_status`, và wire `SubscriptionExpirationJob` vào `jobMonitor` đúng pattern các job khác.

Nhân dịp bổ sung catalog, thêm luôn 3 field FE cũng yêu cầu ở mục 8: `lastRunDurationMs` (đo thời gian chạy thật, thêm cột DB + sửa 7 job đo giờ bắt đầu/kết thúc), `expectedNextRunAt` (tính chính xác từ biểu thức cron cho job dạng cron — dùng `CronExpression.next()` của Spring, không phải ước lượng; với job dạng fixed-rate là `lastRunAt + chu kỳ`), `staleThresholdMinutes` + `stale` (cờ phân biệt "khoẻ" với "OK nhưng đã lâu không chạy lại" — khác với `ERROR`).

Live-test: `system-status` trả đủ 7 job dù DB mới chỉ có 3 job từng chạy (4 job còn lại hiện `NEVER_RUN` kèm `expectedNextRunAt` tính đúng theo cron); job đã chạy hiện `lastRunDurationMs` thật (2-6ms cho các job random-check).

### 2.3 — Xác nhận Masking/Permission Guard ở Backend

FE nêu đúng và không cần Backend làm gì thêm ở điểm này — xác nhận lại: quyết định che PII luôn tính ở tầng `MaskedSerializer`/service, không có đường nào trả PII thô rồi để FE tự che (FE có che thì cũng chỉ là lớp hiển thị phụ, dữ liệu thô chưa từng rời khỏi Backend). Tương tự với Permission Guard — mọi service tự tra lại quyền theo `tenantId` lấy từ path, không phụ thuộc FE gửi đúng gì.

### 2.4 — Permission chuyên biệt `employees:pii:read`

Đồng ý với đề xuất của FE. Đã thêm permission mới `employees:pii:read` (migration `V86`), và **tự động backfill** cho mọi role đang giữ `users:create` tại thời điểm migrate — không role nào bị mất quyền đột ngột khi bản vá lên production. `MaskedSerializer` và `EmployeeExportService` chuyển sang kiểm tra permission mới thay vì `users:create`. Tách logic kiểm tra ra 1 class dùng chung (`PiiAccess`) để đảm bảo `MaskedSerializer` (quyết định che JSON thật) và field `piiMasked` (metadata thông báo cho FE) **luôn đồng nhất** — không có rủi ro 2 nơi tính ra 2 kết quả khác nhau.

### 2.5 — Metadata `piiMasked`

Đã thêm field `piiMasked: boolean` vào cả `EmployeeResponse` và `EmployeeDetailResponse`. Không làm dạng mảng `maskedFields: string[]` như FE gợi ý — vì hệ thống hiện chỉ có 1 quy tắc che áp dụng đồng thời cho `email`+`phone` (không có trường hợp che field này nhưng không che field kia), nên 1 cờ boolean đã đủ diễn đạt chính xác, không cần thêm độ phức tạp của mảng tên field cho một tập hợp cố định. Nếu sau này có PII field khác được che theo quy tắc riêng (ví dụ CCCD/lương với quyền khác), lúc đó mới cần nâng cấp thành mảng — chưa làm trước vì chưa có nhu cầu thật.

### 2.6 — Audit log Employee create/update

Đã thêm. Actor (`callerUserId`), `entityType="Employee"`, action (`employee_created`/`employee_updated`), `oldValue`/`newValue` (snapshot các field nghiệp vụ: mã NV, họ tên, email, phone, chức vụ, phòng ban, trạng thái, ngày vào làm — **email/phone tự động bị che** bởi `AuditLogService.record()` sẵn có, không cần code thêm gì để bảo vệ PII trong chính audit trail), `requestId`/`ipAddress`/`userAgent` lấy từ `HttpRequestUtils`. Bọc `try/catch` quanh lệnh ghi audit — lỗi ghi audit không được phép làm hỏng thao tác nghiệp vụ chính, đúng convention mọi call site audit khác trong hệ thống.

Live-test: sửa `position` của 1 nhân viên → audit log trả đúng diff `{"position": "Kỹ sư cao cấp"} → {"position": "Kỹ sư trưởng TEST"}`, các field khác giữ nguyên (không đổi), `email`/`phone` trong cả oldValue/newValue đều đã che.

### 2.7 — Model/API lưu biên bản go-live

Xây module mới `go-live-records` (entity + migration `V87` + repository + service + controller):

- **Lưu đủ mọi trường FE liệt kê**: `tenantId`, `environment`, `buildVersion`, người kiểm tra (`performedBy` — tự động lấy từ JWT lúc tạo, không cho tự khai), thời gian (`startedAt`/`completedAt`), từng bước (`steps` — JSONB array `{stepName, result, note, evidenceUrl}`), người phê duyệt (`approvedBy`/`approvedAt`/`approvalNote`).
- Vòng đời: `DRAFT` (còn sửa được `steps`) → `APPROVED`/`REJECTED` (bất biến — sửa/phê duyệt lại đều trả `409`, đúng bản chất "biên bản chính thức" không được sửa sau khi đã ký).
- Endpoint: `POST` tạo, `GET` danh sách (lọc tenant/status), `GET /{id}` chi tiết, `PATCH /{id}/steps` cập nhật khi còn draft, `POST /{id}/approve`, `POST /{id}/reject`.
- Quyền: `PLATFORM_ADMIN`/`golive:manage` (permission mới, cùng migration) — cross-tenant by nature giống mọi endpoint `/platform/...` khác.

Live-test: tạo bản ghi kèm 2 bước → `201`, `status=DRAFT`; approve kèm ghi chú → `status=APPROVED`, đúng tên người phê duyệt; thử sửa `steps` sau khi approve → đúng `409`; lọc danh sách theo `status=APPROVED` → đúng 1 kết quả.

---

## 3. Danh sách thay đổi kỹ thuật

| File | Thay đổi |
|---|---|
| `SystemStatusResponse.java` | `healthComponents` đổi kiểu sang `Map<String,HealthComponentStatus>`; `JobStatusItem` thêm `description`/`lastRunDurationMs`/`expectedNextRunAt`/`staleThresholdMinutes`/`stale` |
| `SystemStatusController.java` | `flattenHealth` bỏ key `status` dư thừa; union catalog + DB cho `jobs[]`; tính `expectedNextRunAt`/`stale` |
| `ScheduledJobCatalog.java` (mới) | Danh mục tĩnh 7 job, kèm lịch chạy + ngưỡng stale |
| `ScheduledJobStatus.java`, `ScheduledJobMonitor.java` | Thêm `lastRunDurationMs`, overload `recordSuccess`/`recordFailure` nhận duration |
| 7 file job (`AttendanceSummaryJob`, `RandomCheckDispatchJob`, `NoResponseViolationJob`, `RandomCheckSchedulerJob`, `RandomCheckQueueReconciliationJob`, `DataRetentionJob`, `SubscriptionExpirationJob`) | Đo `System.currentTimeMillis()` đầu/cuối, truyền duration; `SubscriptionExpirationJob` lần đầu wire vào `jobMonitor` |
| `V86__add_job_duration_and_pii_permission.sql` (mới) | Cột `last_run_duration_ms`; permission `employees:pii:read` + backfill từ `users:create` |
| `PiiAccess.java` (mới) | Logic kiểm tra "được xem PII thô" dùng chung cho `MaskedSerializer` và field `piiMasked` |
| `MaskedSerializer.java` | Chuyển sang `employees:pii:read` (qua `PiiAccess`) |
| `EmployeeExportService.java` | Cùng quy tắc bypass với `MaskedSerializer` |
| `EmployeeResponse.java`, `EmployeeDetailResponse.java` | Thêm field `piiMasked` |
| `EmployeeService.java` | Set `piiMasked`; thêm audit log cho `createEmployee`/`updateEmployee` |
| `V87__create_go_live_records.sql` (mới) | Bảng `go_live_records`; permission `golive:manage` |
| Module `golive/` (mới) | Entity, repository, service, controller, 4 DTO |

Build lại, compile sạch. Live-test toàn bộ bằng dữ liệu thật như mô tả ở mục 2. Chạy lại regression: `tests/employee/test_get_employee.sh`, `test_list_employees.sh`, `test_export_employees.sh`, `test_create_employee.sh`, `test_update_employee.sh` — toàn bộ pass, không hồi quy.

---

## 4. Đề xuất chưa làm — cần xác nhận

- **Lịch sử nhiều lượt chạy của job (không chỉ lần gần nhất)**: FE nêu "lịch sử job" như 1 trong 4 điểm cần bổ sung cho System Health. Đã làm 3/4 (`expectedNextRunAt`, `duration`, `staleThreshold`+`stale`) — đây là phần còn lại, giải quyết trực tiếp mục tiêu "phân biệt job khoẻ với job đã ngừng chạy lâu" (qua `stale`) mà KHÔNG cần bảng lịch sử riêng. Bảng lịch sử đầy đủ (mỗi lần chạy 1 dòng, không ghi đè) là 1 tính năng riêng — có ý nghĩa cho việc xem xu hướng/debug sâu hơn, nhưng kéo theo câu hỏi chính sách lưu trữ (giữ bao lâu, có cần dọn định kỳ như audit log không) cần xác nhận trước khi thêm bảng mới, nên chưa tự làm.

---

## 5. Kết luận

8 điểm phản hồi FE đều được xử lý hợp lý theo đúng tinh thần yêu cầu: 2 điểm P1 về System Health đã sửa xong (và phát hiện thêm 1 gap nghiêm trọng hơn — `SubscriptionExpirationJob` hoàn toàn không được giám sát — trong lúc sửa), 1 điểm chỉ cần xác nhận lại kiến trúc (không đổi code), 4 điểm về Data Masking/Audit/Go-live đã triển khai đầy đủ và live-test bằng dữ liệu thật, 1 điểm (lịch sử job) đã giải quyết phần lõi của mục tiêu qua `stale` và đề xuất phần mở rộng (bảng lịch sử riêng) cần xác nhận chính sách lưu trữ trước khi làm thêm.
