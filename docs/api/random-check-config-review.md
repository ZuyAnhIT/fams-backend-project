# Tài liệu Random Check Config — Review nghiệp vụ, sửa lỗi và API tham chiếu

> Cập nhật theo code đang chạy ngày 31/07/2026. Base path: `/api/v1/tenants/{tenantId}/random-check-configs` (cấu hình) và `/api/v1/tenants/{tenantId}/scheduled-checks` (dispatch/vận hành).

## 0. Tóm tắt kết quả

Bạn đưa ra 5 tính năng liên quan tới cấu hình "random check" (kiểm tra ngẫu nhiên nhân viên hiện trường — cùng khái niệm với "spot check"/"time clock verification" ở Deputy, QuickBooks Time, hoặc "geofenced attendance verification" ở các app chấm công công trình). Đối chiếu với code thực tế: **cả 5 tính năng đã được xây dựng từ trước** (không phải làm mới), nhưng audit sâu vào logic phát hiện **6 vấn đề nghiệp vụ thực sự** — đã sửa cả 6, test sống qua API thật (không mock).

| # | Tính năng bạn nêu | Trạng thái trước | Việc đã làm |
|---|---|---|---|
| 1 | Tạo cấu hình random check mặc định tenant | Đã có, đúng | Xác nhận, không đổi |
| 2 | Tạo cấu hình override theo site | Đã có, đúng — nhưng logic phân giải (site trước, tenant sau) chỉ nằm trong code dispatch, không có API để xem trực tiếp | **Đã bổ sung** — API "effective config", mục 6 |
| 3 | Cấu hình số lần và khung giờ check | Đã có, logic đúng — nhưng khung giờ hoàn toàn độc lập với giờ ca thực tế của nhân viên | **Đã sửa** — mục 4 |
| 4 | Cấu hình mode kiểm tra (location_only/face/face_liveness) | Đã có field, nhưng **liveness không được thực thi thực sự** | **Đã sửa (lỗi nghiêm trọng)** — mục 1 |
| 5 | Cấu hình áp dụng theo vai trò (role_at_site) | Đã có, đúng | Xác nhận, không đổi |

Ngoài 5 tính năng bạn nêu, audit liên kết dữ liệu với các tính năng trước đó (quản lý nhân viên, Face ID, ca làm, công trình) phát hiện thêm 3 vấn đề liên kết dữ liệu không nhất quán — đã sửa cả 3 (mục 2, 3, 5).

**Đã test sống** (build lại `fams-api`, gọi API thật + kiểm tra DB thật, không mock) toàn bộ 6 điểm sửa — chi tiết ở từng mục dưới.

## 1. [Lỗi nghiêm trọng — đã sửa] Mode `location_face_liveness` không thực sự thực thi liveness

### 1.1 Phát hiện

`RandomCheckConfig.checkMode` có 3 giá trị: `location_only`, `location_face`, `location_face_liveness` — nhưng khi AI trả kết quả về (`CheckResponseService.applyFaceResult`), code **chỉ đọc `faceVerified`**, hoàn toàn bỏ qua `livenessVerified` dù giá trị này đã được lưu vào DB. Hậu quả: 1 nhân viên cấu hình mode `location_face_liveness` — mode kiểm soát chặt nhất, dùng để chống nhân viên giơ ảnh/video giả thay vì có mặt thật — **chỉ cần khớp khuôn mặt là PASS**, dù liveness thất bại (ví dụ giơ ảnh chụp sẵn của chính mình lên camera). Đây đúng là lỗ hổng mà mode này sinh ra để chặn, nhưng không hoạt động.

Bằng chứng thêm: `violations` có `violation_type='liveness_fail'` được định nghĩa sẵn trong DB constraint từ trước nhưng **chưa bao giờ được tạo ra** ở luồng random check — giá trị "chết", không thể xảy ra.

### 1.2 Đối chiếu thực tế

Hầu hết hệ thống chấm công có "liveness detection" (ProctorU, các app KYC ngân hàng, Deputy's photo verification...) đều coi liveness là điều kiện **độc lập** với face-match — cả hai đều phải pass. Việc chỉ kiểm tra face-match là lỗi rõ ràng, làm mode mạnh nhất trong 3 mode trở thành vô nghĩa (tương đương `location_face`).

### 1.3 Đã sửa

`CheckResponseService.applyFaceResult` giờ đọc thêm check mode từ `ScheduledCheck.configSnapshot`: nếu mode là `location_face_liveness` và `livenessVerified=false` (dù `faceVerified=true`), outcome chuyển `fail`, tạo violation `liveness_fail` riêng biệt (độc lập với `face_fail`).

**Đã test sống**: tạo 1 check mode `location_face_liveness`, mô phỏng callback AI với `faceVerified=true, livenessVerified=false` → xác nhận DB: `outcome=fail`, `failure_reason` chứa `liveness_fail`, violation `liveness_fail` được tạo với mô tả "possible spoofed photo".

## 2. [Lỗi nghiêm trọng — đã sửa] Nhân viên đã nghỉ việc vẫn bị lên lịch kiểm tra ngẫu nhiên

### 2.1 Phát hiện

Job sinh lịch kiểm tra hàng ngày (`ScheduledCheckGeneratorService.generateForDate`) truy vấn assignment đang active qua `AssignmentRepository.findActiveAssignmentsWithShiftForDate`/`findAllActiveAssignmentsWithShiftForDate` — 2 câu native SQL chỉ lọc theo bảng `assignments` (`status='active'`), **không JOIN sang bảng `employees`**. Trong khi đó, `EmployeeService.changeEmployeeStatus` khi chuyển 1 nhân viên sang `terminated` chỉ đổi cờ trên bảng `employees` và thu hồi Face ID — **không hề đụng tới các `Assignment` của nhân viên đó**. Việc huỷ assignment chỉ xảy ra qua 1 hành động riêng, tách biệt (`AssignmentService.cancelAssignment`), không tự động khi nghỉ việc.

**Hậu quả thực tế**: 1 nhân viên bị cho nghỉ việc nhưng chưa có ai chủ động huỷ assignment của họ (rất dễ bị bỏ sót trong quy trình thực tế — HR nghĩ "đổi trạng thái nhân viên" là xong) sẽ **tiếp tục nhận lịch kiểm tra ngẫu nhiên mỗi ngày, vô thời hạn**, và nếu không phản hồi sẽ tiếp tục tích luỹ violation `no_response` cho 1 người không còn làm việc — dữ liệu vi phạm sai lệch, gây nhiễu báo cáo.

Cùng nguyên nhân, hàm `ReportService` dùng lại đúng 2 query này để tính danh sách "nhân viên vắng mặt" (`absentEmployeeIds`) trên báo cáo chấm công ngày — nhân viên đã nghỉ việc cũng bị tính nhầm là "vắng mặt" thay vì được loại hẳn khỏi báo cáo.

### 2.2 Đã sửa

Thêm `JOIN employees e ON e.id = a.employee_id AND e.status='active' AND e.deleted_at IS NULL` vào cả 2 query. Sửa 1 chỗ, tự động khắc phục cả random check lẫn báo cáo "vắng mặt" vì cả 2 dùng chung query.

**Đã test sống**: chuyển 1 nhân viên đang có lịch kiểm tra sang `terminated` → chạy generate cho ngày mai → xác nhận **0** lịch kiểm tra được tạo cho người này, trong khi các đồng nghiệp active khác tại cùng site vẫn được tạo lịch bình thường (21 lịch).

## 3. [Lỗi nghiêm trọng — đã sửa] Không kiểm tra nhân viên đã đăng ký Face ID trước khi bắt buộc xác thực khuôn mặt

### 3.1 Phát hiện

Swagger doc trên `CreateRandomCheckConfigRequest.checkMode` ghi rõ: *"requires employee to have an enrolled Face ID; employees without an enrolled profile will automatically receive a face_fail violation"* — nhưng code **chưa bao giờ implement điều này**. `CheckResponseService.submit()` chỉ kiểm tra "có ảnh trong request hay không", không hề tra `FaceProfileRepository` xem nhân viên đã đăng ký khuôn mặt (qua tính năng Face ID enrollment, tính năng bạn nhắc tới trong yêu cầu liên kết) hay chưa.

**Hậu quả**: 1 nhân viên chưa từng đăng ký Face ID (hoặc bị thu hồi — ví dụ do đổi vai trò, hoặc do vừa bị sa thải rồi phục hồi) nhưng bị lên lịch kiểm tra mode `location_face`/`location_face_liveness` sẽ gửi ảnh lên, hệ thống tốn tài nguyên gọi AI verify dù **chắc chắn không thể pass** (không có gì để so khớp) — vừa lãng phí, vừa trải nghiệm tệ (nhân viên không hiểu vì sao luôn fail).

### 3.2 Đã sửa

`submit()` giờ tra `FaceProfileRepository` trước: nếu không có hồ sơ hoặc `status != 'enrolled'` (ví dụ `not_enrolled`, `revoked`), fail ngay lập tức với lý do rõ ràng, **không gọi AI verify** (tiết kiệm tài nguyên), tạo violation `face_fail` với mô tả "employee has no enrolled Face ID" (khác với lý do "no photo submitted" trước đây — HR đọc violation biết ngay cần nhắc nhân viên đi đăng ký Face ID, không phải lỗi hành vi).

**Đã test sống**: nhân viên có `face_profiles.status='revoked'` gửi ảnh hợp lệ cho 1 check mode `location_face` → xác nhận fail ngay (`faceVerified=false`, không có cuộc gọi AI async nào), violation ghi đúng lý do "no enrolled Face ID".

## 4. [Đã sửa] Khung giờ kiểm tra không ràng buộc theo giờ ca thực tế

### 4.1 Phát hiện

`allowedStartTime`/`allowedEndTime` trên config hoàn toàn độc lập với `Shift.startTime`/`Shift.endTime` của ca mà nhân viên đang làm — `ScheduledCheckGeneratorService` có sẵn `ShiftRepository` được inject nhưng **chưa bao giờ được gọi** (dependency chết). Hậu quả: HR cấu hình khung giờ kiểm tra cho ca ngày (ví dụ `08:00–17:00`) nhưng site đó có cả ca đêm (`22:00–06:00`) — nhân viên ca đêm hoặc không bao giờ bị kiểm tra (khung giờ ngoài giờ làm), hoặc (nguy hiểm hơn) bị "kiểm tra" vào giờ họ đang ngủ nếu admin không để ý.

### 4.2 Đã sửa

Trước khi sinh giờ kiểm tra ngẫu nhiên, hệ thống giờ tính **giao (∩)** giữa khung giờ config và giờ ca thực tế (`Shift.startTime/endTime`) của assignment đang xử lý — chỉ sinh check trong phần giao đó. Nếu 2 khung giờ không giao nhau (ca thực tế nằm hoàn toàn ngoài khung config), assignment đó bị bỏ qua (có log lý do rõ ràng), thay vì sinh check vào giờ nhân viên không làm việc.

Ca qua đêm (`allowOvernight=true`) **chưa được xử lý giao khung giờ** trong bản sửa này (cố ý) — việc bọc khung giờ config cùng ngày với 1 ca vắt qua nửa đêm cần logic riêng phức tạp hơn, rủi ro sai cao hơn lợi ích thu được (ca qua đêm không phổ biến bằng ca ngày/ca chiều thông thường); với ca qua đêm, hệ thống giữ nguyên hành vi cũ (dùng thẳng khung giờ config) — an toàn hơn là rủi ro tính sai.

**Đã test sống**: config khung `07:00–22:00`, ca chiều thực tế `15:00–23:00` → xác nhận toàn bộ giờ check sinh ra nằm trong `[15:00, 22:00]` (đúng phần giao), không có giờ nào trước 15:00 hay sau 22:00. Test khung config `00:00–04:00` không giao với bất kỳ ca nào tại site → xác nhận 0 check được sinh ra, log ghi rõ "does not overlap assignment's shift hours".

## 5. [Đã sửa] Config override của 1 site không bị dọn dẹp khi xoá site

### 5.1 Phát hiện

`SiteService.deleteSite` soft-delete site sau khi xác nhận không còn assignment active, nhưng không đụng tới `random_check_configs` — 1 config override từng gắn với site đó trở thành dữ liệu mồ côi (tham chiếu 1 site đã xoá), không có đường dọn dẹp. Không khai thác được ngay (không còn assignment nào tham chiếu site đã xoá để sinh check), nhưng là dữ liệu rác tồn tại vĩnh viễn, và nếu site được "khôi phục" (xoá `deleted_at` trực tiếp qua thao tác DB — ví dụ khôi phục nhầm) thì config cũ sẽ âm thầm sống lại.

### 5.2 Đã sửa

`deleteSite` giờ soft-delete luôn config override của site đó (nếu có) trong cùng transaction.

**Đã test sống**: tạo site test có config override → xoá site → xác nhận config override cũng có `deleted_at` được set, đồng thời cùng lúc site bị xoá.

## 6. [Đã bổ sung] API "effective config" — xem config nào thực sự áp dụng cho 1 site

### 6.1 Vấn đề

Trước đây muốn biết "config nào đang thực sự áp dụng cho site X" (site override, hay rơi về tenant default), client phải: gọi `GET .../sites/{siteId}` (bắt lỗi 404 nếu không có override), rồi gọi thêm `GET .../tenant-default`, tự lặp lại đúng logic fallback mà `ScheduledCheckGeneratorService`/`ManualCheckService` đã cài sẵn — vừa dư thừa gọi API, vừa là nơi thứ 3 phải giữ đồng bộ với 2 nơi có sẵn trong code backend.

### 6.2 Đã bổ sung

`GET /api/v1/tenants/{tenantId}/random-check-configs/sites/{siteId}/effective` — trả về đúng config sẽ áp dụng (site override nếu có và đang active, không thì tenant default), kèm field mới `resolvedFrom` (`"site_override"` hoặc `"tenant_default"`) để FE biết rõ nguồn. Chỉ 404 khi **cả 2** đều không tồn tại.

**Đã test sống**: gọi cho 1 site đã có override → trả đúng config đó, `resolvedFrom="site_override"`.

## 7. [Đã bổ sung 31/07/2026] Liên kết random-check với AttendanceSummary — chỉ cảnh báo, không tự trừ lương

Theo quyết định của bạn (tham khảo Deputy/QuickBooks Time: random check là công cụ audit/phát hiện gian lận, không phải công cụ trừ lương tự động — 1 lần `no_response` có thể do mất sóng, không phải bằng chứng chắc chắn nhân viên vắng mặt):

- `AttendanceSummary` có thêm cột `has_random_check_failure` (boolean) — `true` nếu ngày đó có ≥1 random check kết thúc `no_response` hoặc `outcome='fail'`. Tính trong `recompute()`, cùng lúc với `hasPendingReviewSession`/`hasRejectedSession` — **không đụng tới `totalWorkMinutes`/OT/bất kỳ field tính lương nào**, đã test sống xác nhận số phút làm việc giữ nguyên.
- Rollup thành `daysWithRandomCheckFailure` trên `AttendanceSummaryResponse`, `AttendanceHrMonthlyResponse`, `AttendanceMonthlyResponse` (nhân viên tự xem) và `MonthlyAttendanceReportResponse.totalRowsWithRandomCheckFailure`.
- `RandomCheckConfig` có thêm `failureEscalationThreshold` (mặc định 3, cấu hình được qua API config) — số lần fail/`no_response` trong 1 tháng để field mới `exceedsRandomCheckFailureThreshold` bật `true` trên báo cáo tháng. Chỉ là **tín hiệu hiển thị cho HR**, không tự động làm gì khác.
- Guard xuất Excel (`409 ATTENDANCE_NOT_READY`, đã có từ đợt sửa attendance) mở rộng thêm điều kiện: chặn xuất nếu còn dòng nào có `daysWithRandomCheckFailure > 0`, cùng cơ chế `confirmDespiteWarnings=true` để HR chủ động bỏ qua nếu đã xác nhận không liên quan.
- Excel export thêm 2 cột "Days With Random-Check Failure" và "Exceeds Failure Threshold".

**Đã test sống**: tạo 1 random check `no_response` cho 1 ngày đã có bảng công → gọi `/recompute` → xác nhận `has_random_check_failure=true` và `total_work_minutes` không đổi; gọi export không `confirmDespiteWarnings` → nhận đúng `409` liệt kê cả số dòng random-check-failure; gọi lại kèm `confirmDespiteWarnings=true` → xuất file thành công.

Migration: `V80__random_check_failure_tracking_and_manual_reason.sql`.

## 8. [Đã bổ sung 31/07/2026] Manual check giữ nguyên hành vi bỏ qua `applicableRoles` — bắt buộc `reason`

Theo quyết định của bạn: giữ nguyên việc `ManualCheckService` không lọc theo `applicableRoles` (đúng thiết kế — HR chỉ định đích danh 1 nhân viên là hành động điều tra có chủ đích, khác sampling ngẫu nhiên theo population). Bổ sung audit trail cho hành động "vượt rào" này:

- `ManualCheckRequest.reason` — **bắt buộc** (400 nếu thiếu), mô tả lý do nhắm tới nhân viên này.
- `ScheduledCheck` có thêm `manual_reason` (lưu lý do) và `triggered_by` (ai bấm) — chỉ set cho check thủ công, null cho check tự động. Trả về trên `ScheduledCheckResponse`.

**Đã test sống**: gọi `/scheduled-checks/manual` thiếu `reason` → `400` đúng thông báo; gọi kèm `reason` → response trả đúng `manualReason` + `triggeredBy`.

## 9. [Đã sửa 31/07/2026] 18 file test trong `tests/randomcheck/` — dọn dẹp toàn bộ

Nguyên nhân gốc (đã nêu ở bản audit trước): API tạo tenant đổi yêu cầu bắt buộc `ownerEmail`/`ownerUserId` (commit 20b9e38, 24/07/2026) mà các script test cũ chưa cập nhật. Khi sửa xong bước setup này, lộ thêm 4 lỗi/staleness khác trong chính các script (không liên quan tới code backend), đã sửa toàn bộ, **cả 18/18 file giờ pass 100%**:

| # | Vấn đề trong test | Sửa |
|---|---|---|
| 1 | Bước tạo tenant thiếu `ownerEmail` → `400` | Thêm `"ownerEmail":"admin@fams.com"` vào toàn bộ 25 lệnh gọi tạo tenant |
| 2 | Bước đăng nhập nhân viên dùng field `"email"` thay vì `"identifier"` (API đã đổi tên field) → script dừng giữa chừng do `set -e` khi không tìm thấy `accessToken` | Đổi toàn bộ ~20 lệnh login trong test sang `"identifier"` |
| 3 | `ManualCheckRequest` thiếu `reason` bắt buộc (hệ quả trực tiếp của mục 8 vừa thêm) | Thêm `"reason":"test manual check"` vào toàn bộ 16 lệnh gọi manual check trong test |
| 4 | `test_dispatch_job.sh` kỳ vọng sai: dispatch 1 check không tồn tại trả về `200` — nhưng code thực tế (đúng REST) trả `404` | Sửa kỳ vọng test thành `404` |
| 5 | `test_fail_violations.sh`: polygon geofence không khép kín (thiếu điểm cuối = điểm đầu); dùng field `faceImageUrl`/`livenessScore` (không được `submit()` đọc để verify — chỉ lưu metadata) thay vì `employeePhotoBase64` (field thật); nhân viên test chưa đăng ký Face ID nên bị fail ngay theo đúng mục 3 (chưa liên quan gì tới liveness) | Khép kín polygon; viết lại Tests 7-9 để mô phỏng đúng luồng bất đồng bộ thật (đăng ký Face ID cho nhân viên test, submit với `employeePhotoBase64`, giả lập callback AI qua `/internal/ai-callback/face-result` — tách khỏi worker AI thật đang chạy trong môi trường dev để tránh race điều kiện) |
| 6 | 2 script (`test_manual_check.sh`, `test_fail_violations.sh`) tạo nhiều site trong CÙNG 1 tenant, vượt giới hạn 1 site của gói Trial mặc định | Nâng gói tenant test lên `pro` ngay sau khi tạo (`PATCH .../subscription`) |
| 7 | `test_fail_violations.sh` Test 12 tạo ca thứ 2 trùng giờ với ca thứ 1 cho cùng 1 nhân viên → bị chặn đúng theo rule "không được có 2 assignment giờ trùng nhau" (rule đúng, test sai dữ liệu) | Đổi ca thứ 2 sang giờ không trùng (18:00–23:00 thay vì 08:00–17:00) |

## 10. [Đã sửa 01/08/2026] Phản hồi yêu cầu Backend từ 2 báo cáo audit FE (Web + App)

Team Web (`22_BAO_CAO_RANDOM_CHECK_WEB_2026-07-31.md`) và App (`26_BAO_CAO_APP_RANDOM_CHECK_2026-07-31.md`) sau khi dựng UI dựa trên tài liệu này đã tự audit code thật và nêu 5 điểm Backend cần bổ sung để hoàn thiện. Đã xác minh độc lập từng điểm trên code thật trước khi sửa — cả 5 xác nhận đúng, đã sửa cả 5, test sống qua API thật.

### 10.1 [Web P1 — đã sửa] `faceVerifyScore` không được map vào response chi tiết

`CheckResponseDto` có field `faceVerifyScore`, cột DB cũng có sẵn giá trị thật (ghi bởi AI callback), nhưng `ScheduledCheckController.toCheckResponseDto()` bỏ sót dòng map field này — luôn trả `null` dù DB có giá trị. HR xem chi tiết 1 lượt kiểm tra face-mode không thấy được độ tin cậy AI để đánh giá tranh chấp.

**Đã sửa**: thêm `.faceVerifyScore(r.getFaceVerifyScore())` vào mapper. **Đã test sống**: mô phỏng callback AI với `faceVerifyScore=0.87` → xác nhận `GET /{checkId}` trả đúng `0.87` (trước đó luôn `null`).

### 10.2 [Web P1 — đã sửa] Chi tiết 1 lượt kiểm tra thiếu `manualReason`/`triggeredBy`

`ScheduledCheckResponse` (dùng cho response tạo/list) đã có 2 field này từ bản vá trước, nhưng `ScheduledCheckDetailResponse` (dùng cho `GET /{checkId}`) thì chưa — Web phải tự "nhớ" dữ liệu từ dòng list đã chọn khi mở modal chi tiết thay vì tin tưởng response của chính API chi tiết, dễ mất dữ liệu khi deep-link/refresh trực tiếp vào URL chi tiết.

**Đã sửa**: thêm `manualReason`/`triggeredBy` vào `ScheduledCheckDetailResponse`, map từ entity. **Đã test sống**: tạo 1 manual check kèm `reason`, gọi `GET /{checkId}` → xác nhận trả đúng `manualReason`/`triggeredBy`, không còn null.

### 10.3 [Web P1 — đã sửa] Danh sách lượt kiểm tra (`GET /scheduled-checks`) thiếu tên nhân viên/site và outcome

Trước đây list chỉ trả UUID thô + không có `outcome`/`failureReason` — Web phải tự resolve tên qua API directory riêng (cache lại để tránh gọi lặp) và chỉ biết kết quả pass/fail khi mở modal chi tiết từng dòng (N+1 nếu muốn hiện luôn trên bảng).

**Đã sửa**: `ScheduledCheckResponse` thêm `employeeName`, `siteName`, `outcome`, `failureReason`. Hydrate bằng **batch query cho cả trang** (không phải N+1 mỗi dòng) — 1 câu `IN (...)` cho employee, 1 câu cho site, 1 câu cho check_responses của toàn bộ ID trong trang, giống hệt pattern đã dùng ở `ReportService`/`AttendanceSummaryService`. Thêm `CheckResponseRepository.findAllByScheduledCheckIdIn` cho việc này.

**Đã test sống**: gọi `GET /scheduled-checks?size=3` → xác nhận mỗi dòng có `employeeName`, `siteName` đầy đủ, và dòng đã `responded` có đúng `outcome`.

### 10.4 [App P0 — đã bổ sung] Endpoint kết quả cuối dành riêng cho nhân viên

**Đây là lỗ hổng nghiêm trọng nhất trong 5 điểm**: tài liệu trước hướng dẫn App poll `GET /scheduled-checks/{checkId}` để lấy kết quả xác thực AI (bất đồng bộ) sau khi gửi ảnh — nhưng endpoint đó yêu cầu quyền `randomchecks:list`/`:configure` (chỉ HR/Admin), nhân viên thường gọi sẽ nhận `403`. `GET /my-pending` (endpoint duy nhất nhân viên gọi được) không có `outcome`/`faceVerified`/`livenessVerified`. Kết quả: **nhân viên không có cách nào hợp lệ để biết kết quả cuối cùng của chính lượt kiểm tra mình vừa phản hồi** — lỗi thiết kế API thực sự, không phải FE hiểu sai.

**Đã bổ sung**: `GET /scheduled-checks/{checkId}/my-result` — không yêu cầu quyền đặc biệt, tự xác định nhân viên qua JWT, **chỉ trả về nếu check đó thuộc đúng nhân viên gọi** (khác nhân viên → `404`, không phải `403`, để không xác nhận sự tồn tại của check thuộc người khác). DTO tối giản (`MyCheckResultResponse`), không có embedding, đường dẫn ảnh lưu trữ nội bộ, hay dữ liệu người khác:

```json
{
  "checkId": "uuid",
  "status": "responded",
  "processingStatus": "pending",
  "outcome": "pass",
  "failureReason": null,
  "locationVerified": true,
  "faceVerified": null,
  "livenessVerified": null,
  "faceVerifyScore": null,
  "respondedAt": "2026-08-01T01:48:21Z"
}
```

`processingStatus` (`"pending"`/`"completed"`) — tính bằng: nếu mode yêu cầu face (`location_face`/`location_face_liveness`) và `faceVerified` vẫn `null` (đang chờ callback AI) → `"pending"`; mọi trường hợp khác (mode không cần face, hoặc đã có kết quả cuối) → `"completed"`.

**Giản lược đã chọn so với đề xuất của App** (ghi rõ để bạn biết, không có gì bị giấu): đề xuất của App có thêm field `processedAt` (thời điểm AI xử lý xong, tách biệt với `respondedAt`) và trạng thái `processingStatus="failed"` riêng (khi bản thân việc gọi AI bị lỗi hạ tầng, khác với "face không khớp"). Cả 2 đều cần thêm cột DB mới + đường dẫn truyền `errorCode` từ AI callback vào tận `check_responses` (hiện `applyFaceResult` không nhận tham số này) — phạm vi lớn hơn 1 endpoint đơn giản, **chưa làm trong đợt này**. App vẫn dùng được contract hiện tại vì mục đích chính (poll tới khi có outcome cuối) hoạt động đúng — trạng thái lỗi hạ tầng AI hiếm gặp sẽ tạm thời hiện là `processingStatus="pending"` mãi tới khi App tự timeout theo thiết kế đã có sẵn (60 giây, theo checklist test của chính App).

**Đã test sống**: gửi phản hồi kèm ảnh → gọi `my-result` ngay → `processingStatus="pending"`, `faceVerified=null`; mô phỏng callback AI → gọi lại `my-result` → `processingStatus="completed"`, `faceVerified=true`, `faceVerifyScore` đúng giá trị. Test bảo mật: nhân viên khác gọi cùng `checkId` → `404` (không phải `403`).

### 10.5 [App P1 — đã bổ sung] Metadata có cấu trúc cho thông báo `RANDOM_CHECK_SENT`

Trước đây `Notification` chỉ có `title`/`body` dạng text tự do — App phải tự tìm `checkId` bằng cách mở danh sách chung thay vì deep-link thẳng vào đúng lượt kiểm tra vừa nhận thông báo.

**Đã bổ sung**: cột `notifications.metadata` (JSONB, nullable — chỉ ảnh hưởng luồng random-check, các loại thông báo khác vẫn `null` như cũ). `RandomCheckDispatchService` giờ gửi kèm:
```json
{ "checkId": "uuid", "siteId": "uuid", "expiresAt": "2026-08-01T01:52:00Z" }
```
`NotificationService.createNotification` có thêm overload nhận `Map<String, Object> metadata` (giữ nguyên overload 5-tham số cũ cho mọi caller khác, không phá vỡ gì). `NotificationResponse` (trả về từ `GET /notifications`) có thêm field `metadata`.

**Giới hạn đã biết, chưa làm**: đây là metadata cho thông báo **trong app** (bảng `notifications`, lấy qua `GET /notifications`) — chưa mở rộng sang **payload data của chính gói FCM push** (`FcmClient.sendToToken` hiện chỉ nhận `title`/`body`, không có tham số data). Nghĩa là: khi App đã mở sẵn và đồng bộ `GET /notifications`, deep-link hoạt động đầy đủ; nhưng nếu muốn xử lý bấm thẳng vào push notification lúc app đang tắt hoàn toàn (background) mà không cần mở danh sách trước, cần thêm việc truyền `data` payload qua FCM riêng — phạm vi lớn hơn (đụng tới toàn bộ `FcmClient`/mọi caller `sendPush`), chưa làm trong đợt này. App đã tự có cơ chế fallback mở danh sách khi bấm thông báo (theo đúng báo cáo App đã ghi) nên không bị chặn.

**Đã test sống**: dispatch 1 check → kiểm tra trực tiếp DB `notifications.metadata` có đúng `checkId`/`siteId`/`expiresAt` → gọi `GET /notifications` bằng token nhân viên → xác nhận field `metadata` trả về đúng trong response JSON.

## 11. [Đã sửa 01/08/2026] 2 điểm thống nhất Backend/tài liệu chưa chặn Web (theo `22_BAO_CAO_RANDOM_CHECK_WEB_2026-07-31.md` mục "Ghi chú")

### 11.1 HR xem ảnh selfie — đã bổ sung endpoint lấy lại ảnh

**Phát hiện qua audit code thật (kể cả sang `ai-service`, repo Python song song)**: `fams-ai` **đã lưu ảnh selfie thật** cho mọi lượt random-check có ảnh — `worker.py` gọi `save_checkin_photo(tenant_id, source_id, image_bytes)` vô điều kiện cho mọi job, ghi vào `checkins/{tenant_id}/{checkResponseId}.jpg`. Vấn đề không phải "chưa lưu ảnh" như đoán ban đầu — mà là **không có route nào lấy lại ảnh đó**, cả bên Python lẫn Java.

**Đã sửa — nhân bản đúng pattern đã dùng cho Face ID enrollment review** (`GET /employees/{id}/face-id/pending-review/photo`, không có gì mới):
- `ai-service`: route mới `GET /checkins/{source_id}/photo?tenant_id=...` (`app/routers/checkin_photo.py`) — đọc trực tiếp file theo đường dẫn cố định (không qua DB, vì tên file chính là `source_id`), trả `404` nếu chưa từng lưu.
- Java: `AiServiceClient.getCheckinPhoto(tenantId, sourceId)` gọi route trên qua `X-Internal-Secret`.
- Endpoint HR mới: `GET /scheduled-checks/{checkId}/photo` — cùng quyền + site-scope với `GET /{checkId}`, stream `image/jpeg` thẳng qua Java (không presigned URL, không S3, giống hệt cách Face ID review đang làm — "hết hạn xem" chính là hết quyền, đánh giá lại mỗi request).
- `CheckResponseDto` thêm `hasPhotoEvidence: boolean` — Web kiểm tra field này trước khi hiện nút "Xem ảnh", tránh gọi endpoint ảnh cho những lượt chắc chắn không có (mode `location_only`, hoặc mode face nhưng nhân viên không gửi ảnh/không enrolled).
- Migration `V82__check_response_photo_evidence_flag.sql` — thêm `check_responses.photo_submitted`, set `true` đúng lúc ảnh thực sự được chuyển tới job AI (không phải lúc nhận request — nếu chưa enrolled hoặc không gửi ảnh thì fail ngay, không có ảnh nào được lưu, `hasPhotoEvidence` đúng là `false`).

**Đã test sống**: submit 1 response kèm ảnh → `hasPhotoEvidence=true` ngay trong response `respond()` → gọi `GET /{checkId}/photo` → nhận đúng `200` + đúng byte ảnh đã gửi. Test 1 response KHÔNG có ảnh (`location_only`) → gọi `.../photo` → đúng `404`.

**Chưa làm, đã nêu với bạn**: retention theo policy biometric (ảnh hiện lưu vô thời hạn trên `fams-ai`, không tự xoá) — tách thành việc riêng (job dọn ảnh cũ theo N ngày), không chặn việc xem ảnh trước mắt.

### 11.2 `Assignment.role` — xác nhận hard-lock `worker`/`supervisor`, đã sửa tài liệu cho khớp

Xác nhận qua code: `Assignment.role` bị khoá cứng ở **3 tầng độc lập** — `@Pattern(regexp = "^(worker|supervisor)$")` trên cả `CreateAssignmentRequest` và `UpdateAssignmentRequest`, cộng `CHECK (role IN ('worker', 'supervisor'))` ở DB (`V26__create_assignments.sql`). Nguồn gốc mô tả "free-text" trong tài liệu trước: `RandomCheckConfig.applicableRoles` (field khác, trên config, không phải trên `Assignment`) đúng là lưu free-text không ràng buộc — nhưng vì nó so khớp trực tiếp với `Assignment.role`, **mọi giá trị khác `worker`/`supervisor` (ví dụ `"employee"` trong ví dụ Swagger cũ) là cấu hình chết, không bao giờ khớp được ai**.

**Đã sửa — chỉ ở tài liệu/Swagger, không đổi schema/validation** (theo đúng quyết định của bạn — mở rộng enum role là quyết định nghiệp vụ lớn hơn, chưa có nhu cầu cụ thể):
- `CreateRandomCheckConfigRequest`, `UpdateRandomCheckConfigRequest`, `UpdateApplicableRolesRequest`: đổi ví dụ Swagger từ `["supervisor", "employee"]` → `["worker", "supervisor"]`, sửa mô tả nêu rõ đây là 2 giá trị hợp lệ duy nhất hiện tại và lý do (khớp `Assignment.role`, có `@Pattern` + `CHECK` constraint).
- `RandomCheckConfigController` (Javadoc endpoint `PUT .../applicable-roles`): sửa tương tự, đổi `role_at_site` (tên field không tồn tại, chỉ là cách gọi trong doc) thành đúng tên field thật `Assignment.role`.
- **Việc Web đang làm (giữ cứng 2 lựa chọn trong UI) là đúng, giữ nguyên, không cần đổi gì.**

## 12. [Đã sửa 01/08/2026] Audit 10 user story vòng đời scheduled-check — 2 lỗi thực sự phát hiện

Bạn đưa ra 10 tính năng bao trùm toàn bộ vòng đời 1 lượt kiểm tra ngẫu nhiên: sinh check đầu ca, snapshot config, hàng đợi dispatch trễ ("Bull/BullMQ job"), huỷ check khi assignment/site không còn hợp lệ, gửi thông báo, App hiển thị check đang chờ, 3 mode phản hồi, và từ chối phản hồi trễ. Đối chiếu với code thực tế: **8/10 đã đúng và đã được xác nhận/sửa ở các đợt trước** (mục 1-11 phía trên) — không lặp lại ở đây. Audit sâu riêng 2 điểm còn nghi vấn phát hiện **2 lỗi thực sự**, đã sửa cả 2.

### 12.1 [Đã sửa] Huỷ check khi assignment không còn hợp lệ — thiếu 1 trong 3 đường dẫn

**User story liên quan**: *"Là một HR/Admin hoặc hệ thống, tôi muốn hủy check khi assignment/site không còn hợp lệ để tránh gửi kiểm tra sai."*

Có 3 cách 1 assignment "không còn hợp lệ": (a) HR chủ động huỷ assignment, (b) nhân viên bị cho nghỉ việc, (c) site bị xoá. Kiểm tra cả 3:
- **(a) HR huỷ assignment — đã đúng từ trước, không phải lỗi**: `AssignmentService.cancelAssignment` gọi `ScheduledCheckCancelService.cancelPendingByAssignment` ngay khi huỷ.
- **(b) Nhân viên bị cho nghỉ việc (`terminated`) — lỗi thật, đã xác nhận**: bản vá trước (mục 2) chỉ chặn việc **sinh check MỚI** cho nhân viên đã nghỉ việc (query sinh lịch join `employees.status='active'`) — nhưng **không đụng tới các check ĐÃ sinh sẵn từ đầu ca hôm đó**. Kịch bản thật: nhân viên bị cho nghỉ lúc 14h, đã có 2 lượt kiểm tra `pending`/`sent` sinh từ sáng → các dòng này bị bỏ quên, tự nhiên hết hạn qua `NoResponseViolationJob` → tạo violation `no_response` cho 1 người đã nghỉ việc. `EmployeeService.changeEmployeeStatus` trước đây không hề gọi tới `ScheduledCheckCancelService`.
- **(c) Site bị xoá — không phải lỗi, không có khoảng hở**: `SiteService.deleteSite` bắt buộc site phải hết assignment active trước khi xoá được — mà đường duy nhất huỷ assignment (a) đã tự dọn check rồi, nên tới lúc site xoá được thì không còn check nào sống sót để mồ côi.

**Đã sửa**: `EmployeeService.changeEmployeeStatus`, nhánh `terminated`, giờ tìm mọi assignment đang `active` của nhân viên đó và gọi `scheduledCheckCancelService.cancelPendingByAssignment(...)` cho từng cái — làm đúng những gì `cancelAssignment` (a) đã làm, chỉ khác nguồn kích hoạt. Không đổi trạng thái assignment (giữ nguyên `active`) — chỉ huỷ các lượt kiểm tra chưa xử lý, tách biệt khỏi quyết định "có nên tự động kết thúc assignment khi nghỉ việc" (một quyết định nghiệp vụ khác, chưa được yêu cầu).

**Đã test sống**: sinh check cho hôm nay → xác nhận nhân viên có 2 check `pending` → gọi API đổi trạng thái nhân viên sang `terminated` → xác nhận cả 2 check chuyển thành `cancelled` (không phải bị bỏ quên tới khi hết hạn), log ghi rõ `"Auto-cancelled 2 scheduled check(s) due to employee termination"`.

### 12.2 [Đã sửa] Hàng đợi dispatch trễ — xác nhận không dùng Bull/BullMQ (khác công nghệ), phát hiện thêm lỗ hổng phục hồi sau restart

**User story liên quan**: *"Là một hệ thống, tôi muốn tạo delayed job cho scheduled_check để gửi thông báo đúng giờ"* (mô tả gốc: "Tạo Bull/BullMQ job gửi check").

**Làm rõ công nghệ**: Bull/BullMQ là thư viện hàng đợi job của **Node.js**, không tồn tại trong dự án này (đã xác nhận: không có `package.json` nào chứa `bullmq` trong toàn bộ repo, kể cả `ai-service`). Backend này là Java/Spring — cơ chế tương đương đã có sẵn từ trước: `RandomCheckDispatchQueue` (Redis Sorted Set, key `fams:randomcheck:dispatch`, score = thời điểm gửi) + `RandomCheckDispatchJob` (`@Scheduled`, quét mỗi 60 giây, lấy các check đã tới giờ và gửi thông báo) — đúng chức năng "delayed job" như Bull/BullMQ cung cấp, chỉ khác nền tảng ngôn ngữ.

**Lỗ hổng phát hiện qua audit sâu — đã sửa**: hàng đợi Redis này **không có cơ chế phục hồi** — chỉ được ghi 1 lần lúc sinh check (`ScheduledCheckGeneratorService`), không có gì đọc lại từ bảng `scheduled_checks` để dựng lại hàng đợi. Hậu quả cụ thể:
- **Restart app** (deploy, crash-recovery) → Redis ZSET không mất dữ liệu (Redis vẫn sống), nhưng **những check được sinh trong lúc app đang khởi động lại** hoặc **check đã có trong hàng đợi từ trước khi Redis chính nó bị restart** sẽ không còn ai gửi thông báo — tới hạn `expiresAt` mà nhân viên chưa từng nhận được thông báo, tự động thành `no_response`, tạo violation oan cho người chưa từng biết mình bị kiểm tra.
- Cấu hình Redis hiện tại (`docker-compose.yml`) dùng `--maxmemory-policy allkeys-lru` — dưới áp lực bộ nhớ, Redis có thể **tự động đuổi (evict)** các key ít dùng, bao gồm cả hàng đợi dispatch, với hệ quả tương tự.

**Đã sửa**: `RandomCheckQueueReconciliationRunner` (chạy 1 lần mỗi khi app khởi động) — đọc toàn bộ check đang `pending` (chưa gửi) từ DB, nạp lại vào hàng đợi Redis. An toàn để chạy lại nhiều lần (Redis `ZADD` trên 1 member đã tồn tại chỉ cập nhật lại score, không tạo trùng).

**Đã test sống**: khởi động lại `fams-api` → log xác nhận `"re-enqueued 10 pending check(s) into the Redis dispatch queue on startup"`.

**Chưa làm, mức độ thấp hơn**: chưa xử lý trường hợp hiếm hơn — app crash đúng lúc giữa bước ghi `status='sent'` vào DB và bước gửi thông báo thực tế (check đã ở trạng thái `sent` nhưng chưa thực sự thông báo) — không nằm trong reconciliation hiện tại (chỉ xử lý `pending`) vì re-queue 1 check `sent` có rủi ro gửi thông báo trùng. Xác suất xảy ra rất thấp (cửa sổ crash chỉ vài mili-giây giữa 2 thao tác), chưa cần ưu tiên.

## 13. [Đã sửa 01/08/2026] 4 điểm điều chỉnh trước production (theo yêu cầu Web/App)

Team Web/App gửi 4 yêu cầu điều chỉnh cần làm **trước khi lên production**, xếp theo P0/P1. Đã xác minh độc lập trên code thật trước khi sửa — cả 4 xác nhận đúng là vấn đề thật, đã sửa cả 4, test sống qua API/DB thật (kể cả gọi thật tới Firebase để xác nhận payload hợp lệ — token giả bị Firebase từ chối đúng lý do "token không hợp lệ", chứ không phải lỗi định dạng payload).

### 13.1 [P0 — đã sửa] `/my-pending` rò rỉ lịch kiểm tra tương lai

**Vấn đề**: `GET /scheduled-checks/my-pending` trả về **toàn bộ** check `pending` của nhân viên, không giới hạn theo thời gian — nhân viên gọi API ngay sau khi job đêm sinh lịch (01:00) có thể đọc được chính xác giờ của MỌI lần kiểm tra còn lại trong ngày, kể cả những lần cách hàng giờ. App có ẩn trên UI, nhưng dữ liệu vẫn nằm trong response — ai xem qua network traffic (rất dễ với app mobile) đều thấy được, phá vỡ hoàn toàn mục đích "ngẫu nhiên" (đối chiếu Deputy/QuickBooks Time: không hệ thống chấm công thực tế nào để nhân viên biết trước giờ kiểm tra đột xuất).

**Đã sửa**: bổ sung điều kiện `scheduledAt <= now + 60 giây` (cấu hình được qua `fams.randomcheck.my-pending.pending-lookahead-seconds`, mặc định 60 giây — đúng bằng chu kỳ quét của dispatch job, nghĩa là nhân viên không bao giờ biết trước quá khoảng thời gian mà dispatch job vốn dĩ đã sắp gửi thông báo rồi). Áp dụng cho **cả** luồng mặc định lẫn khi gọi tường minh `?status=pending` (chặn luôn đường vòng qua query param).

**Đã test sống**: check còn cách xa (nhiều giờ) → không xuất hiện trong response (`data: []`); cùng check đó đổi `scheduledAt` còn 30 giây → xuất hiện ngay. Check `sent` (đã thực sự gửi) không bị ảnh hưởng — luôn hiển thị bình thường.

### 13.2 [P1 — đã sửa] Bổ sung `eventType`/`checkId`/`siteId`/`expiresAt` vào FCM data payload

**Vấn đề**: thông báo `RANDOM_CHECK_SENT` trước đây chỉ có `title`/`body` (`FcmClient` chỉ gọi `Message.builder().setNotification(...)`, không có `.setData(...)`) — field `metadata` đã bổ sung ở đợt trước chỉ nằm trong bảng `notifications` (đọc được qua `GET /notifications`), không có trong chính gói push FCM. Hậu quả: khi app bị tắt hoàn toàn (không chỉ background), hệ điều hành tự hiện thông báo mà app không hề chạy để đọc `metadata` — bấm vào chỉ mở được app ở màn hình mặc định, không deep-link được.

**Đã sửa**: `FcmClient.sendToToken` có thêm overload nhận `Map<String,String> data`, gọi `Message.Builder.putAllData(...)` (FCM yêu cầu data luôn là String→String). `NotificationService.createNotification` giờ tự động chuyển `metadata` (Map<String,Object>) thành data payload, luôn kèm thêm `eventType` (dù caller có tự truyền hay không) để App luôn biết chắc sự kiện gì đã xảy ra ngay từ gói push, không cần đoán qua `title`.

**Đã test sống**: đăng ký 1 device token giả, kích hoạt manual check → xác nhận `notification_delivery_logs` ghi nhận lượt gửi thật **tới Firebase** (không phải giả lập nội bộ), Firebase từ chối đúng vì token giả không hợp lệ (`INVALID_ARGUMENT: The registration token is not a valid FCM registration token`) — chứng minh payload (bao gồm phần data mới) được xây dựng đúng định dạng và gửi thành công tới hạ tầng Firebase thật.

**Giới hạn**: mới áp dụng cho notification loại `RANDOM_CHECK_SENT` (do `RandomCheckDispatchService` là caller duy nhất truyền `metadata`) — các loại thông báo khác trong hệ thống vẫn chỉ có `title`/`body` như cũ trừ khi caller tương ứng cũng truyền `metadata`.

### 13.3 [P1 — đã sửa] Tách cài đặt in-app và push — lỗi "tắt inbox làm mất luôn push"

**Vấn đề nghiêm trọng đã xác nhận**: `NotificationService.createNotification` kiểm tra `isInAppEnabled` **trước**, và `return` ngay nếu tắt — khiến bước kiểm tra `isPushEnabled` (nằm phía sau) **không bao giờ được chạy tới**. Hậu quả: 1 nhân viên tắt "hiện trong inbox app" (in-app) cho loại thông báo nào đó sẽ **đồng thời mất luôn push** cho loại đó, dù bật push riêng — ngược với kỳ vọng thông thường (nhiều app cho phép "không hiện trong inbox nhưng vẫn báo khẩn qua push", ví dụ thông báo bảo mật/OTP). Schema DB đã có sẵn 2 cột độc lập (`in_app_enabled`, `push_enabled`) từ trước — lỗi chỉ nằm ở luồng code, không phải thiếu thiết kế DB.

**Đã sửa**: tách kiểm tra 2 điều kiện độc lập hoàn toàn — tạo dòng `Notification` (in-app) chỉ khi `inAppEnabled`, gửi push chỉ khi `pushEnabled`, không còn phụ thuộc lẫn nhau. Trường hợp in-app tắt nhưng push bật: không tạo dòng in-app (đúng ý người dùng — không muốn thấy trong inbox), nhưng push vẫn gửi bình thường.

**Đã test sống**: tắt `inAppEnabled` (giữ `pushEnabled=true`) cho `RANDOM_CHECK_SENT` → kích hoạt manual check → xác nhận **không** có dòng `notifications` mới được tạo, nhưng `notification_delivery_logs` **vẫn** ghi nhận 1 lượt gửi push mới (gửi thật tới Firebase, bị từ chối vì token giả — đúng như mục 13.2).

### 13.4 [Đã xác định + bổ sung] Thời hạn lưu và job xoá ảnh sinh trắc học

**Xác nhận trước khi sửa**: `fams-ai` lưu ảnh vô thời hạn ở 3 thư mục (`enrollments/`, `checkins/`, `liveness_challenges/`) — không có job dọn dẹp theo tuổi file ở bất kỳ đâu trong repo (Java lẫn Python). Không có con số retention nào được quyết định từ trước trong code/tài liệu (`face-id-management-api.md` ghi rõ đây là "quy trình tổ chức, chưa quyết định" — không có sẵn để tôi tự suy ra).

**Quyết định retention (cần bạn xác nhận lại, chưa phải con số pháp lý chính thức)**: chọn tạm **30 ngày** làm mặc định — tham khảo cùng con số đã dùng cho `delivery-log-days` trong chính hệ thống này (nhất quán nội bộ), và tương đồng khoảng retention phổ biến cho dữ liệu camera an ninh/chấm công ở nhiều tổ chức. **Đây là lựa chọn kỹ thuật tạm thời, không phải tư vấn pháp lý** — cấu hình được qua biến môi trường `DATA_RETENTION_BIOMETRIC_PHOTO_DAYS`, đổi được bất kỳ lúc nào không cần sửa code.

**Đã bổ sung**: job `DataRetentionJob` (chạy sẵn hàng tuần, Chủ Nhật 3h sáng — cùng lịch với việc dọn log/notification cũ) có thêm bước quét `checkins/` (ảnh chấm công + random-check) và `liveness_challenges/` (khung hình liveness), xoá file có tuổi > 30 ngày (theo `mtime`). Endpoint mới `POST /checkins/cleanup` bên `fams-ai`.

**Cố ý CHƯA xử lý `enrollments/`** (ảnh đăng ký Face ID ban đầu) trong đợt này — khác với 2 thư mục trên, 1 ảnh đại diện đang chờ HR duyệt (`pending_photo_path`) có thể tồn tại lâu hơn 30 ngày nếu HR chậm duyệt; xoá theo tuổi thuần tuý sẽ làm hỏng màn "Xem ảnh trước khi duyệt" mà HR không biết. Retention cho `enrollments/` cần logic nhận biết DB (bỏ qua ảnh còn đang được `pending_embedding`/`pending_photo_path` tham chiếu) — phức tạp hơn, để lại thành việc riêng (xem mục 14).

**Đã test sống**: tạo file test .jpg với `mtime` 40 ngày trước + 1 file mới trong cả 2 thư mục `checkins/`, `liveness_challenges/` → gọi `POST /checkins/cleanup?older_than_days=30` → xác nhận đúng: file cũ bị xoá, file mới còn nguyên.

## 14. Điểm còn lại — cần bạn quyết định (chưa làm)

| # | Vấn đề | Ghi chú |
|---|---|---|
| 1 | Ca làm việc qua đêm (`allowOvernight=true`) chưa được tính giao khung giờ với config (mục 4.2) — vẫn dùng thẳng khung config như trước | P2 — cần nếu tenant có nhiều ca đêm và muốn kiểm tra ngẫu nhiên áp dụng chính xác cho ca đêm |
| 2 | Thống kê số nhân viên chưa đăng ký Face ID theo site (Web P2, mục "Thống kê Face ID theo site") — hiện chưa có aggregate endpoint theo site/assignment | P2 — cải tiến trải nghiệm, không ảnh hưởng tính đúng vì hệ thống đã fail-safe với người chưa enrolled (mục 3) |
| 3 | `processedAt` (thời điểm AI xử lý xong) và `processingStatus="failed"` riêng biệt cho lỗi hạ tầng AI (App P0, phần giản lược ở mục 10.4) | Cần thêm cột DB + truyền `errorCode` vào `check_responses` — báo tôi nếu App thực sự cần phân biệt "AI lỗi" với "đang chờ" |
| 4 | Reconciliation (mục 12.2) chưa xử lý check ở trạng thái `sent` bị kẹt giữa lúc app crash | P2 — xác suất cực thấp, chưa cần ưu tiên |
| 5 | Retention cho `enrollments/` (ảnh đăng ký Face ID ban đầu) — cần logic nhận biết DB, chưa làm trong mục 13.4 | Báo tôi nếu cần ưu tiên — phức tạp hơn 2 thư mục đã xử lý vì có trạng thái "đang chờ duyệt" |
| 6 | Con số retention 30 ngày (mục 13.4) là lựa chọn kỹ thuật tạm, chưa qua xác nhận pháp lý/chính sách chính thức | Cần bạn xác nhận lại với bộ phận pháp lý/tuân thủ nếu có, đổi qua biến môi trường không cần sửa code |

## 15. API tham chiếu

| Endpoint | Method | Thay đổi |
|---|---|---|
| `/random-check-configs/tenant-default` | POST/GET | Không đổi |
| `/random-check-configs/sites/{siteId}` | POST/GET | Không đổi |
| `/random-check-configs/sites/{siteId}/effective` | GET | **Mới** — mục 6 |
| `/random-check-configs/{configId}` | GET/PUT/DELETE | Không đổi |
| `/random-check-configs/{configId}/applicable-roles` | PUT | Không đổi |
| `/random-check-configs/{configId}/check-mode` | PUT | Không đổi |
| `/scheduled-checks/generate` | POST | Hành vi thay đổi — giờ bỏ qua nhân viên đã nghỉ việc (mục 2) và ràng buộc theo giờ ca thực tế (mục 4) |
| `/scheduled-checks/{checkId}/respond` | POST | Hành vi thay đổi — kiểm tra Face ID đã đăng ký trước khi verify (mục 3), thực thi đúng liveness (mục 1) |
| `/scheduled-checks/manual` | POST | Hành vi thay đổi — `reason` bắt buộc (mục 8) |
| `/internal/ai-callback/face-result` | POST | Hành vi thay đổi — mục 1 |
| `DELETE /sites/{siteId}` | DELETE | Hành vi thay đổi — dọn config override kèm theo (mục 5) |
| `/attendance/*`, `/reports/attendance/*` | — | Field mới `hasRandomCheckFailure`/`daysWithRandomCheckFailure`/`exceedsRandomCheckFailureThreshold` — mục 7 |
| `/scheduled-checks/{checkId}` (detail) | GET | Field mới `manualReason`/`triggeredBy`; `faceVerifyScore` giờ có giá trị thật thay vì luôn `null` — mục 10.1, 10.2 |
| `/scheduled-checks` (list) | GET | Field mới `employeeName`/`siteName`/`outcome`/`failureReason` trên mỗi dòng — mục 10.3 |
| `/scheduled-checks/{checkId}/my-result` | GET | **Mới** — endpoint nhân viên tự poll kết quả, không cần quyền HR — mục 10.4 |
| `/notifications` (list) | GET | Field mới `metadata` (chỉ có giá trị cho `RANDOM_CHECK_SENT` hiện tại) — mục 10.5 |
| `/scheduled-checks/{checkId}/photo` | GET | **Mới** — HR xem ảnh selfie bằng chứng — mục 11.1 |
| `CheckResponseDto` (nested trong detail/respond) | — | Field mới `hasPhotoEvidence` — mục 11.1 |
| `ai-service: /checkins/{source_id}/photo` | GET | **Mới**, nội bộ (X-Internal-Secret) — mục 11.1 |
| `PATCH /employees/{employeeId}/status` (`terminated`) | PATCH | Hành vi thay đổi — giờ tự huỷ mọi scheduled check `pending`/`sent` của nhân viên, không đổi request/response — mục 12.1 |
| `/scheduled-checks/my-pending` | GET | Hành vi thay đổi — check `pending` chỉ hiện khi `scheduledAt` còn ≤ 60 giây nữa (cấu hình `fams.randomcheck.my-pending.pending-lookahead-seconds`), áp dụng cả mặc định lẫn `?status=pending`; check `sent` không đổi — mục 13.1 |
| FCM push (mọi `sendPush`) | — | Gói push giờ kèm `data` payload (`eventType` luôn có; `checkId`/`siteId`/`expiresAt`/... theo `metadata` của caller) bên cạnh `title`/`body` — hiện chỉ `RANDOM_CHECK_SENT` truyền đủ metadata — mục 13.2 |
| Tạo notification (mọi luồng qua `NotificationService.createNotification`) | — | Hành vi thay đổi — `inAppEnabled`/`pushEnabled` giờ xét độc lập, tắt in-app không còn chặn push — mục 13.3 |
| `ai-service: /checkins/cleanup` | POST | **Mới**, nội bộ (X-Internal-Secret) — job dọn ảnh checkin/random-check + liveness cũ hơn N ngày, gọi hàng tuần bởi `DataRetentionJob` — mục 13.4 |

Migration `V80__random_check_failure_tracking_and_manual_reason.sql` — thêm `attendance_summaries.has_random_check_failure`, `random_check_configs.failure_escalation_threshold`, `scheduled_checks.manual_reason`/`triggered_by`.
Migration `V81__notification_metadata_and_check_list_hydration.sql` — thêm `notifications.metadata` (JSONB, nullable).
Migration `V82__check_response_photo_evidence_flag.sql` — thêm `check_responses.photo_submitted`.
Mục 12 (huỷ check khi nghỉ việc, reconciliation hàng đợi Redis), mục 13 (my-pending time-bound, FCM data payload, in-app/push độc lập, biometric photo retention) không có migration — thuần sửa logic Java/Python + 1 cột config mới trong `application.yml` (`app.data-retention.biometric-photo-days`).
Migration V33-V79 trước đó không đổi.
