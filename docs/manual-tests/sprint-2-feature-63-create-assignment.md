# Kịch bản test thủ công — #63 Tạo phân công nhân viên vào site

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không có assignment_type/trạng thái planned; check trùng chỉ theo
site không theo khoảng ngày thật". Đã xác nhận và xử lý (2026-08-17) theo quyết định nghiệp vụ của
chủ dự án:

- **`assignment_type`, trạng thái `planned`: XÁC NHẬN không tồn tại, KHÔNG làm thêm** — đây là gap
  kiến trúc không ảnh hưởng nghiệp vụ hiện tại (giống pattern province/workspace ở epic Site),
  không nằm trong quyết định lần này.
- **Chống trùng CÙNG SITE: ĐÃ NÂNG CẤP theo quyết định của chủ dự án** — trước đây chặn tuyệt đối
  (không cho tạo assignment thứ 2 dù không chồng ngày), nay dùng CHUNG logic chồng giờ thực tế với
  cross-site (`assertNoConflicts`, gộp 2 nguồn xung đột: khác site + cùng site). Đã bỏ hẳn unique
  index DB cũ `uq_assignments_employee_site_active` (migration `V100`) — giờ có thể tạo trước 1
  assignment tương lai không chồng ngày với assignment hiện tại, đúng như yêu cầu nghiệp vụ (lịch
  làm việc thực tế: đổi ca, gia hạn hợp đồng theo giai đoạn).
- **Chống trùng CROSS-SITE:** giữ nguyên hành vi cũ (đã đúng từ trước), chỉ refactor code dùng
  chung hàm với same-site.
- **Gap mới phát hiện: KHÔNG ghi audit log khi tạo phân công — ĐÃ VÁ**, action `assignment_created`.

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. ✅ Tạo phân công — happy path, TEST LIVE
- Tạo phân công nhân viên "My Assign" vào Site One (2026-08-01 → 2026-08-31, worker) qua API thật.
- **Kết quả thật:** 201, đầy đủ `employeeSummary`/`shiftSummary` embedded.

### 2. ✅ Tạo phân công thứ 2 CÙNG SITE, CÙNG nhân viên, KHÔNG chồng ngày — ĐÃ NÂNG CẤP, TEST LIVE
- Tạo tiếp phân công cho đúng nhân viên đó, đúng site đó, nhưng từ 2026-09-01 (không có `endDate`,
  không chồng với assignment tháng 8 ở trên).
- **Kết quả thật (2026-08-17):** 201 — TẠO THÀNH CÔNG, xác nhận đúng hành vi mới sau khi nâng cấp
  (trước đây sẽ bị chặn 409 dù không chồng ngày).

### 3. ✅ Tạo phân công CÙNG SITE, chồng giờ thực tế — vẫn phải chặn, TEST LIVE
- Tạo phân công thứ 3 cho cùng nhân viên, cùng site, từ 2026-08-15 → 2026-09-15 (chồng cả với
  assignment tháng 8 lẫn tháng 9 ở trên).
- **Kết quả thật:** 409 "Employee already has an overlapping active assignment at this site during
  this period — the shift hours overlap with an existing assignment" — xác nhận vẫn chặn đúng khi
  THỰC SỰ chồng giờ, không phải bỏ hẳn kiểm tra.

### 4. Trùng giờ làm CROSS-SITE — có chồng lấn thật
- Không đổi hành vi so với trước, giữ nguyên logic đã xác nhận đúng từ epic trước.

### 5. Chọn vai trò không hợp lệ, gán nhân viên terminated, shift sai site, endDate<startDate
- Toàn bộ 4 case này: script tự động `test_create_assignment.sh` xác nhận vẫn pass, không hồi quy
  sau khi refactor logic chống trùng.

### 6. ✅ Xác nhận gap "không ghi audit log khi tạo phân công" — ĐÃ VÁ, TEST LIVE
- Sau case 1, kiểm tra `audit_logs` qua DB.
- **Kết quả thật:** có đúng bản ghi `assignment_created`, entity `Assignment`, `new_value` chứa đầy
  đủ snapshot (siteId, employeeId, shiftId, startDate, endDate, daysOfWeek, role, status).

---

## Ghi chú
Toàn bộ case đã test live: script tự động `test_create_assignment.sh` 14/14 pass +
`test_assignment_recurring_schedule.sh` 10/10 pass (không hồi quy sau khi đổi logic chống trùng) +
test tay qua API/DB xác nhận đúng cả 2 chiều (cho phép khi không chồng ngày, chặn khi chồng giờ
thật). Quyết định nghiệp vụ quan trọng nhất: chủ dự án đã CHỌN nâng cấp chống trùng cùng site theo
khoảng ngày thay vì giữ chặn tuyệt đối — đã triển khai đúng theo lựa chọn này. Đã đóng — ĐÃ KHÓA.
