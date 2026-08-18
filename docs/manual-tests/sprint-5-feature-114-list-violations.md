# Kịch bản test thủ công — #114 HR xem danh sách vi phạm

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "thiếu filter affectsAttendance; severity chưa có trong
schema thật". Đã xác nhận lại qua code hiện tại — **ĐÚNG cả 2 điểm, KHÔNG lỗi thời, và thêm 1 điểm
làm rõ về "status":**

- **✅ Lọc đúng:** `employeeId, siteId, violationType, resolved (boolean), from/to (ngày)`. Sort
  theo `checkDate/createdAt/violationType/employeeId/siteId`. Phân trang đầy đủ.
- **❌ GAP thật #1 (xác nhận đúng): KHÔNG có filter `severity`** — vì bản thân field này KHÔNG TỒN
  TẠI trên entity `Violation` (chỉ có `ViolationSeverity` là enum TÍNH TOÁN/suy diễn ở tầng
  util, không phải cột lưu trong DB) — không phải "chưa thêm filter", mà "không có dữ liệu để lọc".
- **❌ GAP thật #2 (xác nhận đúng): KHÔNG có filter `affectsAttendance`** — khác gap #1, field NÀY
  THẬT SỰ TỒN TẠI trên entity (`affectsAttendance` boolean) nhưng `ViolationSpecification.build`
  không có predicate nào dùng nó — đây là filter CÓ THỂ thêm ngay (dữ liệu đã có sẵn), không cần
  đổi schema, khác hẳn mức độ phức tạp so với gap severity.
- **⚠️ Làm rõ: AC ghi "lọc theo status" nhưng hệ thống không có 1 enum `status` thống nhất** — chỉ
  có boolean `resolved` + text tự do `resolution` ("confirmed"/"dismissed"). Web Admin ĐÃ MAP đúng
  2 field này thành trải nghiệm lọc theo "trạng thái" cho người dùng — không phải gap UI, chỉ là
  khác cách đặt tên ở tầng dữ liệu so với AC.

---

## A. Test trên Backend

### 1. ✅ Lọc theo từng tiêu chí: employee, site, violationType, resolved, khoảng ngày
- **Kỳ vọng:** đúng kết quả theo từng điều kiện + kết hợp (AND).

### 2. ✅ Sort theo `checkDate`/`createdAt`/`violationType`/`employeeId`/`siteId`
- **Kỳ vọng:** thứ tự đúng theo từng lựa chọn sort.

### 3. ❌ Xác nhận gap "không có filter severity"
- Thử truyền `severity=high` vào query.
- **Kỳ vọng theo code hiện tại:** không có tác dụng/không tồn tại tham số — xác nhận đúng gap,
  KHÔNG THỂ vá đơn giản (cần thêm cột + logic tính severity trước).

### 4. ❌ Xác nhận gap "không có filter affectsAttendance"
- Thử truyền `affectsAttendance=true` vào query.
- **Kỳ vọng theo code hiện tại:** không có tác dụng — xác nhận đúng gap, CÓ THỂ vá nhanh (dữ liệu
  đã có sẵn trên entity, chỉ cần thêm predicate).

---

## B. Test trên Web Admin
- Trang danh sách vi phạm: xác nhận filter employee/site/loại/resolved/khoảng ngày hoạt động đúng,
  KHÔNG có filter severity/affectsAttendance (khớp đúng backend).

---

## ✅ ĐÃ VÁ (2026-08-18) — ĐÃ KHÓA

### Case 4 — gap `affectsAttendance`: ĐÃ VÁ
- `ViolationSpecification.build` thêm predicate `affectsAttendance` (field đã có sẵn trên entity).
- `ViolationService.listViolations` + `ViolationController` thêm tham số
  `?affectsAttendance=true|false`, giữ nguyên các overload cũ (không phá client cũ).
- Web Admin (`violation.component.tsx`): thêm dropdown "Ảnh hưởng công" vào lưới filter (6 cột,
  trước đó 5).

### Test live — ✅ PASS (2026-08-18)
Seed 2 violation cùng site/employee, 1 `affects_attendance=true` + 1 `=false`. Lọc
`?affectsAttendance=true` chỉ trả đúng bản ghi true, lọc `=false` chỉ trả đúng bản ghi false —
tách biệt hoàn toàn, không lẫn.

### Case 3 — gap `severity`: GIỮ NGUYÊN, chưa vá
Field không tồn tại trên entity, cần đổi schema (thêm cột + logic tính severity) — chi phí cao hơn
hẳn so với `affectsAttendance`, không nằm trong đợt vá nhanh này. Không phải business-blocker —
`violationType` + `affectsAttendance` hiện đã đủ phân biệt mức độ quan trọng cho phần lớn use-case.

## Ghi chú
Regression: 31/31 (bao gồm `test_hr_list_violations.sh`).
