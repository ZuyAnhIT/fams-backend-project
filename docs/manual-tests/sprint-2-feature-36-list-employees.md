# Kịch bản test thủ công — #36 Danh sách nhân viên

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu filter `face_registered` và `workspace`. Đã xác nhận lại qua code
hiện tại — **gap vẫn còn thật, chính xác**: `EmployeeController.listEmployees` chỉ nhận
`search, status, department, sortBy, sortDir, page, size` — không có tham số lọc theo trạng thái
đăng ký Face ID. Filter "department" tồn tại và lọc theo tên phòng ban (từ V71, phòng ban đã hợp
nhất vào Workspace) — coi như 1 dạng lọc workspace gián tiếp, nhưng KHÔNG phải lọc theo
`workspaceId` chính danh và không tính các workspace phụ nếu nhân viên thuộc nhiều workspace. Dữ
liệu Face ID (`faceId`) đã có sẵn trong response từng nhân viên — chỉ thiếu tham số lọc phía
server, không phải thiếu dữ liệu.

---

## A. Test trên Web Admin

### 1. Xem danh sách nhân viên — happy path
- Đăng nhập Company Admin/HR, vào màn Nhân viên.
- **Kỳ vọng:** hiện đủ nhân viên trong tenant, có phân trang, cột Vai trò/Trạng thái/Face ID hiển
  thị đúng dữ liệu.

### 2. Tìm kiếm theo tên/email/mã nhân viên
- Gõ 1 phần tên, rồi 1 phần email, rồi mã nhân viên (nếu có) vào ô tìm kiếm.
- **Kỳ vọng:** lọc đúng theo từng lượt tìm.

### 3. Lọc theo trạng thái (active/inactive/terminated)
- Chọn từng trạng thái trong bộ lọc.
- **Kỳ vọng:** kết quả đúng theo từng trạng thái.

### 4. Lọc theo phòng ban (department)
- Chọn 1 phòng ban cụ thể trong bộ lọc (nếu tenant test có từ 2 phòng ban trở lên).
- **Kỳ vọng:** chỉ hiện nhân viên thuộc đúng phòng ban đó.

### 5. Sắp xếp theo ngày tạo
- Đổi chiều sắp xếp (mới nhất trước / cũ nhất trước).
- **Kỳ vọng:** thứ tự đổi đúng.

### 6. ⚠️ Xác nhận gap "không có filter face_registered"
- Quan sát bộ lọc trên màn danh sách, tìm ô lọc riêng theo "Đã đăng ký Face ID" / "Chưa đăng ký".
- **Kỳ vọng theo code hiện tại:** KHÔNG có bộ lọc này — dữ liệu Face ID chỉ hiển thị dạng cột/badge
  trên từng dòng, không lọc được qua tham số riêng ở URL/API. Nếu thực tế thấy có, ghi lại là tin
  tốt bất ngờ.

### 7. ⚠️ Xác nhận filter "department" chỉ là lọc gián tiếp, không phải workspaceId thật
- Nếu tenant test có 1 nhân viên thuộc nhiều workspace (qua `WorkspaceMember`, không chỉ
  `departmentId` chính), thử lọc theo 1 trong các workspace phụ đó (không phải department chính).
- **Kỳ vọng theo code hiện tại:** nhân viên đó KHÔNG xuất hiện khi lọc theo workspace phụ (vì
  filter hiện tại chỉ so khớp `Employee.department`, không join qua `WorkspaceMember`). Nếu tenant
  test chưa có nhân viên đa-workspace, ghi lại "không tái hiện được, để dành Sprint 2 khi test kỹ
  Epic Workspace".

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright. Case 1-5 là luồng nghiệp vụ chính, rủi ro fail
thấp (đã dùng gián tiếp nhiều lần trong các đợt test trước). Case 6-7 là trọng tâm để xác nhận
đúng 2 gap đã biết.
