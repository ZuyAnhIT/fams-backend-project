# Kịch bản test thủ công — #33 Mời nhân viên bằng email

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không chọn workspace mặc định; không ghi audit". Đã xác nhận lại
qua code hiện tại — **cả 2 gap vẫn còn thật, chưa sửa**:
- `InviteEmployeeRequest` chỉ có `email, phone, firstName, lastName, roleId` — không có
  `workspaceId`. Người được mời không được gán vào phòng ban/workspace nào lúc gửi lời mời.
- `EmployeeInvitationService` (gửi lời mời) không gọi `AuditLogService` ở đâu cả — khác với
  `EmployeeService` (tạo/sửa nhân viên) đã có audit từ trước. Đây là gap thật, không phải hiểu
  nhầm.

Không chặn khóa tính năng (luồng mời chính vẫn hoạt động đúng, đã tự test qua UI nhiều lần trong
đợt RBAC vừa rồi để tạo tài khoản test) — nhưng cần bạn xác nhận lại để quyết định có ưu tiên sửa
2 gap này không trước khi khóa hẳn #33.

---

## A. Test trên Web Admin

### 1. Mời nhân viên mới — happy path
- Đăng nhập Company Admin/HR (có quyền `employees:create`), vào Nhân viên → "Mời tham gia (gửi
  email)".
- Nhập email chưa tồn tại trong tenant, chọn 1 role.
- **Kỳ vọng:** gửi thành công, toast xác nhận; lời mời xuất hiện ở tab "Lời mời đã gửi" với trạng
  thái "pending".

### 2. Mời email đã có lời mời pending
- Mời lại đúng email vừa gửi ở case 1 (khi vẫn đang pending).
- **Kỳ vọng:** báo lỗi trùng (409) — không tạo thêm lời mời thứ 2 cho cùng email.

### 3. Mời khi công ty đã đạt giới hạn nhân viên theo gói (plan limit)
- Nếu tenant test đang gần/đạt giới hạn `maxEmployees` của gói hiện tại, thử mời thêm.
- **Kỳ vọng:** bị chặn với thông báo rõ ràng về giới hạn gói — nếu tenant test còn nhiều chỗ
  trống thì bỏ qua case này (ghi chú "không tái hiện được, tenant còn nhiều slot").

### 4. ⚠️ Xác nhận gap "không chọn workspace mặc định"
- Ở màn "Mời nhân viên mới" (case 1), quan sát kỹ có ô chọn phòng ban/workspace hay không.
- **Kỳ vọng theo code hiện tại:** KHÔNG có ô này — chỉ có Email, SĐT, Họ, Tên, Vai trò. Nếu thực
  tế test thấy có (đã được bổ sung), ghi lại là tin tốt bất ngờ, không phải fail.

### 5. ⚠️ Xác nhận gap "không ghi audit"
- Sau khi gửi lời mời ở case 1, vào Nhật ký audit, tìm hành động liên quan tới lời mời vừa gửi
  (lọc theo Entity/Action nếu có, hoặc theo thời gian gần nhất).
- **Kỳ vọng theo code hiện tại:** KHÔNG tìm thấy bản ghi audit nào cho hành động gửi lời mời (dù
  các hành động RBAC khác như role_created/role_assigned đều có). Nếu thực tế thấy có, ghi lại là
  tin tốt bất ngờ.

---

## Ghi chú
Case 1-2 đã được gián tiếp xác nhận hoạt động đúng qua việc dùng luồng mời này nhiều lần để tạo
tài khoản test trong đợt kiểm thử RBAC #24-31 (2026-08-15) — không phải lo case này fail hoàn
toàn. Case 4-5 là trọng tâm để chính thức ghi nhận 2 gap đã biết, không phải để tìm bug mới.
