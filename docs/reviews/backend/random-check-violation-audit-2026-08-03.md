# Báo cáo audit nghiệp vụ: Random Check / Vi phạm / Điều chỉnh chấm công / Giải trình nhân viên

**Ngày:** 2026-08-03, cập nhật 2026-08-04
**Phạm vi:** 8 user story do người dùng cung cấp, liên quan tới liên kết dữ liệu giữa Quản lý nhân viên, Face ID, Ca làm, Phòng ban, Công trình, Chấm công, Random Check và Vi phạm. Bản cập nhật 2026-08-04 xử lý thêm 3 mục tồn đọng (mục 7 cũ) sau khi có xác nhận từ người dùng.
**Tham chiếu thực tế:** Deputy (Random/Spot check + Exception workflow), QuickBooks Time (GPS/geofence check-in + timesheet review), Connecteam (Live GPS tracking + violation resolution flow).

---

## 0. Cập nhật 2026-08-04 — xử lý 3 mục tồn đọng

| # | Vấn đề | Xử lý |
|---|---|---|
| 1 | UX gộp 2 form giải trình (checkin pending_review + violation unresolved) | Thêm `GET /api/v1/tenants/{tenantId}/me/exceptions` — gộp đọc (read) 2 nguồn thành 1 danh sách duy nhất, sort theo thời gian, kèm sẵn `explainEndpoint` cho từng item để FE gọi đúng API nộp giải trình tương ứng. Hai luồng ghi (POST .../checkin/{id}/explain và POST .../violations/{id}/explain) **vẫn tách biệt** — đây là hợp lý vì 2 loại bản ghi khác nhau về bản chất, chỉ hợp nhất phần đọc. |
| 2 | Kích hoạt thủ công không giới hạn số lần/ngày | **Giữ nguyên không rate-limit cứng** (đúng chủ đích, khớp Deputy/QuickBooks Time). Bổ sung: (a) audit log mỗi lần kích hoạt thủ công (entity `ScheduledCheck`, action `manual_random_check_triggered`, ghi employeeId/siteId/checkMode/reason/số lần trong ngày); (b) trường `manualTriggerCountToday` trong response để FE hiển thị cảnh báo mềm, không chặn. |
| 3 | Race condition: Face ID bị thu hồi (revoke) giữa lúc callback xác thực khuôn mặt đang xử lý | Thêm guard tại `FaceResultCallbackController`: khi callback báo `faceVerified=true`, hệ thống đọc lại trạng thái Face ID **tại thời điểm nhận callback** (không dùng lại kết quả đã kiểm tra lúc check-in); nếu hồ sơ không còn `enrolled`, hạ check-in về `pending_review` thay vì tin vào kết quả khớp đã lỗi thời — không tạo violation (đây là vấn đề thời điểm hệ thống, không phải bằng chứng nhân viên sai). |

Cả 3 mục đã build lại (`docker restart fams-api`, xác nhận compile sạch — 404 file, không lỗi), và verify trực tiếp trên dữ liệu thật:
- `/me/exceptions`: test với nhân viên có cả checkin pending_review và violation unresolved — trả về danh sách gộp đúng, sort mới nhất trước.
- Kích hoạt thủ công 2 lần liên tiếp cho cùng nhân viên/ngày: `manualTriggerCountToday` tăng đúng 1→2, audit log ghi đủ 2 dòng với `triggerCountToday` khớp.
- Race condition: revoke Face ID → gọi callback giả lập `faceVerified=true` → check-in hạ đúng xuống `pending_review`, không tạo violation; sau khi khôi phục `enrolled` và gọi lại callback, check-in giữ nguyên `valid` như bình thường (xác nhận không có false positive khi Face ID vẫn hợp lệ).
- Toàn bộ dữ liệu test (2 scheduled_checks + audit log thủ công, trạng thái tạm thời của face_profiles/checkins dùng để mô phỏng race condition, các tenant IP-whitelist tạm tắt để test) đã được khôi phục về đúng trạng thái ban đầu sau khi verify xong.

*Lưu ý phát sinh ngoài phạm vi 3 mục trên*: trong lúc rebuild lần này phát hiện `fams-postgres` và `fams-minio` container đã bị crash từ trước (exit code 127, do lỗi mount bind-mount của Docker Desktop trên WSL2 — không liên quan tới code), khiến `fams-api` không khởi động được. Đã `docker start` lại cả 2 container, xác nhận healthy, sau đó `fams-api` khởi động thành công bình thường — không phải lỗi do các thay đổi trong audit này.

---

## 1. Tóm tắt kết quả

| # | Hạng mục | Trước audit | Sau audit |
|---|---|---|---|
| 1 | HR Manager truy cập checkin/random-check | 🔴 Thiếu quyền — 403 toàn bộ endpoint | ✅ Đã cấp đủ 7 quyền (V83) |
| 2 | Random check yêu cầu Face ID khi nhân viên chưa đăng ký | 🔴 Sinh `face_fail` giả, không thể vượt qua | ✅ Tự động hạ cấp về `location_only` khi chưa có Face ID `enrolled` |
| 3 | Vi phạm bị tạo trùng lặp khi face callback gọi lại | 🟡 Có nguy cơ trùng (không có guard) | ✅ Đã thêm idempotency guard |
| 4 | Vi phạm bị HR dismiss nhưng cờ `hasRandomCheckFailure` không cập nhật | 🔴 Dữ liệu không nhất quán giữa Violation và AttendanceSummary | ✅ Tự động recompute sau khi confirm/dismiss |
| 5 | Không thể tra cứu vi phạm gắn với 1 scheduled-check cụ thể | 🟡 Thiếu liên kết 2 chiều | ✅ Thêm `violations[]` vào chi tiết scheduled-check + filter `scheduledCheckId` trên `GET /violations` |
| 6 | Nhân viên không tự xem được vi phạm/lịch sử chấm công của mình theo trạng thái | 🟡 Thiếu self-service, phải nhờ HR | ✅ Thêm `GET /violations/my` + filter `status` (bao gồm `pending_review`) trên checkin history |

Toàn bộ 6 hạng mục trên đã được sửa, biên dịch, build lại container, và kiểm thử trực tiếp bằng dữ liệu seed thật. Bộ regression suite (65 test) chạy sau khi sửa: **49 pass / 16 fail** — toàn bộ 16 fail đã được truy nguyên là lỗi môi trường/test script có từ trước, **không liên quan đến các thay đổi trong đợt audit này** (chi tiết ở mục 5).

---

## 2. Đối chiếu từng user story

### Story 1–2 — Cấu hình Random Check theo tenant/site, chế độ kiểm tra (location/face/liveness)
**Xác nhận đúng nghiệp vụ.** `RandomCheckConfig` hỗ trợ override theo site, 3 checkMode tăng dần độ nghiêm ngặt — khớp mô hình "spot check tiers" của Deputy. Không phát hiện lỗi logic, không sửa.

### Story 3 — HR Manager tạo/xem random check thủ công
**Lỗi nghiêm trọng đã sửa.** HR_MANAGER (role hệ thống, `tenant_id IS NULL`) chưa từng được cấp `randomchecks:*`/`checkins:*` trong migration gốc (V13) — mọi request của HR Manager vào các module này trả 403, dù UI/flow nghiệp vụ giả định HR Manager quản lý được. Đây là gap giữa RBAC và luồng nghiệp vụ, không phải lỗi thiết kế tính năng. Đã cấp qua `V83__hr_manager_checkins_and_randomchecks_access.sql`, verify trực tiếp: `role_permissions` nay có đủ 7 quyền, xác nhận qua các cuộc gọi API thật (201/200 thay vì 403).

### Story 4 — Random check tự động sinh theo lịch (nightly job)
**Lỗi nghiệp vụ đã sửa.** `ScheduledCheckGeneratorService` sinh check theo `config.checkMode` mà không kiểm tra nhân viên đã đăng ký Face ID (`enrolled`) hay chưa. Hệ quả: nhân viên chưa từng đăng ký khuôn mặt vẫn bị giao random check dạng `location_face`/`location_face_liveness`, và mọi phản hồi của họ tất yếu fail ở bước xác thực khuôn mặt — tạo violation oan, không có đường thoát hợp lệ. Đã thêm fail-safe: nếu chưa `enrolled`, tự hạ `effectiveCheckMode` về `location_only` tại thời điểm sinh check, ghi log rõ lý do, snapshot phản ánh đúng chế độ thực tế đã áp dụng (đúng nguyên tắc "config tại thời điểm phát sinh là nguồn sự thật" đã có sẵn trong hệ thống). Đây đúng là mẫu hình QuickBooks Time áp dụng — không bắt buộc sinh trắc học khi thiết bị/hồ sơ chưa đủ điều kiện, tránh false violation.

### Story 5 — HR tạo random check thủ công (on-demand) cho 1 nhân viên
Cùng gap Face ID như Story 4, đã áp cùng fail-safe vào `ManualCheckService`, nhưng **chỉ áp dụng khi HR không chỉ định rõ `checkMode` trong request**. Lý do: nếu HR chủ động chọn `location_face` cho một nhân viên cụ thể (vd. để ép buộc nhân viên hoàn tất đăng ký Face ID hoặc phục vụ điều tra), hệ thống phải tôn trọng quyết định thủ công đó thay vì âm thầm hạ cấp — tránh HR bị bất ngờ vì check "biến mất yêu cầu" mà không rõ lý do.

### Story 6 — Nhân viên phản hồi random check, hệ thống ghi nhận vi phạm khi fail
Phát hiện và sửa 2 vấn đề:
- **Trùng lặp vi phạm**: `createViolation()` được gọi từ cả `submit()` (luồng GPS/location) và `applyFaceResult()` (callback xác thực khuôn mặt bất đồng bộ) cho cùng 1 `ScheduledCheck`. Không có guard chống trùng trước đó. Đã thêm kiểm tra `existsByScheduledCheckIdAndViolationType` (dùng lại tiện ích repository sẵn có) trước khi insert, khớp pattern idempotency đã dùng ở nơi khác trong service.
- **Đồng bộ cờ chấm công khi vi phạm được HR xử lý**: `AttendanceSummary.hasRandomCheckFailure` được tính tự động qua native query tại thời điểm chấm công, nhưng khi HR sau đó `confirm`/`dismiss` một violation, cờ này **không được tính lại** — dẫn tới báo cáo chấm công hiển thị sai (vd. nhân viên đã được minh oan nhưng báo cáo vẫn ghi nhận vi phạm, hoặc ngược lại). Đã thêm `AttendanceSummaryService.recomputeIfSummaryExists()`, gọi ngay sau khi `ViolationService.confirmViolation()`/`dismissViolation()` lưu thay đổi. Đồng thời mở rộng truy vấn native `existsFailedOrNoResponseCheck()` để loại các check mà **toàn bộ** violation liên quan đã bị dismiss (không loại nếu còn violation chưa xử lý/còn hiệu lực khác trong cùng ngày — đã verify cả 2 nhánh bằng SQL trực tiếp trên dữ liệu seed thật).

### Story 7 — HR xem/điều chỉnh (override) chấm công khi có vi phạm
**Giữ nguyên thiết kế hiện tại, không hard-block.** Đã cân nhắc việc chặn cứng thao tác override khi còn violation `unresolved`, nhưng quyết định **không áp dụng** vì:
- Field `Violation.affectsAttendance` hiện là cờ HR tự đặt thủ công, mang tính báo cáo/tham khảo — không phải rule engine tự động quyết định "được phép sửa hay không". Đây khớp với cách Deputy/Connecteam vận hành: hệ thống cảnh báo (surface exception) nhưng quyền quyết định cuối vẫn thuộc về người quản lý, vì có nhiều tình huống hợp lệ đòi hỏi override dù còn vi phạm treo (vd. nhân viên đang trong quá trình khiếu nại, HR cần sửa tạm để không chặn lương).
- Nếu ép buộc "phải resolve violation trước khi override" sẽ tạo vòng lặp phụ thuộc cứng nhắc, không khớp thực tế vận hành nhân sự (nhiều trường hợp override xảy ra *trước khi* điều tra vi phạm xong).
**Đây là điểm cần quyết định thêm từ phía anh/chị**: nếu muốn giới hạn chặt hơn (vd. yêu cầu HR nhập lý do bắt buộc khi override một ngày đang có violation chưa xử lý), đây là thay đổi UI/validation nhỏ, có thể bổ sung riêng — hiện tại chưa làm vì không có yêu cầu nghiệp vụ rõ ràng nào chỉ ra đây là lỗi.

### Story 8 — Nhân viên tự giải trình (explanation) / tự tra cứu lịch sử vi phạm và chấm công
**Gap đã sửa.** Trước đó nhân viên chỉ có `GET /scheduled-checks/my-pending` và `/my-result` (theo từng check), không có cách xem **tổng hợp** vi phạm của chính mình theo trạng thái đã xử lý/chưa xử lý — phải nhờ HR tra cứu hộ, không đúng nguyên tắc self-service đã thiết lập trong hệ thống (pattern `/my-*` có sẵn). Đã bổ sung:
- `GET /violations/my?resolved=<bool>` — tự scope theo Employee của user gọi API (theo đúng pattern `findByUserIdAndTenantIdAndDeletedAtIsNull` đã dùng ở các endpoint `/my-*` khác), không cần `@PreAuthorize` vì scoping đã đảm bảo qua chính danh tính người gọi.
- `GET /checkin/history` bổ sung filter `status` (bao gồm `pending_review`) để nhân viên tự lọc các lần chấm công đang chờ duyệt — hỗ trợ trực tiếp luồng "tự kiểm tra trước khi giải trình".

---

## 3. Danh sách thay đổi kỹ thuật

| File | Thay đổi |
|---|---|
| `V83__hr_manager_checkins_and_randomchecks_access.sql` | Migration mới, cấp 7 quyền cho HR_MANAGER |
| `ScheduledCheckGeneratorService.java` | Fail-safe hạ `checkMode` về `location_only` khi chưa có Face ID enrolled |
| `ManualCheckService.java` | Cùng fail-safe, chỉ áp dụng khi HR không chỉ định `checkMode` rõ ràng |
| `CheckResponseService.java` | Idempotency guard chống tạo trùng violation |
| `ScheduledCheckRepository.java` | Mở rộng `existsFailedOrNoResponseCheck()` loại trừ check đã dismiss toàn bộ |
| `AttendanceSummaryService.java` | Thêm `recomputeIfSummaryExists()` |
| `ViolationService.java` | Gọi recompute sau confirm/dismiss; thêm `listMyViolations()`; thêm filter `scheduledCheckId` |
| `ViolationRepository.java` | Thêm `findByScheduledCheckIdAndDeletedAtIsNull` |
| `ViolationSpecification.java` | Overload `build()` nhận `scheduledCheckId` |
| `ViolationController.java` | Thêm `GET /violations/my`, filter `scheduledCheckId` trên list |
| `ScheduledCheckDetailResponse.java` | Thêm `violations[]` (nested `ViolationSummary`) |
| `ScheduledCheckController.java` | Populate `violations[]` trong response chi tiết |
| `CheckinService.java` / `CheckinController.java` | Thêm filter `status` cho lịch sử chấm công |
| `MyExceptionsController.java` / `MyExceptionItemResponse.java` (mới, package `selfservice`) | Gộp đọc checkin pending_review + violation unresolved thành 1 inbox: `GET /me/exceptions` |
| `ManualCheckService.java` | Thêm `countManualTriggersToday()`, gọi `AuditLogService.record()` sau mỗi lần kích hoạt thủ công |
| `ScheduledCheckRepository.java` | Thêm `countByTenantIdAndEmployeeIdAndCheckDateAndCheckIndexLessThanEqualAndDeletedAtIsNull` |
| `ScheduledCheckResponse.java` / `ScheduledCheckController.java` | Thêm trường `manualTriggerCountToday` trên response của endpoint kích hoạt thủ công |
| `FaceResultCallbackController.java` | Guard race-condition: đọc lại trạng thái Face ID tại thời điểm callback, hạ check-in về `pending_review` nếu đã bị revoke |

Tất cả đã build qua `docker restart fams-api`, xác nhận compile sạch qua log Spring Boot, và test trực tiếp bằng curl trên dữ liệu seed thật (không phải unit test giả lập).

---

## 4. Giới hạn đã biết khi kiểm thử

- Không thể demo trực tiếp toàn bộ luồng "dismiss violation → hasRandomCheckFailure chuyển true→false" bằng dữ liệu seed lịch sử, vì `attendance_summaries` trong seed được insert bằng SQL thô (mặc định `false`), không đi qua logic Java thật. Đã **verify độc lập bằng cách chạy trực tiếp câu native SQL** của `existsFailedOrNoResponseCheck()` trên dữ liệu thật, toggle `violations.resolution` và xác nhận kết quả đúng ở cả 2 nhánh (còn sibling violation chưa xử lý → vẫn tính là fail; hết violation hiệu lực → không còn tính là fail).
- 16/65 test trong regression suite fail — đã kiểm tra từng cái, toàn bộ do 3 nguyên nhân môi trường/test-script có từ trước, không liên quan thay đổi hôm nay:
  1. Một số script gọi `POST /tenants` thiếu field `ownerEmail` bắt buộc → "SETUP FAILED".
  2. Một số script dùng field `email` thay vì `identifier` khi gọi `/auth/login`.
  3. Một số test phụ thuộc giờ trong ngày (vd. `test_basic_checkin.sh`), chạy vào ban đêm nên bị chặn hợp lệ bởi validation `CHECKIN_TOO_LATE` (ca đã kết thúc lúc 17:00) — đây là hành vi đúng, không phải bug.
- Sau audit đã chạy lại full clean reseed (drop volume → seed → backfill chấm công 95 ngày, `daysFailed=0`) để đưa môi trường về trạng thái sạch, xác nhận: 18 tenant demo hợp lệ, không có tenant rác từ test, HR_MANAGER có đủ quyền trên schema mới.

---

## 5. Việc còn mở, cần quyết định thêm

1. **Chặn override chấm công khi còn violation chưa xử lý?** — Hiện để mở (xem Story 7). Nếu muốn siết chặt, cần xác nhận yêu cầu cụ thể (chặn cứng / chỉ cảnh báo / bắt buộc nhập lý do).
2. **Email mời (invitation) không tới nơi do Gmail hết quota gửi trong ngày** (`550-5.4.5 Daily user sending limit exceeded`, phát hiện trong log lúc kiểm tra Story liên quan trước đó) — không thuộc phạm vi 8 story này nhưng ảnh hưởng trực tiếp trải nghiệm nhân viên. Đề xuất còn để mở, chưa chọn phương án: đổi credential Gmail / chuyển sang provider email giao dịch (SendGrid, SES...) / thêm log cảnh báo khi gửi thất bại để HR biết mà gửi link thủ công.
3. **README.md phần "Default Accounts & Demo Data"** đã lỗi thời so với bộ seed 18-tenant hiện tại — đề xuất cập nhật, chưa thực hiện, chờ xác nhận.

(3 mục tồn đọng khác từ bản audit 2026-08-03 — gộp UX giải trình, giới hạn kích hoạt thủ công, race condition Face ID revoke — đã được xử lý, xem mục 0.)

---

## 6. Kết luận

8 user story đã được kiểm tra đối chiếu với dữ liệu liên kết thực tế (nhân viên, Face ID, ca làm, phòng ban, công trình, chấm công, random check, vi phạm). Phát hiện 4 lỗi/gap nghiệp vụ thực sự (RBAC thiếu quyền, false violation do thiếu Face ID, thiếu đồng bộ dữ liệu vi phạm↔báo cáo chấm công, thiếu liên kết tra cứu 2 chiều) và 2 gap tính năng self-service — toàn bộ đã sửa, build, verify bằng dữ liệu thật. Không phát hiện thêm lỗi nghiệp vụ nào khác ngoài phạm vi 8 story. Một điểm thiết kế (Story 7) được giữ nguyên có chủ đích, kèm giải thích lý do tham chiếu thực tế ngành.

Bản cập nhật 2026-08-04 xử lý thêm 3 vấn đề tồn đọng (mục 0): hợp nhất phần đọc của 2 luồng giải trình thành 1 inbox tự phục vụ, bổ sung audit log + tín hiệu mềm (không chặn cứng) cho việc kích hoạt random check thủ công, và vá race condition hiếm gặp khi Face ID bị thu hồi đúng lúc kết quả xác thực khuôn mặt đang được xử lý bất đồng bộ. Cả 3 đã build, verify trực tiếp trên dữ liệu thật, và dọn dẹp dữ liệu test khỏi môi trường.
