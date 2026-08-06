# API Reference: Reports & Global Search

> Cập nhật theo code đang chạy ngày 2026-08-05, sau audit nghiệp vụ (`docs/reviews/backend/report-search-audit-2026-08-05.md`) và bản vá P1 theo phản hồi FE cùng ngày (`docs/reviews/backend/report-search-audit-2026-08-05.md` mục 8). Base path: `/api/v1/tenants/{tenantId}/reports` và `/api/v1/tenants/{tenantId}/search`. Tài liệu này là **nguồn tham khảo chính thức cho FE (Web + App)**.

## 0. Bản vá P1 (2026-08-05, cùng ngày) — trả lời 3 đề xuất từ FE

| # | FE đề xuất gì | Đã làm gì | Chi tiết |
|---|---|---|---|
| 1 | Presence/danh sách vắng mặt chỉ trả UUID, nên có `employeeName`/`employeeCode` hoặc API resolve hàng loạt | Đã đổi `presentEmployeeIds`/`absentEmployeeIds`/`absentEmployeeIds` (site presence + báo cáo công ngày) từ `List<UUID>` sang `List<EmployeeRef>` (đã đổi tên field thành `presentEmployees`/`absentEmployees`) — mỗi phần tử có sẵn `employeeId`+`employeeName`+`employeeCode`, không cần FE tự resolve hàng loạt nữa | Mục 3, 7 |
| 2 | Báo cáo Face ID nên hỗ trợ `departmentId` và search phía server | Đã thêm 2 query param `departmentId`, `search` (tìm theo tên/email/mã nhân viên) | Mục 8 |
| 3 | Global Search chưa tìm trực tiếp theo mã check-in | Đã thêm: nếu `q` là 1 UUID hợp lệ, tự động thử tìm đúng check-in đó theo ID (khớp tuyệt đối, không phải prefix), gộp vào đầu kết quả `checkins` | Mục 9 |

**Lưu ý breaking change**: field `presentEmployeeIds`/`absentEmployeeIds` (site presence) và `absentEmployeeIds` (báo cáo công ngày) đã đổi tên/kiểu dữ liệu — vì các màn hình dùng field này **chưa được FE dựng** (đã ghi rõ ở checklist mục 11 bản gốc), đây được coi là sửa trước khi có consumer thật, không phải thay đổi gây vỡ hợp đồng đang dùng.

---

Bao gồm 7 user story:
- *HR/Admin xem báo cáo công theo ngày.*
- *HR/Admin xem tổng công tháng (chuẩn bị tính lương).*
- *HR/Admin xuất bảng công ra Excel.*
- *HR/Admin xem thống kê vi phạm theo loại/severity/site/nhân viên.*
- *HR/Admin/Supervisor xem số người đang có mặt/thiếu tại từng site.*
- *HR/Admin xem trạng thái đăng ký Face ID.*
- *HR/Admin tìm kiếm nhanh nhân viên/site/check-in.*

---

## 1. Khái niệm nền tảng

### 1.1 Toàn bộ report chịu chung 1 permission gate — nhưng tự động thu hẹp theo site-scope

Cả 6 endpoint report đều yêu cầu quyền `reports:list` (export riêng yêu cầu `reports:export`/`attendance:export`). Không có permission tách riêng theo từng loại báo cáo. Với người dùng bị giới hạn site (site-scoped role, ví dụ SITE_SUPERVISOR) — **mọi report tự động thu hẹp về đúng (các) site họ được giao**, không cần FE tự lọc lại. Xem chi tiết cơ chế ở mục 1.2 và bảng quyền mục 2.

**Vá 2026-08-05 — lỗi bảo mật nghiêm trọng đã sửa**: trước đây báo cáo công theo ngày, báo cáo vi phạm, báo cáo hiện diện site, và tìm kiếm toàn hệ thống **hoàn toàn không áp dụng site-scope** — một người dùng bị giới hạn site (SITE_SUPERVISOR) gọi các API này (nếu có quyền `reports:list`/`employees:list`) sẽ thấy dữ liệu của TẤT CẢ site trong tenant, không chỉ site họ phụ trách. Đã sửa đồng bộ cho cả 4 endpoint, cùng cơ chế `SiteScopeService` đã dùng sẵn ở báo cáo công tháng và báo cáo Face ID.

### 1.2 SITE_SUPERVISOR giờ có quyền `reports:list` (mới, 2026-08-05)

**Trước đây**: SITE_SUPERVISOR không có bất kỳ quyền `reports:*` nào — dù user story nói rõ "HR/Admin/Supervisor" cho báo cáo hiện diện site, supervisor hoàn toàn không gọi được endpoint này (`403`).

**Đã sửa**: cấp `reports:list` cho SITE_SUPERVISOR (migration V84) — **chỉ sau khi** đã sửa xong lỗi site-scope ở mục 1.1, để đảm bảo việc cấp quyền này không mở ra rò rỉ dữ liệu. Kết quả: supervisor giờ gọi được cả 5 report dùng `reports:list` (daily/monthly attendance, violations, site presence, Face ID), nhưng **mỗi report tự động chỉ trả về đúng site họ phụ trách** — không cần FE ẩn/hiện gì thêm, backend tự thu hẹp. **Không cấp** `reports:export`/`attendance:export` cho supervisor — export vẫn chỉ dành cho HR/Admin đúng theo story.

### 1.3 Violation severity — suy ra từ loại vi phạm, không phải field riêng

**Mới (2026-08-05)**: báo cáo vi phạm có thêm `bySeverity`. Đây **không phải** 1 field HR tự đặt tay trên từng violation — hệ thống tự suy ra từ `violationType`:

| Severity | violationType | Lý do |
|---|---|---|
| `HIGH` | `face_fail`, `liveness_fail` | Liên quan trực tiếp xác thực danh tính — tín hiệu mạnh nhất về gian lận/chấm công hộ |
| `MEDIUM` | `location_fail` | Sai vị trí thật nhưng thường ít nghiêm trọng hơn (GPS trôi, làm việc tại địa điểm phụ đã duyệt) |
| `LOW` | `no_response` | Tín hiệu yếu nhất về vi phạm thật — điện thoại hết pin, bỏ lỡ thông báo là nguyên nhân phổ biến |

FE **không cần và không có API để sửa** severity của 1 violation cụ thể — nó luôn được tính lại từ `violationType`.

---

## 2. Ma trận quyền

| Endpoint | TENANT_ADMIN / HR_MANAGER | SITE_SUPERVISOR (mới) | Nhân viên thường |
|---|---|---|---|
| Báo cáo công ngày/tháng | ✅ (`reports:list`) | ✅ — tự thu hẹp về site được giao | ❌ |
| Export Excel bảng công | ✅ (`attendance:export`) | ❌ | ❌ |
| Báo cáo vi phạm theo kỳ | ✅ (`reports:list`) | ✅ — tự thu hẹp về site được giao | ❌ |
| Export Excel vi phạm | ✅ (`reports:export`) | ❌ | ❌ |
| Báo cáo hiện diện site | ✅ (`reports:list`) | ✅ — tự thu hẹp về site được giao | ❌ |
| Báo cáo Face ID | ✅ (`reports:list`) | ✅ — tự thu hẹp về site được giao | ❌ |
| Tìm kiếm nhanh | ✅ (`employees:list`) | ✅ — tự thu hẹp về site được giao | ❌ |

**Site-scope tự động**: nếu người gọi bị giới hạn ≥1 site cụ thể (không phải "unrestricted"), mọi report ở trên tự lọc — nếu FE truyền `siteId` không nằm trong phạm vi được giao, trả `403`; nếu không truyền `siteId`, tự hiểu là "toàn bộ site được giao" (không phải "toàn tenant"). Người dùng unrestricted (TENANT_ADMIN/HR_MANAGER/Platform Admin) không bị ảnh hưởng.

---

## 3. `GET /reports/attendance/daily` — Báo cáo công ngày

Query: `date` (bắt buộc, ISO date), `siteId` (tuỳ chọn), `page`, `size`.

```json
{
  "date": "2026-07-31", "siteId": null,
  "totalPresent": 24, "totalAbsent": 1, "totalLate": 0, "totalEarlyLeave": 0,
  "totalMissingCheckout": 0, "totalWorkMinutes": 9120, "totalOtMinutes": 0,
  "absentEmployees": [
    { "employeeId": "uuid", "employeeName": "An Nguyễn Văn", "employeeCode": "HL-001" }
  ],
  "records": { "content": [ { "...": "AttendanceSummaryResponse đầy đủ" } ], "totalElements": 24 }
}
```

- `totalAbsent`/`absentEmployees`: nhân viên có assignment active ngày đó nhưng KHÔNG có bản ghi chấm công — đây là điểm khác biệt quan trọng so với chỉ đếm "vắng mặt theo check-in"; dùng đúng field này để hiện danh sách "chưa chấm công hôm nay".
- Với người gọi bị giới hạn site: nếu không truyền `siteId`, báo cáo tự gộp mọi site họ được giao (không phải chỉ 1 site) — `absentEmployees` cũng theo đúng phạm vi đó.
- **Mới (2026-08-05)**: `absentEmployees` mỗi phần tử đã có sẵn `employeeName`/`employeeCode` — không cần FE tự gọi API khác resolve tên (trước đây chỉ có mảng UUID trần, field tên cũ `absentEmployeeIds`).

**UI đề xuất**: bảng "Chấm công theo ngày" với date-picker, 6 số liệu tổng quan dạng thẻ ở đầu trang (present/absent/late/early-leave/missing-checkout), bảng chi tiết bên dưới, danh sách vắng mặt riêng (click từ số liệu `totalAbsent`).

---

## 4. `GET /reports/attendance/monthly` — Báo cáo công tháng

**User story**: *chuẩn bị tính lương.*

Query: `year`, `month` (bắt buộc), `siteId` (tuỳ chọn), `page`, `size`.

Trả về tổng hợp theo từng nhân viên+site cho cả tháng: `presentDays`, `totalWorkMinutes`, `lateDays`/`totalLateMinutes`, `earlyLeaveDays`, `missingCheckoutDays`, `totalOtMinutes`, kèm 3 cờ cảnh báo `daysWithPendingReview`/`daysWithRejectedSession`/`daysWithRandomCheckFailure` — **đây chính là dữ liệu payroll cần đối chiếu trước khi chốt lương**, xem thêm ràng buộc export ở mục 5.

**UI đề xuất**: bảng "Bảng công tháng" — 1 dòng/nhân viên/site, cột số liệu tổng hợp, badge cảnh báo nếu có `daysWithPendingReview`/`daysWithRejectedSession`/`daysWithRandomCheckFailure` > 0 (nhắc HR xử lý trước khi export lương, xem mục 5).

---

## 5. `GET /reports/attendance/export` — Export bảng công Excel

Query: `year`, `month` (bắt buộc), `siteId` (tuỳ chọn), `confirmDespiteWarnings` (mặc định `false`). Quyền `attendance:export`.

**Guard "sẵn sàng tính lương" (đã có từ trước, không đổi)**: trả `409 ATTENDANCE_NOT_READY` nếu phạm vi export còn dòng nào có `pending_review`/`rejected` chưa xử lý hoặc `hasRandomCheckFailure` — chặn xuất file lương khi dữ liệu chưa "sạch". HR xem message lỗi (có số lượng cụ thể từng loại), xử lý xong rồi export lại, hoặc gọi lại với `confirmDespiteWarnings=true` nếu chắc chắn muốn export bất chấp cảnh báo.

**UI đề xuất**: nút "Xuất Excel" trên màn báo cáo công tháng (mục 4) — nếu `409`, hiện dialog liệt kê rõ số dòng có vấn đề + nút "Xuất bất chấp cảnh báo" (map sang `confirmDespiteWarnings=true`) thay vì chặn cứng, để HR tự quyết định khi cần.

---

## 6. `GET /reports/violations` — Báo cáo vi phạm theo kỳ

Query: `from`, `to` (tuỳ chọn, theo `checkDate`), `siteId`, `employeeId`, `violationType` (tuỳ chọn), `page`, `size`.

```json
{
  "totalViolations": 30, "resolvedCount": 20, "unresolvedCount": 10, "affectsAttendanceCount": 5,
  "byViolationType": { "no_response": 21, "location_fail": 9 },
  "bySeverity": { "LOW": 21, "MEDIUM": 9 },
  "bySite": { "<siteId>": 14 },
  "byEmployee": { "<employeeId>": 3 },
  "records": { "content": [ "...ViolationListResponse..." ] }
}
```

- `bySeverity` — xem định nghĩa mục 1.3.
- `bySite`/`byEmployee`: key là UUID dạng string — FE tự resolve tên qua danh sách site/nhân viên đã có sẵn ở màn khác, response này không kèm tên.

**UI đề xuất**: màn "Báo cáo vi phạm" — bộ lọc kỳ/site/nhân viên/loại, 4 thẻ tổng quan (total/resolved/unresolved/affects-attendance), 2 biểu đồ (theo loại, theo severity — donut chart phù hợp vì `bySeverity` chỉ có 3 giá trị cố định), bảng chi tiết phân trang bên dưới. Có thể điều hướng sang màn Danh sách vi phạm (`violation-management-api.md`) để xử lý confirm/dismiss trực tiếp từ 1 dòng.

### 6.1 `GET /reports/violations/export` — Export Excel vi phạm

Cùng bộ filter, quyền `reports:export`. Không có guard "not ready" như bảng công — vi phạm không ảnh hưởng lương tự động (xem `violation-management-api.md` mục 1.4), nên xuất file bất kỳ lúc nào không cần điều kiện tiên quyết.

**Cập nhật 2026-08-06 — thêm filter `resolved` (boolean, tuỳ chọn)**: trước đây endpoint export **thiếu** `resolved` dù màn danh sách vi phạm đã hỗ trợ — dẫn tới việc export không khớp đúng bộ lọc người dùng đang xem (ví dụ đang lọc "chưa xử lý" nhưng file export lại gồm cả đã xử lý). Đã bổ sung `resolved` vào export, hoạt động giống hệt `GET /violations?resolved=...`: `true` → chỉ vi phạm đã xử lý, `false` → chỉ chưa xử lý, bỏ trống → cả hai. **FE cần bỏ modal cảnh báo "file sẽ gồm cả hai trạng thái"** (không còn cần thiết) và truyền thẳng `resolved` hiện tại của bộ lọc màn hình vào request export, để file tải về khớp tuyệt đối với danh sách đang xem trên UI.

---

## 7. `GET /reports/sites/presence` — Báo cáo hiện diện theo site

**User story**: *HR/Admin/Supervisor xem số người đang có mặt/thiếu tại từng site.*

Query: `siteId` (tuỳ chọn), `page`, `size`. Snapshot **real-time** (không phải dữ liệu lịch sử theo ngày như báo cáo công) — "đang có mặt" nghĩa là có phiên chấm công mở, bắt đầu **trong hôm nay** (theo timezone của từng site — xem `dashboard-api.md` mục 1.3, cùng cơ chế).

```json
{
  "reportedAt": "2026-08-05T14:27:20Z",
  "totalSites": 1, "totalPresent": 0, "totalAssigned": 24, "totalAbsent": 24,
  "sites": {
    "content": [
      {
        "siteId": "uuid", "siteName": "Trụ sở Hoàng Long Hà Nội", "timezone": "Asia/Ho_Chi_Minh",
        "assignedCount": 24, "presentCount": 0, "absentCount": 24,
        "presentEmployees": [],
        "absentEmployees": [
          { "employeeId": "uuid", "employeeName": "Giang Hoàng Thị", "employeeCode": "HL-005" }
        ]
      }
    ]
  }
}
```

**Đã vá 2026-08-05**: trước đây endpoint này trả về TẤT CẢ site trong tenant bất kể người gọi bị giới hạn site hay không (lỗ hổng nghiêm trọng nhất trong đợt audit này) — 1 supervisor gọi API sẽ thấy đủ 13 site thay vì đúng 1 site họ phụ trách. Đã sửa và verify trực tiếp: supervisor giờ chỉ thấy đúng site được giao, HR/Admin (unrestricted) vẫn thấy đủ toàn bộ site như cũ.

**Mới (2026-08-05, bản vá P1)**: `presentEmployees`/`absentEmployees` (đã đổi tên từ `presentEmployeeIds`/`absentEmployeeIds`) giờ mỗi phần tử là object có `employeeId`+`employeeName`+`employeeCode`, không còn mảng UUID trần — FE hiện được ngay tên/mã nhân viên trong danh sách hiện diện mà không cần gọi thêm API resolve hàng loạt.

**UI đề xuất (đặc biệt phù hợp cho App/Web của Supervisor)**: mỗi site 1 card hiện `presentCount`/`assignedCount` dạng tỷ lệ trực quan, danh sách `absentEmployeeIds` bên dưới (resolve tên qua danh sách nhân viên đã có). Vì đây là snapshot thời điểm gọi API, khuyến nghị FE tự poll lại mỗi 1-2 phút nếu màn đang mở — không có WebSocket/push riêng cho báo cáo này (giống khuyến nghị ở `dashboard-api.md` mục 5 cho Supervisor Dashboard — 2 nguồn dữ liệu tương tự nhau về bản chất, HR có thể dùng report này để xem TẤT CẢ site cùng lúc, còn Supervisor Dashboard tối ưu cho 1 màn hình cá nhân).

---

## 8. `GET /reports/face-id/enrollment` — Báo cáo trạng thái Face ID

**User story**: *xem nhân viên đã/chưa đăng ký Face ID để hoàn tất onboarding.*

Query: `status` (tuỳ chọn: `enrolled`|`pending`|`not_enrolled`|`revoked`), `departmentId` (tuỳ chọn, **mới 2026-08-05**), `search` (tuỳ chọn, **mới 2026-08-05** — tìm theo tên/email/mã nhân viên phía server, không cần FE tự lọc mảng đã tải), `page`, `size`.

Mỗi dòng gồm thông tin nhân viên (mã, tên, phòng ban) + `faceIdStatus`, `consentGiven`/`consentGivenAt`, `enrolledAt`/`revokedAt`, `reviewStatus` (`none` nếu chưa từng nộp hồ sơ), `submittedAt`, `rejectionReason` (nếu bị từ chối) — **đủ dữ liệu để HR theo dõi toàn bộ vòng đời đăng ký Face ID** mà không cần vào từng hồ sơ nhân viên.

**Mới (2026-08-05)**: response gốc giờ echo lại `departmentId`/`search` đã áp dụng (giống cách `siteId`/`violationType` đã echo ở báo cáo vi phạm) — 4 số liệu tổng quan (`enrolledCount`/`pendingCount`/`notEnrolledCount`/`revokedCount`) tính TRONG phạm vi `departmentId`/`search` đã lọc, không phải toàn tenant — đúng ý nghĩa "tổng quan của tập đang xem", không cần FE tự trừ lại.

**Liên kết nghiệp vụ quan trọng**: đây chính là dữ liệu quyết định nhân viên có bị fail oan khi Random Check yêu cầu mode `location_face`/`location_face_liveness` hay không (xem `random-check-ui-guide.md` mục 1.4, 3.5 — hệ thống đã có fail-safe tự hạ mode khi chưa `enrolled`, nhưng báo cáo này giúp HR chủ động nhắc nhân viên đăng ký trước, thay vì để hệ thống âm thầm hạ mode).

**UI đề xuất**: màn "Trạng thái Face ID" — bộ lọc theo status, 4 thẻ đếm tổng quan, bảng chi tiết có thể lọc theo phòng ban để ưu tiên nhắc nhở theo nhóm trong đợt onboarding.

---

## 9. `GET /search` — Tìm kiếm nhanh

**User story**: *tìm nhanh dữ liệu chính để giảm thao tác điều hướng.*

Query: `q` (chuỗi tìm kiếm, tối thiểu 2 ký tự — dưới 2 ký tự trả về rỗng ngay để tránh full-table-scan), `limit` (mặc định áp dụng riêng cho từng nhóm kết quả, không phải tổng).

```json
{
  "query": "nguyen", "limit": 5,
  "employees": [ "...EmployeeResponse..." ],
  "sites": [ "...SiteResponse..." ],
  "checkins": [ "...CheckinResponse, chỉ của nhân viên đã match ở trên..." ]
}
```

- Tìm nhân viên theo: họ tên, email, mã nhân viên, chức danh, phòng ban.
- Tìm site theo: tên, mã, địa chỉ, mô tả.
- `checkins` gồm 2 nguồn gộp lại: (1) check-in gần đây của **những nhân viên đã match** ở phần `employees`, và (2) **mới (2026-08-05)** — nếu `q` là 1 chuỗi UUID hợp lệ, tự động thử tìm đúng 1 check-in có `id` khớp tuyệt đối (không phải prefix/partial), chèn lên đầu danh sách `checkins` nếu tìm thấy và nằm trong phạm vi site-scope của người gọi. Dùng khi HR có sẵn 1 check-in ID cụ thể (ví dụ từ ticket hỗ trợ, audit log) và muốn tra thẳng, không phải gõ tên nhân viên rồi lọc trong danh sách gần đây.
- **Đã vá 2026-08-05**: cùng lỗi rò rỉ site-scope như mục 1.1 — supervisor tìm kiếm giờ chỉ thấy nhân viên/site/check-in trong phạm vi được giao (kể cả tra cứu trực tiếp theo ID ở trên), verify trực tiếp: tìm 1 nhân viên tồn tại thật nhưng ở site khác → supervisor nhận mảng rỗng, HR (unrestricted) tìm cùng từ khoá → thấy đúng kết quả; tra 1 checkin ID thật nhưng thuộc site khác → supervisor nhận mảng rỗng.

**UI đề xuất**: thanh tìm kiếm toàn cục trên header (Web) — gõ tới đâu gọi API tới đó (debounce ~300ms), hiện kết quả dạng dropdown chia 3 nhóm (Nhân viên/Site/Check-in gần đây), click vào 1 kết quả điều hướng thẳng tới trang chi tiết tương ứng.

---

## 10. Mã lỗi cần xử lý

| HTTP | Khi nào | FE nên làm gì |
|---|---|---|
| 400 | Thiếu `date`/`year`/`month`, `month` ngoài 1–12 | Validate client trước khi submit |
| 401 | Chưa đăng nhập / token hết hạn | Redirect đăng nhập lại |
| 403 | Thiếu quyền (`reports:list`/`:export`, `attendance:export`, `employees:list`); hoặc truyền `siteId` ngoài phạm vi site-scope của người gọi | Ẩn menu nếu biết trước không có quyền; với lỗi site-scope, hiện thông báo rõ "không có quyền xem site này" thay vì lỗi chung chung |
| 409 (`ATTENDANCE_NOT_READY`) | Export bảng công còn dữ liệu chưa sẵn sàng | Hiện dialog cảnh báo + tuỳ chọn `confirmDespiteWarnings=true` (mục 5) |

---

## 11. Checklist bàn giao frontend

- [ ] **Web — bắt buộc, chưa có màn hình**: Báo cáo công ngày (mục 3), Báo cáo công tháng (mục 4) kèm nút Export (mục 5).
- [ ] **Web — bắt buộc, chưa có màn hình**: Báo cáo vi phạm theo kỳ (mục 6) — 2 chart (loại + severity) + bảng chi tiết, kèm Export.
- [ ] **Web/App — bắt buộc, chưa có màn hình**: Báo cáo hiện diện site (mục 7) — đặc biệt hữu ích cho Supervisor, cân nhắc poll định kỳ nếu màn đang mở.
- [ ] **Web — bắt buộc, chưa có màn hình**: Báo cáo trạng thái Face ID (mục 8), lọc theo phòng ban.
- [ ] **Web — bắt buộc, chưa có màn hình**: Thanh tìm kiếm nhanh toàn cục (mục 9), debounce input, dropdown 3 nhóm kết quả.
- [ ] **Web/App**: xử lý đúng `409 ATTENDANCE_NOT_READY` khi export bảng công (mục 5, 10) — không coi là lỗi cứng.
- [ ] **Web**: nếu tài khoản đăng nhập là SITE_SUPERVISOR, không cần tự ẩn bớt filter site trên UI report — backend tự thu hẹp kết quả đúng phạm vi, chỉ cần xử lý đúng lỗi `403` nếu FE lỡ truyền `siteId` sai phạm vi.
