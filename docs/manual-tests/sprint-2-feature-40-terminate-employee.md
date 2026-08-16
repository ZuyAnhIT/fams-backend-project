# Kịch bản test thủ công — #40 Tạm ngừng/nghỉ việc nhân viên

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không có terminated_at; không tự hủy assignment/Face ID". Đã xác
nhận lại qua code hiện tại — **1 phần đã sửa, 1 phần vẫn thiếu, và có 1 phần SỬA DỞ DANG cần đặc
biệt chú ý:**

- **Tự thu hồi Face ID: ĐÃ SỬA** — khi chuyển status sang "terminated",
  `FaceIdService.autoRevokeOnTermination` được gọi, thu hồi đăng ký khuôn mặt đúng (no-op nếu
  chưa từng đăng ký/đã thu hồi từ trước).
- **Cột `terminated_at`: ĐÃ VÁ (2026-08-16)** — thêm cột `terminated_at` (migration V95), set khi
  chuyển sang "terminated", clear về null khi HR đảo ngược quyết định (chuyển khỏi terminated).
  Web Admin: trang chi tiết nhân viên giờ hiện thêm tag "Từ dd/MM/yyyy" cạnh tag trạng thái khi
  đã nghỉ việc.
- **✅ Tự hủy assignment: ĐÃ VÁ (2026-08-16) — đây từng là gap nghiêm trọng nhất, giờ đã sửa.**
  `EmployeeService.changeEmployeeStatus` giờ set `assignment.status="cancelled"` (giống hệt
  `AssignmentService.cancelAssignment`) cho MỌI assignment đang active của nhân viên khi terminate,
  không chỉ hủy Random Check đang chờ như trước. Đồng thời sửa lại đúng nội dung modal xác nhận
  trên Web Admin (trước đây modal ghi sai là "các phân công hiện có không tự kết thúc" — nay đã
  cập nhật đúng: "...cùng các phân công công trình đang active của nhân viên này, sẽ tự động bị
  hủy").

**Test qua UI thật (Playwright, 2026-08-16) — case 4 (gap nghiêm trọng) đã xác nhận SỬA XONG:**
tạo nhân viên "Test UITerminate" + 1 assignment active tại site test (qua API, để chuẩn bị dữ
liệu nhanh) → vào danh sách Nhân viên trên Web Admin thật → bấm badge trạng thái → "Đánh dấu Đã
nghỉ việc" → xác nhận modal (đọc đúng nội dung mới) → bấm "Xác nhận" → vào trang chi tiết nhân
viên: header hiện tag "Đã nghỉ" + "Từ 16/08/2026" đúng ngày → tab "Workspace & Phân công": phân
công tại site vừa gán hiện tag "Đã hủy" (trước đây sẽ vẫn hiện "Đang hoạt động"/active vĩnh viễn).
Xác nhận qua DB: `assignments.status='cancelled'`, `employees.terminated_at` có giá trị đúng
timestamp.

---

## A. Test trên Web Admin

### 1. Chuyển trạng thái Tạm nghỉ (inactive) — happy path
- Vào danh sách Nhân viên, đổi trạng thái 1 người sang "Tạm nghỉ" qua menu trạng thái.
- **Kỳ vọng:** đổi thành công ngay, không cần tải lại; người này không đăng nhập/chấm công được
  trong lúc tạm nghỉ (nếu tiện, thử đăng nhập bằng tài khoản đó, kỳ vọng bị chặn — có thể bỏ qua
  case này nếu không có tài khoản test phù hợp).

### 2. Chuyển trạng thái Đã nghỉ việc (terminated) — happy path
- Đổi 1 nhân viên khác (đã có Face ID đăng ký + đang có assignment active) sang "Đã nghỉ việc".
- **Kỳ vọng:** đổi thành công, có cảnh báo rõ ràng trước khi xác nhận (đọc kỹ nội dung modal xác
  nhận sẵn có).

### 3. ✅ Xác nhận Face ID tự bị thu hồi sau khi nghỉ việc
- Sau case 2, xem tab Face ID của nhân viên vừa terminate.
- **Kỳ vọng theo code hiện tại:** trạng thái Face ID chuyển "Đã thu hồi" — xác nhận đúng gap cũ đã
  sửa.

### 4. ✅ Xác nhận gap NGHIÊM TRỌNG — Assignment tự hủy sau khi nghỉ việc — ĐÃ VÁ
- Trước khi terminate, ghi lại rõ assignment (phân công công trình) đang active của nhân viên ở
  case 2 (xem tab Workspace/Phân công ở trang chi tiết, hoặc màn Công trình → xem danh sách phân
  công của công trình đó).
- Sau khi terminate (case 2), xem lại đúng assignment đó (trang chi tiết nhân viên, hoặc màn quản
  lý phân công của công trình).
- **Kết quả thật (2026-08-16, test qua UI thật):** assignment tự chuyển tag "Đã hủy" ngay sau khi
  terminate — xác nhận đúng gap nghiêm trọng đã được vá, không còn tình trạng nhân viên nghỉ việc
  nhưng hệ thống vẫn coi đang được phân công công trình.

### 5. Xác nhận Random Check đang chờ bị hủy đúng (phần đã sửa, không phải phần gap)
- Nếu nhân viên ở case 2 đang có 1 Kiểm tra ngẫu nhiên (Random Check) ở trạng thái "đã gửi, chưa
  phản hồi" lúc terminate.
- **Kỳ vọng:** Random Check đó tự chuyển "đã hủy" ngay khi terminate — không cần nhân viên phản
  hồi nữa (khớp đúng phần code ĐÃ hoạt động).

### 6. Chuyển ngược lại Hoạt động (active) sau khi đã Tạm nghỉ/Đã nghỉ việc
- Đổi 1 nhân viên đã tạm nghỉ (case 1) trở lại "Hoạt động".
- **Kỳ vọng:** đổi lại bình thường, không lỗi. Riêng trường hợp đã "Đã nghỉ việc" rồi active lại
  — quan sát kỹ Face ID có tự động phục hồi hay vẫn ở trạng thái "Đã thu hồi" (cần đăng ký lại thủ
  công), ghi lại đúng thực tế.

### 7. ✅ Xác nhận gap "không có terminated_at" — ĐÃ VÁ
- Sau khi terminate ở case 2, kiểm tra có trường/thời điểm "Ngày nghỉ việc" hiển thị ở đâu không
  (trang chi tiết, cột danh sách, export Excel).
- **Kết quả thật (2026-08-16):** CÓ — trang chi tiết nhân viên hiện tag "Từ dd/MM/yyyy" cạnh tag
  trạng thái "Đã nghỉ" ngay ở header, đúng ngày terminate thật.

---

## Ghi chú
Case 4 là trọng tâm tuyệt đối của đợt test này — đã xác nhận vá xong qua test UI thật (không chỉ
qua code), kèm bằng chứng DB (`assignments.status='cancelled'`). Case 1-3, 5-7 đều pass, không có
gap còn tồn đọng ở tính năng này.
