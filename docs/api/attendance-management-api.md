# Tài liệu Attendance Summary / Bảng công — Review, sửa lỗi và API tham chiếu

> Cập nhật theo code đang chạy ngày 31/07/2026 (bản vá lần 2, dựa trên báo cáo audit code từ team Web/App). Base path: `/api/v1/tenants/{tenantId}/attendance`.

## 0.b Bản vá lần 2 (31/07/2026) — theo báo cáo audit từ team Web/App

Team Web và App tự audit code (không phải test hộp đen) module attendance vừa bàn giao, phát hiện thêm 4 vấn đề. Đã xác minh độc lập từng vấn đề trên code thật + query DB thật trước khi sửa (không tin báo cáo mù quáng) — cả 4 đều xác nhận đúng, đã sửa cả 4, test sống qua API thật.

| # | Vấn đề | Mức độ | Trạng thái |
|---|---|---|---|
| 1 | `POST /attendance/recompute` không lọc theo tenant — HR/Admin bất kỳ tenant nào cũng có thể trigger recompute đụng vào dữ liệu chấm công của TENANT KHÁC | **Nghiêm trọng — vi phạm cách ly dữ liệu multi-tenant** | **Đã sửa** — mục 7 |
| 2 | Dữ liệu lịch sử (trước bản vá lần 1, migration V79) chưa được backfill lại — `has_pending_review_session`/`has_rejected_session` vẫn `false` trên toàn bộ 146 ngày có phiên `pending_review` thật | Dữ liệu sai, ảnh hưởng payroll | **Đã sửa** — mục 8, đã backfill xong |
| 3 | `ReportService` (2 API `/reports/attendance/monthly`, `/reports/attendance/export`) — dùng permission `reports:export` thay vì `attendance:export` đã seed sẵn; KHÔNG có site-scope enforcement (SITE_SUPERVISOR xem/xuất được dữ liệu ngoài site được giao); dùng cách gộp nhóm trong Java (không phải DB); Excel export chỉ có UUID thô, không có tên | Nghiêm trọng (phân quyền) + hiệu năng + UX | **Đã sửa** — mục 9 |
| 4 | Không có cách nào để HR "mở khóa" 1 bản ghi đã `adjust` thủ công khi có dữ liệu mới hợp lệ đến muộn — muốn cập nhật phải sửa tay DB | Thiếu tính năng | **Đã bổ sung** — mục 10 |

## 7. [Nghiêm trọng — đã sửa] `/attendance/recompute` không cách ly tenant

### 7.1 Phát hiện

`AttendanceSummaryController.recomputeForDate` gọi thẳng `AttendanceSummaryService.recomputeForDate(LocalDate)` — hàm này **không nhận tham số tenantId**, truy vấn `CheckinRepository.findCheckinsBetween` lấy TẤT CẢ check-in trong khoảng thời gian, không lọc tenant. Hàm này vốn được thiết kế cho `AttendanceSummaryJob` (job đêm hệ thống, chạy cho mọi tenant là đúng) — nhưng endpoint HR-facing lại tái sử dụng thẳng, nghĩa là bất kỳ HR/Admin nào (kể cả site-scoped supervisor có quyền `attendance:list`) gọi API này đều vô tình recompute lại dữ liệu của MỌI tenant khác cho ngày đó.

### 7.2 Đã sửa

- `CheckinRepository` bổ sung `findCheckinsBetweenForTenant(tenantId, siteId, from, to)` — có lọc tenant (và site nếu truyền vào); `findCheckinsBetween` cũ giữ nguyên, ghi rõ Javadoc chỉ được dùng bởi `AttendanceSummaryJob`.
- `AttendanceSummaryService` thêm `triggerRecompute(tenantId, siteId, date, callerUserId, callerIsPlatformAdmin)` — endpoint-facing, tự kiểm tra quyền + site-scope (dùng lại `resolveSiteFilter` sẵn có), gọi query đã lọc tenant ở trên.
- Controller đổi sang gọi `triggerRecompute`, không còn gọi trực tiếp hàm job-only.

**Đã test sống**: tạo 2 tenant, gọi recompute cho tenant A ngày X → xác nhận `updated_at` của các bản ghi tenant B cùng ngày **không đổi** (so sánh timestamp trước/sau, không suy đoán).

## 8. [Đã bổ sung] Backfill dữ liệu lịch sử — endpoint platform-admin

Bản vá lần 1 (mục 1) sửa logic `recompute()` để loại trừ phiên `pending_review`/`rejected`, nhưng KHÔNG tự động chạy lại cho dữ liệu đã tồn tại từ trước migration V79 — các bản ghi `attendance_summaries` cũ vẫn giữ số liệu tính theo logic CŨ (đã tính cả phiên chưa duyệt) cho tới khi có gì đó trigger recompute lại đúng ngày/nhân viên/site đó.

Bổ sung `POST /api/v1/platform/attendance/backfill?from=...&to=...` (PLATFORM_ADMIN only, cross-tenant theo đúng bản chất của thao tác backfill/migration dữ liệu) — chạy lại `recomputeForDate` cho từng ngày trong khoảng, tái sử dụng nguyên logic recompute chuẩn (không viết công thức SQL riêng), bỏ qua các bản ghi đã bị HR `adjust` tay (cùng cơ chế bảo vệ như mọi đường recompute khác). Trả về `{daysProcessed, daysFailed}`.

**Đã chạy trên dữ liệu thật**: `backfill?from=2026-06-25&to=2026-07-31` (37 ngày, 0 lỗi) → xác nhận số bản ghi `has_pending_review_session=true` từ 0 lên đúng 146 (khớp 100% với số check-in `pending_review` thật trong DB).

## 9. [Nghiêm trọng — đã sửa] `ReportService` — permission sai, thiếu site-scope, hiệu năng, thiếu tên

Áp dụng cho `GET /reports/attendance/monthly` và `GET /reports/attendance/export` (khác base path với `/attendance/...` ở trên — thuộc domain "reports", dùng khi HR cần báo cáo/xuất Excel, không phải xem trực tiếp).

### 9.1 Phát hiện (đối chiếu code, không suy đoán)

- **Permission sai**: `exportMonthlyAttendance` kiểm tra `reports:export`, nhưng migration `V13__seed_roles_and_permissions.sql` đã tạo và gán cho các role permission `attendance:export` riêng cho đúng hành động này — permission đó tồn tại trong DB nhưng chưa từng được code nào kiểm tra tới, vô dụng từ lúc seed.
- **Thiếu site-scope hoàn toàn**: cả 2 hàm không gọi `SiteScopeService` dù đã có sẵn trong constructor — 1 SITE_SUPERVISOR chỉ được giao 1 site vẫn có thể truyền `siteId` bất kỳ (hoặc bỏ trống để xem tất cả site) mà không bị chặn.
- **Hiệu năng**: dùng `summaryRepository.findAll(spec)` tải toàn bộ dữ liệu tháng vào RAM rồi gộp nhóm bằng `LinkedHashMap` trong Java — đúng vấn đề đã sửa ở mục 3 cho `AttendanceSummaryService`, nhưng bị bỏ sót vì đây là 1 bản triển khai riêng, trùng lặp logic trong `ReportService`.
- **Excel không có tên**: cột "Employee ID"/"Site ID" là UUID thô — HR không dùng được trực tiếp để đối chiếu bảng lương.

### 9.2 Đã sửa

- `exportMonthlyAttendance` đổi sang kiểm tra `attendance:export`.
- Cả 2 hàm dùng site-scope helper riêng (`resolveSiteFilterForReports`, cùng nguyên tắc với `resolveSiteFilter` của `AttendanceSummaryService`) — site-scoped caller bị chặn nếu truyền `siteId` ngoài phạm vi, trả rỗng nếu không có site nào được phép.
- Cả 2 chuyển sang dùng `AttendanceSummaryRepository.aggregateMonthly` (native SQL `GROUP BY`) thay vì gộp nhóm trong Java.
- Excel export: thêm cột Employee Code, Employee Name, Site Name (hydrate qua `EmployeeRepository`/`SiteRepository`), thêm cột "Days With Pending Review"/"Days With Rejected Session", thêm hàng meta đầu file ghi rõ thời điểm xuất + tổng số dòng còn cảnh báo.
- `MonthlyAttendanceReportResponse` (JSON) bổ sung `totalRowsWithPendingReview`/`totalRowsWithRejectedSession` — HR thấy ngay số nhân viên có dữ liệu chưa chốt mà không phải rà từng dòng.
- **Guard xuất file mới**: nếu bất kỳ dòng nào trong phạm vi (tháng + site filter) còn `daysWithPendingReview>0` hoặc `daysWithRejectedSession>0`, `export` trả lỗi `409 ATTENDANCE_NOT_READY` (kèm số liệu cụ thể + thông báo tiếng Việt) thay vì âm thầm xuất file có thể sai số. HR xác nhận muốn xuất dù vậy thì gọi lại kèm `confirmDespiteWarnings=true`.

**Đã test sống**: gọi export không kèm `confirmDespiteWarnings` trên tenant có 12/12 dòng pending-review → nhận đúng `409 ATTENDANCE_NOT_READY`; gọi lại kèm `confirmDespiteWarnings=true` → nhận file `.xlsx` hợp lệ (xác nhận bằng `file` command, đúng định dạng Excel 2007+); `/reports/attendance/monthly` trả đúng tên nhân viên/site đã hydrate và `totalRowsWithPendingReview=12` khớp dữ liệu thật.

## 10. [Đã bổ sung] Unlock + recompute cho bản ghi đã HR điều chỉnh tay

Bản vá lần 1 (mục 2) làm cho `adjustmentReason != null` bảo vệ vĩnh viễn 1 bản ghi khỏi bị recompute tự động ghi đè — đúng mục tiêu chống mất quyết định của HR, nhưng chưa có đường nào để HR **chủ động** gỡ bảo vệ đó khi dữ liệu mới hợp lệ đến muộn (ví dụ: 1 checkin đồng bộ offline trễ vài ngày, hoặc HR duyệt lại 1 phiên `pending_review` sau khi đã lỡ điều chỉnh tay).

Bổ sung `POST /attendance/{summaryId}/unlock-and-recompute` (quyền `attendance:list`, cùng site-scope check với `/adjust`), body bắt buộc `{"reason": "..."}`:

1. Xóa `adjustmentReason` (gỡ khóa).
2. Gọi lại `recompute()` chuẩn cho đúng employee/site/ngày đó.
3. Ghi 1 dòng vào `audit_logs` (bảng audit chung sẵn có, dùng lại `AuditLogService`) — `action=attendance_summary_unlock_and_recompute`, `oldValue` chứa lý do điều chỉnh cũ + `totalWorkMinutes` TRƯỚC khi mở khóa, `newValue` chứa lý do mở khóa + `totalWorkMinutes` SAU khi recompute — đủ để trả lời "ai mở khóa, khi nào, vì sao, số liệu đổi thế nào".

**Đã test sống**: `adjust` 1 bản ghi thành `totalWorkMinutes=777` (khóa lại) → `unlock-and-recompute` → xác nhận response trả về `totalWorkMinutes=480` (giá trị tính lại từ dữ liệu check-in thật, không phải 777) và `adjustmentReason=null`; kiểm tra trực tiếp bảng `audit_logs` xác nhận `oldValue.totalWorkMinutes=777`, `newValue.totalWorkMinutes=480` — đúng giá trị TRƯỚC/SAU, không bị lẫn do cùng 1 entity instance trong Hibernate persistence context (lỗi này gặp phải khi code lần đầu, đã sửa bằng cách chụp giá trị cũ vào biến local trước khi gọi recompute).

## 11. Điểm còn lại

### 11.1 [Đã xác minh 31/07/2026 — KHÔNG phải rủi ro, đóng lại] Split-shift (2 ca khác nhau/ngày)

Câu hỏi đặt ra ở bản vá trước: hệ thống có cho 1 nhân viên 2 ca KHÁC NHAU trong cùng 1 ngày không (vì `recompute()` chỉ dùng snapshot ca của phiên chấm công ĐẦU TIÊN trong ngày để tính đi muộn/về sớm/OT cho cả ngày — nếu có 2 ca khác nhau thật, phần tính cho phiên sau sẽ sai giờ ca).

**Đã kiểm tra trực tiếp trong schema — câu trả lời là KHÔNG, tình huống này bị chặn ở tầng database:**

```sql
-- V26__create_assignments.sql
-- An employee may only have one active assignment per site at a time
CREATE UNIQUE INDEX uq_assignments_employee_site_active
    ON assignments(employee_id, site_id)
    WHERE status = 'active' AND deleted_at IS NULL;
```

1 nhân viên chỉ có thể có **đúng 1 assignment (= 1 ca) đang active tại 1 site, tại 1 thời điểm** — ràng buộc unique index cấp DB, không phải chỉ kiểm tra ở code, nên không có đường nào (kể cả do bug ở tầng ứng dụng) tạo ra được 2 ca khác nhau cùng lúc cho 1 nhân viên tại 1 site. Vì `recompute()` gộp nhóm theo đúng `employeeId + siteId + date`, trong phạm vi 1 nhóm chỉ có thể tồn tại 1 ca active tại một thời điểm — không phải rủi ro thật.

**Vẫn còn 1 edge case thật, nhưng hiếm và hệ quả nhỏ hơn nhiều**: nếu HR **đổi ca cho nhân viên NGAY TRONG NGÀY** (kết thúc assignment cũ, tạo assignment mới với shift khác — cùng site, cùng ngày; ràng buộc unique chỉ chặn 2 assignment active CÙNG LÚC, không chặn đổi nối tiếp trong ngày), các phiên trước/sau lúc đổi sẽ mang 2 snapshot ca khác nhau, và `recompute()` vẫn chỉ dùng ca của phiên đầu tiên cho cả ngày. **Quyết định: không sửa** — tần suất gần như bằng 0 (đổi ca thường áp dụng từ ngày hôm sau, không phải giữa ca đang làm), và nếu gặp thật, HR có thể tự sửa qua `/adjust` như mọi trường hợp bất thường khác. Muốn xử lý triệt để sẽ cần đổi hẳn model summary từ "1 dòng/ngày" sang "1 dòng/đoạn-assignment" — chi phí lớn hơn nhiều lần so với lợi ích thực tế của 1 edge case gần như không xảy ra.

### 11.2 Còn lại — cần bạn quyết định (chưa làm)

| # | Vấn đề | Ghi chú |
|---|---|---|
| 1 | `hasNewSourceDataAfterAdjustment`/`adjustedAt` — phát hiện dữ liệu mới đến SAU khi đã `adjust`, hiện chỉ phát hiện được gián tiếp qua việc tự chạy lại `/recompute` hoặc dùng endpoint `unlock-and-recompute` mới (mục 10) rồi so sánh | P1, có thể bổ sung sau nếu HR thực sự cần cảnh báo chủ động thay vì phải tự kiểm tra |
| 2 | `daysWithManualAdjustment` — rollup số ngày trong tháng đã bị HR `adjust` tay, hiện chưa có trên API tháng | P2 |
| 3 | 2 lỗi fixture test có từ trước (`test_late_detection.sh`, `test_ot_minutes.sh`) — dữ liệu setup ca làm không hợp lệ, không liên quan tới code, chưa sửa lần này | Việc dọn dẹp test, không ảnh hưởng chức năng thật |

## 0. Tóm tắt kết quả

Bạn đưa ra 11 tính năng liên quan tới hiển thị kết quả chấm công, lịch sử, bảng công ngày/tháng, và tự động tính đi muộn/về sớm/OT/thiếu checkout. Đối chiếu với hệ thống chấm công thực tế (QuickBooks Time, Deputy — đã dùng làm tham chiếu suốt các đợt review trước) phát hiện: **cả 11 tính năng đã được xây dựng từ trước** (không phải làm mới), nhưng review sâu vào logic tính toán phát hiện **2 lỗi nghiệp vụ nghiêm trọng** — đã sửa cả 2 — cùng vài điểm cần nâng cấp đã ghi nhận nhưng chưa làm (cần bạn quyết định).

| # | Tính năng | Trạng thái trước | Việc đã làm |
|---|---|---|---|
| 1 | Hiển thị kết quả check-in/out | Đã có, đúng | Xác nhận, không đổi |
| 2 | Nhân viên xem lịch sử chấm công | Đã có, đúng | Xác nhận, không đổi |
| 3 | HR xem danh sách check-in (tìm/lọc/sort/trang) | Đã có, đúng | Xác nhận, không đổi |
| 4 | HR xem chi tiết 1 check-in | Đã có, đúng | Xác nhận, không đổi |
| 5 | Tự động tạo attendance summary | Đã có **nhưng sai logic nghiêm trọng** | **Đã sửa** — mục 1 |
| 6 | Tính đi muộn | Đã có, logic đúng | Kế thừa đúng bản vá mục 1 |
| 7 | Tính về sớm | Đã có, logic đúng | Kế thừa đúng bản vá mục 1 |
| 8 | Tính OT | Đã có, logic đúng | Kế thừa đúng bản vá mục 1 |
| 9 | Phát hiện thiếu checkout | Đã có, logic đúng | Kế thừa đúng bản vá mục 1 |
| 10 | Nhân viên xem bảng công ngày/tháng | Đã có, đúng | Bổ sung field minh bạch — mục 1.3 |
| 11 | HR xem bảng công tổng hợp | Đã có, đúng nhưng có rủi ro quy mô | **Đã sửa** — mục 3 |

**Kết quả test**: build lại `fams-api`, test sống qua API thật (không mock) xác nhận cả 3 điểm sửa đúng — dựng 1 phiên chấm công thật, HR từ chối (`rejected`) → bảng công tự động loại trừ đúng; HR điều chỉnh thủ công (`adjust`) → gọi lại `/recompute` không còn ghi đè âm thầm; bảng công tháng HR phân trang đúng trên dữ liệu seed thật (13 nhóm employee+site → đúng 3 trang). Chạy lại `tests/attendance/*.sh` (9 file, sửa thêm 2 lỗi kịch bản test có từ trước không liên quan) — 7/9 file pass 100% (54/54 test), 2 file còn lại (`test_late_detection.sh`, `test_ot_minutes.sh`) thất bại do dữ liệu fixture test tự thân có vấn đề (giờ ca không hợp lệ), không liên quan tới thay đổi lần này.

## 1. [Lỗi nghiêm trọng #1 — đã sửa] Attendance summary tính cả phiên `pending_review`/`rejected`

### 1.1 Phát hiện

`AttendanceSummaryService.recompute()` gộp **TẤT CẢ** phiên chấm công của 1 ngày để tính `totalWorkMinutes`, `isLate`, `isEarlyLeave`, `otMinutes`, `missingCheckout` — không lọc theo `status` của từng phiên (`CheckinRepository.findSessionsForDateRange` không có điều kiện `status`). Nghĩa là:

- 1 phiên `pending_review` (đang chờ HR xem lại — geofence sai, xác thực khuôn mặt thất bại...) vẫn được tính đầy đủ vào giờ công/OT/đi muộn.
- 1 phiên `rejected` (HR đã xác nhận là gian lận, ví dụ đồng nghiệp bấm hộ) **vẫn được tính** như phiên hợp lệ.

Điều này **trực tiếp mâu thuẫn với chính lời hứa hệ thống đã đưa ra cho nhân viên** — khi checkout rơi vào `pending_review`, message trả về nói rõ: *"Check-out recorded, but needs HR review... **This won't affect your pay until reviewed**"* (`CheckinService.resolveDisplayMessage`). Trên thực tế, trước khi sửa, nó **VẪN** ảnh hưởng ngay tới bảng công/giờ làm — lời hứa đó không đúng với những gì code thực sự làm.

### 1.2 Đối chiếu thực tế

Deputy, QuickBooks Time đều có khái niệm "unconfirmed/disputed time entry" — không tính vào giờ công chính thức cho tới khi được duyệt. Việc phiên `rejected` (đã xác nhận gian lận) không được loại trừ là lỗi rõ ràng theo bất kỳ chuẩn nào.

### 1.3 Đã sửa

`recompute()` giờ chỉ gộp phiên có `status='valid'` vào mọi phép tính (giờ công, đi muộn, về sớm, OT, thiếu checkout). Thêm 2 field mới trên `AttendanceSummary` để **không âm thầm giấu** việc loại trừ này khỏi HR/nhân viên:

- `hasPendingReviewSession` (boolean) — có ít nhất 1 phiên đang chờ duyệt trong ngày đó → số liệu ngày này **có thể thấp hơn thực tế**, chưa chốt.
- `hasRejectedSession` (boolean) — có ít nhất 1 phiên đã bị HR từ chối trong ngày đó (đã bị loại khỏi mọi số liệu).

Cả 2 field có mặt trên `AttendanceSummaryResponse` (theo ngày) và rollup thành `daysWithPendingReview`/`daysWithRejectedSession` trên 2 API tổng hợp tháng (`AttendanceMonthlyResponse`, `AttendanceHrMonthlyResponse`) — HR xuất bảng lương nên lọc/kiểm tra kỹ những ngày có `daysWithPendingReview > 0` trước khi chốt.

**Trường hợp đặc biệt**: nếu ngày đó CHỈ có phiên `pending_review`/`rejected` (không có phiên `valid` nào), summary vẫn được tạo/cập nhật với `totalWorkMinutes=0, sessionCount=0` (không bỏ qua như trước) — tránh để lại dữ liệu cũ (từ lúc phiên còn `valid`) bị "mồ côi" không được làm mới khi trạng thái đổi sau đó.

**Đã test sống**: tạo 1 phiên chấm công thật, checkout xong (bảng công ghi nhận đúng `sessionCount=1`), sau đó HR override phiên đó thành `rejected` → bảng công tự động recompute lại đúng `sessionCount=0`, `hasRejectedSession=true`.

## 2. [Lỗi nghiêm trọng #2 — đã sửa] Điều chỉnh thủ công của HR bị ghi đè âm thầm

### 2.1 Phát hiện

`PATCH .../attendance/{id}/adjust` cho HR sửa tay số liệu (kèm `reason` bắt buộc, dùng khi giải quyết tranh chấp/lỗi dữ liệu). Nhưng `recompute()` **ghi đè vô điều kiện** mọi field đã tính mỗi khi được gọi lại — mà `recompute()` có thể tự động chạy lại bất cứ lúc nào: 1 lượt đồng bộ offline đến trễ (nhân viên mất mạng, đồng bộ lại vài ngày sau), job đêm chạy lại, hoặc HR khác gọi `/recompute` cho ngày đó. Bất kỳ trường hợp nào trong số này **âm thầm xóa mất quyết định của HR** — không cảnh báo, không ghi log rõ ràng, và `adjustment_reason` vẫn còn nguyên trong DB nhưng giờ mô tả sai lệch (nói "đã điều chỉnh" trong khi số liệu đã bị viết đè về giá trị tính lại).

### 2.2 Đã sửa

`recompute()` giờ kiểm tra: nếu bản ghi summary đã có `adjustmentReason` khác null (tức đã từng được HR điều chỉnh tay), **bỏ qua** lượt ghi đè tự động đó, chỉ ghi log thông tin. HR muốn chấp nhận số liệu mới phải chủ động gọi lại `/adjust` (xóa hoặc cập nhật `reason`) — không có đường nào tự động âm thầm ghi đè quyết định đã có audit trail.

**Đã test sống**: HR điều chỉnh 1 bản ghi (`totalWorkMinutes=480`, `reason="..."`) → gọi `/recompute` thủ công cho đúng ngày đó → xác nhận `totalWorkMinutes` giữ nguyên 480, log ghi rõ *"Skipping auto-recompute for HR-adjusted summary"*.

## 3. [Đã sửa] HR bảng công tháng — chuyển sang gộp nhóm + phân trang ở tầng DB

`listMonthlyAttendance` trước đây tải **toàn bộ** dữ liệu tháng của tenant (mọi nhân viên × mọi site × 30 ngày) vào bộ nhớ Java rồi mới gộp nhóm theo `employeeId+siteId` và tự cắt trang (`subList`) — với tenant nhiều nhân viên, mỗi lần HR mở màn bảng công tháng sẽ tải cả nghìn dòng vào RAM dù chỉ hiển thị 20-50 dòng/trang.

Đã viết lại bằng 1 câu SQL native `GROUP BY employee_id, site_id` (kèm `countQuery` riêng để phân trang chính xác), trả về đúng số dòng cần cho từng trang — không đổi API endpoint, không đổi response shape (`AttendanceHrMonthlyResponse`), chỉ đổi cách truy vấn bên trong (`AttendanceSummaryRepository.aggregateMonthly`, migration không cần thiết vì không đổi schema).

**Đã test sống**: gọi `/attendance/monthly?year=2026&month=7&size=5` trên dữ liệu seed thật (13 nhóm employee+site) → đúng 5 dòng/trang, `totalElements=13`, `totalPages=3`, tên nhân viên/site hydrate đúng. Chạy lại `test_hr_monthly.sh` — 10/10 pass, không hồi quy.

### Điểm còn lại — edge case, chưa sửa (mức độ thấp)

| # | Vấn đề | Mức độ | Ghi chú |
|---|---|---|---|
| 1 | 1 ngày có 2 phiên thuộc **2 ca khác nhau** (split-shift) — chỉ ca của phiên ĐẦU TIÊN có snapshot được dùng để tính đi muộn/về sớm cho **cả ngày** | **Đã xác minh (31/07/2026): không phải rủi ro thật** | Xem mục 11.1 — bị chặn ở tầng DB (`uq_assignments_employee_site_active`), 1 nhân viên chỉ có đúng 1 assignment active/site tại 1 thời điểm. Vẫn còn 1 edge case rất hiếm (đổi ca giữa ngày) — không sửa, xử lý bằng `/adjust` nếu gặp. |
| 2 | `missing_checkout=true` chưa có test tự động end-to-end xác nhận (chỉ test được nhánh `false`) | Thiếu test coverage | Cần fixture "ngày trong quá khứ có phiên mở" — có thể bổ sung sau nếu cần |

## 4. Ghi chú quan trọng cho FE/xuất lương — `totalWorkMinutes` đã bao gồm OT

`totalWorkMinutes` là **tổng giờ làm đã tính đủ**, bao gồm cả phần vượt ca (OT) nếu có — KHÔNG được cộng thêm `otMinutes` lên trên `totalWorkMinutes` khi tính lương, vì đó là double-count. `otMinutes` chỉ là **phần breakdown** cho biết trong tổng đó, bao nhiêu phút là OT — đã ghi rõ vào Swagger description của `AttendanceSummaryResponse.totalWorkMinutes`.

## 5. API tham chiếu (không đổi endpoint, chỉ bổ sung field)

| Endpoint | Method | Ai gọi | Field mới |
|---|---|---|---|
| `/attendance` | GET | HR (`attendance:list`) | `hasPendingReviewSession`, `hasRejectedSession` trên mỗi dòng |
| `/attendance/{id}` | GET | HR (`attendance:read`) | như trên |
| `/attendance/me` | GET | Nhân viên (tự động) | như trên |
| `/attendance/monthly` | GET | HR (`attendance:list`) | `daysWithPendingReview`, `daysWithRejectedSession` |
| `/attendance/me/monthly` | GET | Nhân viên (tự động) | như trên + `dailySummaries[]` có field mới |
| `/attendance/{id}/adjust` | PATCH | HR (`attendance:list`) | Không đổi request; giờ được **bảo vệ** khỏi bị ghi đè tự động |
| `/attendance/recompute` | POST | HR (`attendance:list`) | Không đổi; giờ tôn trọng bản ghi đã adjust |

Migration `V79__attendance_summary_status_filtering_and_adjustment_protection.sql` — thêm 2 cột `has_pending_review_session`, `has_rejected_session` vào `attendance_summaries` (mặc định `false`, không phá dữ liệu cũ).

## 6. Xác nhận thêm — không cần sửa

- **Snapshot ca làm dùng đúng dữ liệu tại thời điểm check-in** (không re-fetch Shift sống) — đã đúng từ trước (nguyên tắc V72), late/early/OT không bị ảnh hưởng nếu Shift bị sửa sau đó.
- **Xử lý ca qua đêm (overnight)** — đã đúng, `shiftEnd` cộng thêm 1 ngày khi `allowOvernight=true`.
- **`missing_checkout` chỉ đánh dấu cho ngày trong QUÁ KHỨ** — đúng thiết kế, tránh báo sai cho nhân viên đang trong ca dở dang hôm nay. Cơ chế thực tế: cả real-time (mỗi lần check-in/checkout) lẫn job đêm (01:00 UTC) — nhưng trên thực tế chỉ job đêm mới thực sự "chốt" được cờ này cho 1 ngày đã qua, vì không còn sự kiện nào khác chạm vào ngày đó nữa.
- **Phân quyền/site-scope** trên mọi endpoint HR — đã đúng, SITE_SUPERVISOR bị giới hạn site không xem được dữ liệu ngoài phạm vi, trả rỗng thay vì lỗi khi không có site nào được phép.
- **Cách ly employee tự xem** (`/attendance/me`) — chỉ thấy đúng dữ liệu của chính mình, không có tham số `employeeId` để dò người khác.
