# Báo cáo audit nghiệp vụ: Vi phạm (HR actions) + 3 Dashboard vai trò

**Ngày:** 2026-08-04
**Phạm vi:** 8 user story — 5 story về xử lý vi phạm (HR list/detail/confirm/dismiss/attendance-impact) đã được audit và sửa xong trong đợt trước (2026-08-03, xem `random-check-violation-audit-2026-08-03.md` + `violation-evidence-contract-2026-08-04.md`); 3 story Dashboard (Employee/HR/Site Supervisor) là phần audit mới trong báo cáo này.
**Tham chiếu thực tế:** Deputy (role-based dashboard, "who's on shift now"), BambooHR (employee self-service home screen), Connecteam (site supervisor live headcount).

---

## 1. Tóm tắt kết quả

| # | Hạng mục | Trước audit | Sau audit |
|---|---|---|---|
| 1–5 | 5 story xử lý vi phạm (list/detail/confirm/dismiss/attendance-impact) | — | ✅ Đã audit & sửa xong đợt trước (2026-08-03/04), không phát sinh vấn đề mới lần này |
| 6 | Dashboard nhân viên thiếu "thông báo" | 🔴 Story yêu cầu rõ nhưng API không có field nào cho thông báo/việc cần xử lý | ✅ Thêm `alerts.unreadNotifications` + `alerts.pendingExplanations` |
| 7 | Dashboard HR/Supervisor: "đang có mặt tại site" tính sai | 🔴 Đếm CẢ những phiên chấm công quên checkout từ nhiều ngày/tuần trước, không chỉ hôm nay | ✅ Giới hạn theo ngày, verify trực tiếp bằng dữ liệu giả lập |
| 8 | "Hôm nay" tính theo UTC cứng, sai với giờ thực tế của tenant | 🟡 Sai lệch tới 7 tiếng (giờ Việt Nam) quanh mốc nửa đêm UTC, ảnh hưởng cả 3 dashboard + báo cáo hiện diện site | ✅ Dùng timezone của tenant/site (field đã có sẵn, trước đây không dùng tới) |

Đã build lại, biên dịch sạch, và live-test toàn bộ 3 điểm sửa bằng dữ liệu thật (bao gồm test có chủ đích tạo tình huống lỗi để xác nhận bug thật sự tồn tại trước khi sửa).

---

## 2. Đối chiếu 5 story xử lý vi phạm (nhắc lại, không có thay đổi mới)

Cả 5 story này (HR xem danh sách/chi tiết vi phạm, xác nhận, bỏ qua, cập nhật ảnh hưởng công) đã được audit toàn diện và sửa xong ở 2 đợt trước:

- **2026-08-03**: RBAC thiếu quyền HR_MANAGER (V83), idempotency chống trùng violation, đồng bộ `hasRandomCheckFailure` khi confirm/dismiss, liên kết 2 chiều violation↔scheduledCheck, self-service `/violations/my`.
- **2026-08-04 (sáng)**: bổ sung `resolution`/`resolutionReason`/`affectsAttendance` vào response list/detail, API upload ảnh giải trình private (multipart, kiểm tra magic bytes, không dùng chung bucket avatar), `GET /me/exceptions` phân biệt "chưa gửi" và "đã gửi đang chờ HR".

Kiểm tra lại lần này (đọc code + gọi API thật): toàn bộ vẫn hoạt động đúng, không phát hiện hồi quy nào từ các thay đổi dashboard trong đợt này (dashboard và violation dùng chung `ViolationRepository`/`CheckinRepository` nhưng chỉ *thêm* method mới, không sửa method cũ của 2 repository này theo hướng phá vỡ hành vi hiện có).

---

## 3. Đối chiếu 3 story Dashboard

### Story 6 — Dashboard nhân viên

**User story**: *"xem ca hôm nay, trạng thái chấm công, công tháng và thông báo để biết việc cần làm trong ngày."*

`GET /dashboard/employee` đã có sẵn `todayShifts`, `checkin` (trạng thái chấm công hôm nay), `monthlyAttendance` — đúng 3/4 phần đầu. Phần **"thông báo"** hoàn toàn thiếu — đây là gap thật so với yêu cầu, không phải suy diễn. Đã bổ sung field `alerts`:

```json
"alerts": {
  "unreadNotifications": 0,
  "pendingExplanations": 11
}
```

- `unreadNotifications`: tái sử dụng `NotificationRepository.countUnreadByTenantAndUser` đã có sẵn (không viết logic mới) — cùng nguồn dữ liệu với `GET /notifications`.
- `pendingExplanations`: gộp số checkin `pending_review` + số violation `resolved=false` của chính nhân viên — **cùng bộ dữ liệu** với `GET /me/exceptions` (tính năng tự phục vụ đã audit hôm nay ở phần 2), cung cấp dưới dạng số đếm nhẹ để màn hình chính không cần tải cả danh sách chỉ để hiện badge số. Đây chính là liên kết dữ liệu "biết việc cần làm trong ngày" mà user story yêu cầu — tham khảo mô hình BambooHR/Deputy: trang chủ nhân viên luôn có 1 khối "Cần bạn xử lý" tổng hợp từ nhiều nguồn, không bắt nhân viên tự đi tìm từng mục.

Verify trực tiếp: nhân viên có 11 việc cần giải trình (violations + checkin) → dashboard trả đúng `pendingExplanations: 11`.

### Story 7 — Dashboard HR

**User story**: *"xem tổng quan nhân sự, chấm công, vi phạm và công trình để ra quyết định nhanh."*

Cấu trúc `personnel`/`attendance`/`violations`/`sites` đã đúng, liên kết đủ 4 mảng nghiệp vụ user yêu cầu. Phát hiện 1 lỗi nghiêm trọng khi audit sâu:

**Lỗi "đang có mặt tại site" (`onSiteNow`/`employeesOnSiteNow`) đếm cả phiên chấm công quên checkout từ rất lâu trước đó.** Câu query gốc chỉ có điều kiện `check_out_at IS NULL`, không có giới hạn ngày — nghĩa là 1 nhân viên quên checkout từ 3 tuần trước vẫn bị tính là "đang có mặt ngay bây giờ" mãi mãi cho tới khi có ai đó thủ công đóng phiên đó. Đây là dữ liệu vận hành sai nghiêm trọng: HR ra quyết định "bao nhiêu người đang ở công trường" dựa trên số sai, có thể ảnh hưởng tới cả tình huống an toàn lao động (điểm danh khẩn cấp).

**Đã verify bug có thật trước khi sửa**: giả lập 1 phiên chấm công ngày 2026-05-20 (đã checkout bình thường) → xóa `check_out_at` để mô phỏng "quên checkout" → chạy thẳng câu SQL gốc (không giới hạn ngày) → xác nhận đúng là bị đếm là "đang mở". Sau khi sửa (thêm điều kiện `check_in_at >= đầu ngày hôm nay`) → gọi API thật → `onSiteNow` đúng là `0`, không đếm phiên cũ. Khôi phục dữ liệu về trạng thái ban đầu sau khi verify.

### Story 8 — Dashboard giám sát công trình

**User story**: *"xem nhân viên tại site mình phụ trách để quản lý hiện trường."*

Cấu trúc đúng — tự động lọc theo site mà supervisor có assignment role=`supervisor` hôm nay (không cần khai báo quyền RBAC riêng, đúng pattern self-scope đã dùng ở các module khác). Cùng lỗi "phiên quên checkout" như Story 7 (dùng chung hàm) — đã sửa đồng thời, verify: supervisor thật đăng nhập, xác nhận `onSiteNow: 0` sau khi sửa, đúng dữ liệu.

### Lỗi liên quan phát hiện thêm khi audit sâu — "hôm nay" tính theo UTC cứng

Cả 3 dashboard (và tiện thể phát hiện thêm ở `ReportService.getSitePresenceReport` — báo cáo hiện diện site, cùng lỗi gốc) dùng `LocalDate.now(ZoneOffset.UTC)` hoặc thậm chí `LocalDate.now()` không có timezone (theo giờ hệ điều hành container, không xác định) để tính "hôm nay". Vì `Tenant`/`Site` đã có sẵn field `timezone` (mặc định `UTC` nếu tenant chưa cấu hình) nhưng không được các dashboard sử dụng, toàn bộ số liệu "hôm nay"/"tháng này" bị lệch tới 7 tiếng đối với tenant ở múi giờ Việt Nam (UTC+7) — đúng lúc quan trọng nhất (đầu ca sáng, khi HR cần số liệu "ai đã có mặt hôm nay" chính xác nhất).

**Đã sửa**: dùng `tenant.getTimezone()` cho 2 dashboard tổng hợp toàn tenant (HR, Employee), và `site.getTimezone()` cho phần tính theo từng site cụ thể (Supervisor dashboard, ReportService — nơi đối tượng Site đã sẵn có trong vòng lặp, cho độ chính xác cao nhất khi các site trong cùng tenant có thể ở nhiều múi giờ khác nhau về lý thuyết).

---

## 4. Danh sách thay đổi kỹ thuật

| File | Thay đổi |
|---|---|
| `CheckinRepository.java` | `countOpenSessions`/`findOpenSessionsBySite` nhận thêm tham số `since` (giới hạn ngày); thêm `countByTenantIdAndEmployeeIdAndStatusAndDeletedAtIsNull` |
| `ViolationRepository.java` | Thêm `countByTenantIdAndEmployeeIdAndResolvedFalseAndDeletedAtIsNull` |
| `HrDashboardService.java` | Dùng tenant timezone cho "hôm nay"/"đầu tháng"; tính `onSiteNow` 1 lần dùng chung cho `attendance` + `sites` (trước đây gọi query giống hệt 2 lần); truyền `since` vào `countOpenSessions` |
| `SupervisorDashboardService.java` | Dùng tenant timezone; truyền `since` vào `findOpenSessionsBySite` |
| `EmployeeDashboardService.java` | Dùng tenant timezone; thêm `buildAlerts()` (unread notifications + pending explanations) |
| `EmployeeDashboardResponse.java` | Thêm `alerts` (nested `AlertsSummary`: `unreadNotifications`, `pendingExplanations`) |
| `HrDashboardResponse.java` | Cập nhật mô tả field `attendance` (không còn "UTC date" cứng) |
| `ReportService.java` (`getSitePresenceReport`) | Dùng site timezone thay vì `LocalDate.now()` không xác định; truyền `since` vào `findOpenSessionsBySite` |

Build lại qua `docker restart fams-api`, xác nhận compile sạch (404 file, 0 lỗi), test trực tiếp bằng curl trên dữ liệu thật cho cả 3 dashboard.

---

## 5. Giới hạn đã biết

- Bộ test có sẵn `tests/dashboard/*.sh` (3 file) fail với `SETUP FAILED: tenant` — cùng lỗi kịch bản test có từ trước (thiếu field `ownerEmail` khi tạo tenant test), không liên quan thay đổi hôm nay. Đã bù bằng live-test trực tiếp qua curl cho cả 3 endpoint, bao gồm 1 test dựng tình huống lỗi có chủ đích để xác nhận bug "phiên quên checkout" là thật trước khi sửa, không phải suy đoán.
- `SupervisorDashboardService`/`HrDashboardService` dùng timezone tenant làm mặc định cho các phép tính không gắn liền 1 site cụ thể — đây là lựa chọn thực dụng phù hợp thực tế dữ liệu hệ thống (mọi tenant trong seed đều là công ty Việt Nam, 1 múi giờ); nếu về sau có tenant đa quốc gia với site nhiều múi giờ khác nhau, phần tổng hợp toàn tenant (không phải phần theo từng site — phần đó đã dùng site timezone) sẽ cần thiết kế lại theo "ngày làm việc" riêng cho từng site thay vì 1 mốc "hôm nay" chung.

---

## 6. Kết luận

3 dashboard vai trò (Employee/HR/Site Supervisor) đã tồn tại sẵn trong code nhưng chưa từng được audit nghiệp vụ — lần audit này phát hiện 1 lỗi dữ liệu nghiêm trọng (đếm sai người "đang có mặt" do không giới hạn theo ngày, ảnh hưởng cả 2 dashboard vai trò quản lý lẫn báo cáo hiện diện site), 1 lỗi múi giờ ảnh hưởng độ chính xác số liệu "hôm nay" trên cả 3 dashboard, và 1 gap tính năng thật so với yêu cầu (thiếu thông báo/việc cần làm trên dashboard nhân viên). Cả 3 đã sửa, build, và verify trực tiếp bằng dữ liệu thật — bao gồm dựng tình huống lỗi có chủ đích để chứng minh bug tồn tại trước khi sửa, không chỉ đọc code suy luận. 5 story xử lý vi phạm còn lại đã đúng từ các đợt audit trước, không phát sinh vấn đề mới.
