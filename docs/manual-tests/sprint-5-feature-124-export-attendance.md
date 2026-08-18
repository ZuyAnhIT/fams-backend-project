# Kịch bản test thủ công — #124 Export bảng công ra Excel

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): không nằm trong 14 issue gốc — audit code hiện tại (2026-08-18), cùng đợt
audit module Report, phát hiện 3 gap độc lập trên endpoint export:

- **❌ GAP #1: thiếu filter workspace** — như #122/#123.
- **❌ GAP #2: thiếu dòng TOTAL** — AC yêu cầu file Excel có dòng tổng cộng để HR/kế toán không
  phải tự cộng trước khi đưa vào payroll; file cũ dừng ngay sau dòng nhân viên cuối.
- **❌ GAP #3: export payroll-affecting nhưng KHÔNG có audit log** — mọi mutation HR khác trong hệ
  thống (điều chỉnh công, override checkin...) đều ghi audit log, riêng export ảnh hưởng payroll
  lại không có dấu vết ai đã export phạm vi nào lúc nào.

## ✅ ĐÃ VÁ (2026-08-18)

- `GET /reports/attendance/export` nhận thêm `workspaceId`, dùng lại
  `aggregateMonthlyRows(..., workspaceId)` đã sửa ở #123 — nên workspace filter trên export và
  trên report tháng dùng chung 1 đường logic, không lệch nhau.
- Thêm dòng TOTAL cuối bảng Excel: tổng `presentDays`, `totalWorkMinutes`, `lateDays`,
  `totalLateMinutes`, `earlyLeaveDays`, `totalEarlyLeaveMinutes`, `totalOtMinutes`,
  `missingCheckoutDays`.
- Thêm cột "Violation Count" (từ field `violationCount` mới ở #123) vào file Excel.
- Thêm `AuditLogService.record(..., action="EXPORT_ATTENDANCE", ...)` sau khi export thành công,
  ghi `year`/`month`/`siteId`/số dòng — bọc trong try/catch riêng để lỗi ghi audit không làm hỏng
  request export (người dùng vẫn tải được file dù audit log lỗi).

### 🐛 2 bug runtime phát hiện khi live-test (KHÔNG có trong code cũ, do sửa lỗi trong lúc vá):
1. **NPE khi `employeeCode` null** — `Collectors.toMap(Employee::getId, Employee::getEmployeeCode, ...)`
   throw `NullPointerException` (Collectors.toMap không cho phép value null) khi nhân viên chưa
   được gán mã. **Đã vá:** map null → chuỗi rỗng trước khi đưa vào toMap.
2. **Audit log export bị Hibernate âm thầm bỏ qua, không lỗi, không ghi** —
   `exportMonthlyAttendance` chạy trong `@Transactional(readOnly=true)`; khi `AuditLogService.record()`
   (mặc định `REQUIRED`) join transaction đó, Hibernate set `FlushMode.MANUAL` cho session
   read-only → INSERT audit log không bao giờ được flush xuống DB, transaction commit như no-op,
   không exception, không log warning — **im lặng tuyệt đối, dễ bị bỏ sót nếu không live-test**.
   **Đã vá:** đổi `AuditLogService.record()` sang `@Transactional(propagation = REQUIRES_NEW)` —
   audit logging không còn phụ thuộc transaction mode của caller. Đây là fix chung, áp dụng cho
   MỌI caller của `record()`, không riêng export.

---

## A. Test trên Backend

### 1. ✅✅✅ (Case quan trọng nhất — đã từng crash) `GET /reports/attendance/export` trả file hợp
   lệ, không NPE
- **Kỳ vọng — xác nhận qua live API call:** HTTP 200, file `.xlsx` hợp lệ (mở được bằng
  `zipfile`/Excel), không còn NullPointerException dù có nhân viên chưa gán `employeeCode`.

### 2. ✅ Dòng TOTAL và cột "Violation Count" có trong file
- **Kỳ vọng — xác nhận qua live check nội dung file:** shared-strings của file chứa cả "TOTAL"
  (dòng cuối bảng) và "Violation Count" (tiêu đề cột mới).

### 3. ✅✅ `workspaceId=X` chỉ export nhân viên thuộc workspace X
- **Kỳ vọng — xác nhận qua live check:** file export không filter có 5 dòng dữ liệu (header + meta
  + 2 nhân viên + TOTAL); file export filter `workspaceId=X` có 4 dòng (thiếu đúng 1 nhân viên
  không thuộc workspace) — chênh lệch đúng 1 dòng, khớp kỳ vọng.

### 4. ✅✅ Audit log `EXPORT_ATTENDANCE` được ghi nhận đúng
- **Kỳ vọng — xác nhận qua live query DB sau fix REQUIRES_NEW:** 1 dòng `audit_logs` với
  `action='EXPORT_ATTENDANCE'`, `actor_id` đúng người export, `new_value` chứa
  `year`/`month`/`siteId`/`rows`. Trước khi vá bug #2 ở trên: 0 dòng dù request trả 200 — đã xác
  nhận lại sau fix, có đúng 1 dòng.

## B. Test trên Web Admin

### 5. ✅✅ ĐÃ TEST LIVE qua Playwright thật — nút Export truyền đúng `workspaceId`, file tải về đúng
- Trang Báo cáo > Công tháng, bấm "Xuất Excel" 2 lần qua UI thật (không chọn workspace, rồi chọn
  workspace), dùng `page.waitForEvent('download')` bắt file .xlsx thật tải về, mở bằng openpyxl để
  đếm số dòng dữ liệu thật (không chỉ đọc phản hồi API).
- **Kết quả:** file không filter có 2 dòng nhân viên (TOTAL Present Days=2); file filter workspace
  có đúng 1 dòng (nhân viên thuộc workspace, TOTAL Present Days=1) — xác nhận filter hoạt động
  xuyên suốt từ click UI tới nội dung file tải về.

---

## Ghi chú
Đây là gap nghiêm trọng nhất trong đợt #121-125 — export payroll-affecting bị NPE (crash hoàn
toàn, HTTP 500) trước khi vá, và audit log bị mất âm thầm sau lần vá đầu tiên (không phát hiện
được nếu chỉ dựa vào HTTP 200, cần query DB trực tiếp mới lộ ra). **Đã rà thêm và vá 1 gap hệ
thống khác phát sinh từ quá trình test live #121** (không liên quan trực tiếp export, nhưng phát
hiện cùng đợt): JWT chọn role/tenant "chính" không theo độ ưu tiên — xem
`docs/manual-tests/sprint-5-feature-121-supervisor-dashboard.md` mục "Phát hiện phụ" và
`docs/BACKLOG.md` mục 7.
