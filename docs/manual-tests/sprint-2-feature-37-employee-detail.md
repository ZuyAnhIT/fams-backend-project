# Kịch bản test thủ công — #37 Xem chi tiết nhân viên

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi: "workspaces/assignments trong response bị hardcode rỗng, chưa nối với 2
module đó". Đã xác nhận lại qua code hiện tại — **gap này đã được sửa từ trước, audit note cũ đã
lỗi thời**: `EmployeeService.getEmployee` giờ lấy đúng dữ liệu thật — `workspaces` qua
`WorkspaceMemberRepository`, `assignments` qua `AssignmentRepository` (kèm map sang response qua
`AssignmentService`), cộng thêm `roles` (role đang active) và `faceId` (trạng thái Face ID). Không
có gap nào cần xác nhận lại — mục tiêu chính của đợt test này là xác nhận dữ liệu hiển thị đúng
qua UI thật, không phải tìm gap.

---

## A. Test trên Web Admin

### 1. Xem chi tiết nhân viên — happy path
- Vào danh sách Nhân viên, bấm "Chi tiết" trên 1 dòng bất kỳ (ưu tiên chọn nhân viên đã có
  workspace/assignment/role/Face ID để kiểm tra đủ các tab).
- **Kỳ vọng:** vào đúng trang chi tiết, hiện đủ thông tin cơ bản (tên, email, SĐT, mã NV, phòng
  ban, chức vụ, trạng thái).

### 2. Tab "Vai trò & Phân quyền" hiển thị đúng role đang giữ
- Chuyển sang tab role trên trang chi tiết.
- **Kỳ vọng:** hiện đúng (các) role tài khoản này đang giữ trong tenant — khớp với cột "Vai trò"
  ở màn danh sách.

### 3. Tab Workspace/Phân công hiển thị đúng dữ liệu thật (xác nhận gap cũ đã sửa)
- Chuyển sang tab workspace/phân công.
- **Kỳ vọng:** hiện đúng danh sách workspace nhân viên đang thuộc về, và đúng danh sách
  assignment (phân công công trình) đang có — **không rỗng** nếu nhân viên đó thực sự có dữ liệu
  (đối chiếu với màn Phòng ban / Công trình để so khớp).

### 4. Tab Face ID hiển thị đúng trạng thái
- Chuyển sang tab Face ID.
- **Kỳ vọng:** hiện đúng trạng thái (Chưa đăng ký/Đã đăng ký/Đã thu hồi) khớp với cột Face ID ở
  màn danh sách.

### 5. Nhân viên chưa có dữ liệu gì (mới tạo thủ công) — không lỗi, hiện rỗng đúng cách
- Xem chi tiết 1 nhân viên vừa tạo thủ công (chưa có workspace/assignment/role/Face ID).
- **Kỳ vọng:** không lỗi, không crash — các tab hiện trạng thái rỗng rõ ràng ("Chưa có workspace",
  "Chưa có phân công"...).

### 6. Quyền xem PII (email/SĐT che một phần)
- Đăng nhập tài khoản không có quyền `employees:pii:read`, xem chi tiết 1 nhân viên có email/SĐT.
- **Kỳ vọng:** email/SĐT hiện dạng che một phần (không đổi so với hành vi ở màn danh sách).

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright. Case 3 là trọng tâm để xác nhận chính thức gap
cũ (07-22) đã được sửa — rủi ro fail thấp vì đã xác nhận qua code.
