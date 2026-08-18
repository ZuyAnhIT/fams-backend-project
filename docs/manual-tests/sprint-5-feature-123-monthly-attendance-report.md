# Kịch bản test thủ công — #123 Báo cáo công tháng

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): không nằm trong 14 issue gốc — audit code hiện tại (2026-08-18), cùng đợt
audit module Report với #122/#124/#125, phát hiện 2 gap độc lập:

- **❌ GAP #1: thiếu filter workspace** — như #122, AC yêu cầu nhưng chưa implement.
- **❌ GAP #2: thiếu `violationCount` per-employee** — AC báo cáo công tháng yêu cầu số vi phạm
  từng nhân viên trong tháng, nhưng response chỉ có `daysWithRandomCheckFailure` (chỉ tính vi
  phạm loại random-check-derived, không phải TOÀN BỘ loại vi phạm).

## ✅ ĐÃ VÁ (2026-08-18)

- `GET /reports/attendance/monthly` nhận thêm `workspaceId` — cùng pattern resolve-employeeIds-trước
  như #122, áp dụng lên `aggregateMonthlyRows` (không đụng vào native aggregate query phức tạp sẵn
  có — lọc kết quả sau khi fetch, trong Java, để tránh rủi ro sửa SQL native).
- `AttendanceHrMonthlyResponse` thêm field `violationCount` — đếm TOÀN BỘ loại vi phạm (không chỉ
  random-check) của nhân viên đó tại site đó trong tháng, qua query nhóm mới
  `ViolationRepository.countByEmployeeAndSiteInRange`, merge vào response trong Java (tách biệt
  khỏi aggregate query gốc).
- Web Admin: thêm dropdown workspace + cột "Vi phạm" mới trong bảng báo cáo tháng.

---

## A. Test trên Backend

### 1. ✅ `GET /reports/attendance/monthly` không kèm `workspaceId` — không đổi hành vi cũ ngoài
   field `violationCount` mới (giá trị mặc định đúng, không phá field khác)

### 2. ✅✅ (Case quan trọng nhất) `workspaceId=X` chỉ trả nhân viên thuộc workspace X
- Setup: 2 nhân viên cùng site, cùng có attendance summary tháng hiện tại; chỉ 1 người thuộc
  workspace X.
- **Kỳ vọng — xác nhận đúng qua live API call:** không filter → `totalEmployees=2`; filter
  `workspaceId=X` → `totalEmployees=1`, đúng nhân viên.

### 3. ✅✅ `violationCount` đếm đúng TOÀN BỘ loại vi phạm, không chỉ random-check-derived
- Setup: 1 violation loại `no_response` cho 1 nhân viên trong tháng, KHÔNG có random-check
  failure nào (`daysWithRandomCheckFailure=0`).
- **Kỳ vọng — xác nhận đúng qua live API call:** nhân viên đó có `violationCount=1` dù
  `daysWithRandomCheckFailure=0` — chứng minh field mới bao quát rộng hơn field cũ, không trùng lặp.

## B. Test trên Web Admin

### 4. ✅✅ ĐÃ TEST LIVE qua Playwright thật — dropdown workspace + cột "Vi phạm" mới
- Mở tab Công tháng (không filter): stat "Nhân viên" = 2, bảng 2 dòng, cột "Vi phạm" hiện đúng số
  (Tag đỏ khi > 0).
- Chọn workspace trong dropdown mới → stat "Nhân viên" giảm đúng còn 1, bảng còn 1 dòng. Cột "Vi
  phạm" bị cắt chữ tiêu đề ở viewport rất rộng khi mới thêm — đã tăng `width` cột (110px) và
  `scroll.x` bảng (1410px) để hiện đủ, không còn bị cắt.

---

## Ghi chú
`tsc --noEmit` phía Web Admin sạch. Aggregate query gốc (`aggregateMonthly`, native SQL) không bị
sửa — filter workspace và violationCount đều xử lý ở tầng Java sau khi fetch, để tránh rủi ro trên
truy vấn phức tạp sẵn có.
