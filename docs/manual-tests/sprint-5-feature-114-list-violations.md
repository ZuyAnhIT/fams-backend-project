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

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
Trọng tâm khi test: case 4 (gap affectsAttendance — ưu tiên vá trước vì chi phí thấp, dữ liệu sẵn
có) so với case 3 (gap severity — chi phí cao hơn nhiều, cần quyết định nghiệp vụ có thực sự cần
phân loại mức độ nghiêm trọng hay dùng `violationType` + `affectsAttendance` hiện tại đã đủ phân
biệt mức độ quan trọng). Case 1-2 rủi ro fail thấp, đã có `test_hr_list_violations.sh` phủ.
