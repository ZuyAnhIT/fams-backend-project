# Báo cáo audit nghiệp vụ: Báo cáo (Reports) + Tìm kiếm nhanh (Search)

**Ngày:** 2026-08-05
**Phạm vi:** 7 user story — báo cáo công ngày/tháng, export Excel, báo cáo vi phạm theo kỳ, báo cáo hiện diện site, báo cáo Face ID, tìm kiếm nhanh.
**Tham chiếu thực tế:** Deputy (exception severity classification), BambooHR/ADP (payroll-ready export guard — đã có sẵn từ trước), Connecteam (site presence real-time cho supervisor).

---

## 1. Tóm tắt kết quả

Cả 6 endpoint báo cáo + tìm kiếm nhanh đã tồn tại sẵn trong code, khớp gần đúng với 7 story ngay từ đầu. Audit phát hiện 3 vấn đề thật:

| # | Hạng mục | Trước audit | Sau audit |
|---|---|---|---|
| 1 | **Rò rỉ dữ liệu xuyên site (nghiêm trọng)** — báo cáo công ngày, báo cáo vi phạm, báo cáo hiện diện site, tìm kiếm nhanh | 🔴 Người dùng bị giới hạn site (SITE_SUPERVISOR) thấy dữ liệu TOÀN TENANT, không chỉ site được giao | ✅ Đã sửa đồng bộ cả 4 endpoint, verify trực tiếp bằng tài khoản supervisor thật |
| 2 | Site Supervisor không truy cập được báo cáo hiện diện site dù story yêu cầu | 🔴 Thiếu hẳn quyền `reports:list` | ✅ Cấp qua V84 — chỉ sau khi đã sửa xong mục 1, tránh mở lỗ hổng mới |
| 3 | Báo cáo vi phạm thiếu breakdown theo severity | 🟡 Story yêu cầu rõ nhưng hệ thống không có khái niệm severity nào | ✅ Thêm `bySeverity`, suy ra từ `violationType` (không cần cột DB mới) |

---

## 2. Chi tiết lỗi #1 — Rò rỉ dữ liệu xuyên site

### Phát hiện

Kiểm tra từng endpoint report, so sánh với 2 endpoint đã làm đúng từ trước (báo cáo công tháng, báo cáo Face ID — cả 2 đều gọi `SiteScopeService`/`resolveSiteFilterForReports`), phát hiện **4 endpoint hoàn toàn không áp dụng site-scope**:

- `GET /reports/attendance/daily`
- `GET /reports/violations` (+ `GET /reports/violations/export`)
- `GET /reports/sites/presence`
- `GET /search`

Một người dùng RBAC bị giới hạn site (điển hình: SITE_SUPERVISOR, hoặc bất kỳ role tuỳ chỉnh nào có site-scoped assignment) nếu có quyền `reports:list`/`employees:list`, gọi 1 trong 4 endpoint trên sẽ thấy **toàn bộ dữ liệu của mọi site trong tenant**, không chỉ site họ phụ trách — vi phạm nguyên tắc site-scope đã áp dụng nhất quán ở mọi nơi khác trong hệ thống (checkin list, Face ID report, báo cáo công tháng, dashboard supervisor).

### Đã sửa và verify

- **Báo cáo công ngày**: dùng lại `resolveSiteFilterForReports` (helper đã có sẵn, dùng cho báo cáo công tháng) — tự thu hẹp về 1 site hoặc chặn `403` nếu `siteId` truyền vào ngoài phạm vi.
- **Báo cáo vi phạm + export**: cùng helper, áp dụng cho cả 2 endpoint.
- **Báo cáo hiện diện site**: dùng pattern khác (Face-ID-report style) vì endpoint này lặp qua **nhiều** site cùng lúc, không phải 1 site — lọc danh sách site theo `allowedSiteIds`.
- **Tìm kiếm nhanh**: thêm predicate `id IN allowedSiteIds` cho site search, và resolve `visibleEmployeeIds` qua bảng `assignments` (Employee không có cột site trực tiếp) cho nhân viên search — check-in search tự động thu hẹp theo vì nó chỉ tìm trong nhân viên đã match.

**Verify trực tiếp bằng tài khoản supervisor thật** (không chỉ đọc code):
- Báo cáo vi phạm: HR thấy 30 vi phạm toàn tenant, supervisor cùng gọi chỉ thấy 14 (đúng site của họ), `bySite` chỉ có 1 site.
- Báo cáo công ngày: supervisor không truyền `siteId` → tự động chỉ trả 24 bản ghi, toàn bộ đúng site được giao (không lẫn site khác).
- Báo cáo hiện diện site: **trước khi sửa**, supervisor gọi thấy đủ 13 site; **sau khi sửa**, chỉ thấy đúng 1 site họ phụ trách — HR vẫn thấy đủ 13 site như cũ (không hồi quy).
- Tìm kiếm: tìm 1 nhân viên có thật nhưng thuộc site khác — supervisor nhận mảng rỗng, HR (unrestricted) tìm cùng từ khoá thấy đúng kết quả.

---

## 3. Chi tiết lỗi #2 — Site Supervisor thiếu quyền truy cập báo cáo hiện diện site

Story nói rõ: *"Là một HR/Admin/Supervisor, tôi muốn xem số người đang có mặt/thiếu tại từng site."* Nhưng trước audit, SITE_SUPERVISOR không có bất kỳ quyền `reports:*` nào trong RBAC seed — gọi bất kỳ endpoint report nào đều `403`.

**Quyết định thiết kế**: `ReportController` chỉ có 1 permission gate (`reports:list`) áp dụng chung cho cả 5 endpoint đọc (không có permission tách riêng theo từng loại báo cáo) — cấp quyền này đồng nghĩa supervisor cũng truy cập được báo cáo công/vi phạm/Face ID, không chỉ riêng site presence. Đây là lựa chọn chủ đích, không phải để sót: một Site Supervisor quản lý hiện trường hợp lý cũng cần biết tình hình chấm công/vi phạm của chính site mình, giống cách Deputy/Connecteam cho site manager xem đầy đủ số liệu site họ quản lý, không chỉ riêng headcount. **Không cấp** `reports:export`/`attendance:export` — export vẫn giữ đúng phạm vi HR/Admin theo story, chỉ supervisor xem, không xuất file.

Migration `V84` chỉ chạy **sau khi** lỗi #1 đã được sửa và verify — nếu làm ngược thứ tự sẽ ngay lập tức tạo ra đúng lỗ hổng đang cố sửa.

---

## 4. Chi tiết lỗi #3 — Báo cáo vi phạm thiếu severity

Story yêu cầu: *"thống kê vi phạm theo loại, severity, site, nhân viên."* Hệ thống có sẵn breakdown theo loại/site/nhân viên nhưng **không có khái niệm severity nào** — không có cột DB, không có field nào trên `Violation` entity.

**Quyết định thiết kế**: thay vì thêm cột DB mới (đòi hỏi HR tự gán severity tay cho từng vi phạm — không có story nào yêu cầu việc này), suy ra severity trực tiếp từ `violationType` đã có sẵn — cách tiếp cận giống Deputy phân loại "exception severity" theo loại exception, không phải trường editable riêng:

- **HIGH**: `face_fail`, `liveness_fail` — liên quan trực tiếp xác thực danh tính, tín hiệu mạnh nhất về gian lận.
- **MEDIUM**: `location_fail` — sai vị trí thật nhưng thường ít nghiêm trọng hơn.
- **LOW**: `no_response` — tín hiệu yếu nhất, nhiều khả năng là lý do khách quan (hết pin, mất mạng).

Thêm `bySeverity` (map) vào `ViolationReportResponse`, tính cùng lúc với `byViolationType`/`bySite`/`byEmployee` trong `getViolationReport`. Verify trực tiếp: 30 vi phạm gồm 21 `no_response` + 9 `location_fail` → `bySeverity` trả đúng `{LOW: 21, MEDIUM: 9}`.

---

## 5. Danh sách thay đổi kỹ thuật

| File | Thay đổi |
|---|---|
| `ReportService.java` — `getDailyAttendanceReport` | Áp dụng `resolveSiteFilterForReports` (site-scope) |
| `ReportService.java` — `getViolationReport`, `exportViolations` | Áp dụng site-scope; thêm `bySeverity` |
| `ReportService.java` — `getSitePresenceReport` | Site-scope theo `allowedSiteIds` (multi-site pattern) |
| `ViolationReportResponse.java` | Thêm field `bySeverity` |
| `ViolationSeverity.java` (mới, package `violation.util`) | Enum HIGH/MEDIUM/LOW, suy ra từ `violationType` |
| `SearchService.java` | Site-scope cho cả employee/site search (qua `assignments` cho employee, trực tiếp cho site); inject `SiteScopeService`, `AssignmentRepository` |
| `V84__site_supervisor_reports_access.sql` (mới) | Cấp `reports:list` cho SITE_SUPERVISOR |

Build lại qua `docker restart fams-api`, compile sạch (406 file, 0 lỗi). Live-test toàn bộ bằng tài khoản HR thật + tài khoản supervisor thật (không chỉ platform admin — quan trọng vì bug chỉ lộ ra với site-scoped caller). Chạy lại toàn bộ regression suite sẵn có (`tests/report/*.sh` 6 file, `tests/search/*.sh` 1 file) — **81 pass, 0 fail** (2 skip do lỗi đăng ký tài khoản test có từ trước, không liên quan thay đổi hôm nay).

---

## 6. Giới hạn đã biết

- `tests/face-id/test_face_id_report.sh` fail với `SETUP FAILED: emp login` — không liên quan thay đổi hôm nay (không đụng tới code Face ID report lần này), cùng dạng lỗi kịch bản test có từ trước đã ghi nhận nhiều lần trong các đợt audit trước.
- Site presence report và Supervisor Dashboard (`dashboard-api.md`) có phần trùng lặp dữ liệu (cả 2 đều trả "ai đang có mặt tại site") — đây là chủ đích, không phải dư thừa cần dọn: report phục vụ HR xem TẤT CẢ site cùng lúc dạng bảng, dashboard tối ưu cho 1 màn hình cá nhân của supervisor. Không hợp nhất 2 API vì đối tượng dùng và ngữ cảnh UI khác nhau.

---

## 7. Kết luận

7 user story về báo cáo/tìm kiếm đã tồn tại sẵn nhưng chưa từng được audit về site-scope — phát hiện 1 lỗ hổng rò rỉ dữ liệu nghiêm trọng ảnh hưởng 4 endpoint (báo cáo công ngày, báo cáo vi phạm, báo cáo hiện diện site, tìm kiếm), 1 gap quyền truy cập thật so với yêu cầu story (Supervisor không xem được site presence), và 1 gap tính năng thật (thiếu severity trong báo cáo vi phạm). Cả 3 đã sửa, build, và verify bằng tài khoản supervisor thật (không chỉ platform admin) để đảm bảo bug thực sự được chứng minh tồn tại trước khi sửa và biến mất sau khi sửa — không dừng ở việc đọc code suy luận.

---

## 8. Bản vá P1 (cùng ngày 2026-08-05) — 3 đề xuất từ phía FE

Sau khi bàn giao tài liệu, FE phản hồi 3 đề xuất "không bắt buộc nhưng nên cân nhắc". Cả 3 hợp lý, đã triển khai:

### 8.1 Presence/danh sách vắng mặt chỉ trả UUID

**Đề xuất**: `presentEmployeeIds`/`absentEmployeeIds` (báo cáo hiện diện site) và `absentEmployeeIds` (báo cáo công ngày) chỉ trả mảng UUID trần, buộc FE tự gọi API khác resolve tên/mã cho từng ID — với danh sách vài chục nhân viên/site, đây là N+1 lookup phía client.

**Đã sửa**: thêm DTO dùng chung `EmployeeRef` (employeeId/employeeName/employeeCode), thay `List<UUID>` bằng `List<EmployeeRef>` ở cả 2 báo cáo, đổi tên field (`presentEmployeeIds`→`presentEmployees`, `absentEmployeeIds`→`absentEmployees`) cho rõ nghĩa (không còn "Ids" khi chứa object). Resolve theo batch 1 câu query (`findAllByTenantIdAndIdInAndDeletedAtIsNull`), không phải N+1 phía backend. Đây là breaking change có chủ đích — cả 2 màn hình đích chưa được FE dựng (đã ghi ở checklist bản gốc), nên sửa ngay trước khi có consumer thật hợp lý hơn giữ field cũ rồi thêm field mới song song.

Verify trực tiếp: gọi báo cáo hiện diện site và báo cáo công ngày, xác nhận `absentEmployees`/`presentEmployees` trả đúng object có `employeeName`/`employeeCode` khớp dữ liệu thật trong DB.

### 8.2 Báo cáo Face ID nên hỗ trợ `departmentId` và search phía server

**Đã sửa**: thêm 2 query param `departmentId` (lọc theo phòng ban, dùng field `Employee.departmentId` đã có sẵn — trước đó chỉ có field `department` dạng chuỗi tự do không lọc được chính xác) và `search` (tìm theo tên/email/mã nhân viên, áp dụng ở tầng Specification giống cách Global Search đã làm). 4 số liệu tổng quan (enrolled/pending/notEnrolled/revoked count) tính lại đúng trong phạm vi đã lọc, không phải toàn tenant.

Verify trực tiếp: `search=Giang` trả đúng 3 nhân viên tên Giang (từ tổng 40); lọc theo 1 `departmentId` cụ thể trả đúng 5 nhân viên (khớp count trực tiếp từ SQL).

### 8.3 Global Search nên tìm trực tiếp theo mã check-in

**Đã sửa**: nếu chuỗi tìm kiếm `q` parse được thành UUID hợp lệ, tự động thử tìm đúng 1 `CheckinRecord` có `id` khớp tuyệt đối, chèn vào đầu mảng `checkins` (dedupe nếu đã có từ nhánh tìm theo nhân viên). Có tôn trọng site-scope — verify trực tiếp: HR (unrestricted) tra 1 checkin ID thật → tìm thấy; supervisor tra checkin ID thật nhưng ở site khác → nhận mảng rỗng đúng như hành vi site-scope đã áp dụng cho toàn bộ search.

**Giới hạn có chủ đích**: chỉ khớp UUID đầy đủ, không hỗ trợ tìm theo prefix/1 phần ID — `CheckinRecord.id` là cột kiểu `uuid` thật (không phải text), tìm theo prefix sẽ cần ép kiểu quét toàn bảng; check-in ID luôn được copy-paste nguyên vẹn từ nơi khác (ticket, audit log) trong thực tế sử dụng, không phải gõ tay từng phần.

### Kỹ thuật

| File | Thay đổi |
|---|---|
| `EmployeeRef.java` (mới) | DTO dùng chung: employeeId/employeeName/employeeCode |
| `SitePresenceEntry.java` | `presentEmployeeIds`/`absentEmployeeIds` → `presentEmployees`/`absentEmployees` (`List<EmployeeRef>`) |
| `DailyAttendanceReportResponse.java` | `absentEmployeeIds` → `absentEmployees` (`List<EmployeeRef>`) |
| `FaceIdReportResponse.java` | Thêm `departmentId`, `search` (echo lại filter đã áp dụng) |
| `ReportService.java` | Thêm `toEmployeeRefs()` batch-resolve; áp dụng filter `departmentId`/`search` vào Face ID report qua Specification |
| `ReportController.java` | Thêm query param `departmentId`, `search` cho endpoint Face ID |
| `SearchService.java` | Thêm nhánh tra checkin theo UUID khớp tuyệt đối, tôn trọng site-scope |
| `tests/report/test_daily_attendance_report.sh` | Cập nhật assertion theo tên field mới (`absentEmployees`) |

Build lại, compile sạch (407 file). Chạy lại toàn bộ regression suite `tests/report/*.sh` + `tests/search/*.sh`: **82 pass, 0 fail** (đã cập nhật 1 test script theo tên field mới, 2 skip do lỗi đăng ký tài khoản test có từ trước, không liên quan).
