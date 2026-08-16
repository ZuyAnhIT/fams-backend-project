# Kịch bản test thủ công — #38 Tạo nhân viên thủ công

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi ✅ ĐÃ XONG, không có gap. Đã xác nhận lại qua code hiện tại: đúng như
audit — kiểm tra quyền, kiểm tra giới hạn gói (`planLimitEnforcementService.assertEmployeeLimit`),
tự sinh mã nhân viên nếu bỏ trống + chặn trùng mã, ghi audit `employee_created` đầy đủ. Riêng đợt
sửa 2026-08-16 vừa thêm trường mới `plannedRoleId` ("Vai trò dự kiến") vào đúng luồng này — đã
test kỹ khi sửa, nhưng cần xác nhận lại 1 lần chính thức trong loạt test này.

⚠️ Phát hiện khi research code: có 1 route riêng `/customer/employees/create` (ngoài modal "Thêm
hồ sơ" ở màn danh sách) — cần xác nhận đây có phải luồng trùng lặp hay đã gộp chung, tránh 2 nơi
hành vi khác nhau.

---

## A. Test trên Web Admin

### 1. Tạo nhân viên thủ công — happy path đầy đủ
- Vào Nhân viên → "Thêm hồ sơ (chưa cần đăng nhập)", điền đủ Tên/Họ/Email/SĐT/Mã NV/Chức
  vụ/Phòng ban/Ngày vào làm/Vai trò dự kiến.
- **Kỳ vọng:** tạo thành công, nhân viên mới xuất hiện ngay trong danh sách, chưa có tài khoản
  đăng nhập ("Chưa đăng ký" ở cột liên quan nếu có), audit `employee_created` được ghi.

### 2. Tạo không nhập mã nhân viên — tự sinh mã
- Tạo 1 nhân viên khác, để trống Mã nhân viên.
- **Kỳ vọng:** hệ thống tự sinh mã theo prefix cấu hình của tenant (xem Cấu hình Công ty), không
  lỗi, không trùng với nhân viên đã có.

### 3. Tạo trùng mã nhân viên đã nhập thủ công
- Tạo 1 nhân viên với mã trùng với nhân viên đã tồn tại (nhập tay, không để trống).
- **Kỳ vọng:** lỗi 409 rõ ràng, không tạo bản ghi.

### 4. Tạo khi công ty đã đạt giới hạn nhân viên theo gói
- Nếu tenant test gần/đạt giới hạn `maxEmployees`, thử tạo thêm.
- **Kỳ vọng:** bị chặn với thông báo rõ ràng. Nếu tenant còn nhiều chỗ trống, ghi "không tái hiện
  được, để dành khi test riêng Epic Subscription/Plan".

### 5. Xác nhận route `/customer/employees/create` (nếu còn tồn tại và khác EmployeeFormModal)
- Thử truy cập trực tiếp URL `/customer/employees/create` (gõ tay hoặc qua link nếu có).
- **Kỳ vọng:** hoặc redirect/mở đúng cùng 1 form với modal "Thêm hồ sơ", hoặc xác nhận route này
  không còn dùng (404/không có link nào trỏ tới) — ghi lại đúng thực tế quan sát được, tránh 2
  luồng tạo nhân viên có hành vi lệch nhau.

### 6. Chọn "Vai trò dự kiến" — xác nhận lưu đúng (đã test kỹ lúc sửa, xác nhận lại 1 lần)
- Tạo 1 nhân viên, chọn 1 role tùy chỉnh ở ô "Vai trò dự kiến".
- **Kỳ vọng:** lưu đúng; nếu sau đó mời (gửi email) đúng email này mà không chọn role, lời mời tự
  dùng đúng role dự kiến (xem lại chi tiết ở kịch bản #33 nếu cần test full chuỗi).

---

## Ghi chú
Case 1-3, 6 rủi ro fail thấp (đã xác nhận qua code + đã test 1 phần lúc sửa #33-36). Case 5 là
trọng tâm mới — chỉ để xác nhận không có luồng tạo nhân viên thứ 2 gây lệch hành vi.
