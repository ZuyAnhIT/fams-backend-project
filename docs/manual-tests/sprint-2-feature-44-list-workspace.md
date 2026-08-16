# Kịch bản test thủ công — #44 Danh sách workspace

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "thiếu số thành viên trong response". Đã xác nhận lại qua code
hiện tại:
- **Gap "thiếu số thành viên": ĐÃ SỬA** — `WorkspaceService.listWorkspaces` (và endpoint `/tree`)
  giờ tự batch-load số thành viên active qua `workspaceMemberRepository.countActiveByWorkspaceIdIn`,
  trả về trong response qua field `activeMemberCount` (kèm `childWorkspaceCount`). Audit note cũ
  đã lỗi thời, không phải gap thật hiện tại — cần xác nhận lại qua UI thật thay vì giả định vẫn
  còn thiếu.

---

## A. Test trên Web Admin

### 1. Xem danh sách/cây workspace — happy path
- Vào màn Phòng ban, quan sát danh sách hoặc cây tổ chức hiển thị.
- **Kỳ vọng:** hiển thị đúng toàn bộ workspace của tenant, đúng cấu trúc cha-con (nếu có).

### 2. ✅ Xác nhận số thành viên hiển thị đúng — gap cũ đã sửa, test qua UI thật
- Chọn 1 workspace đã có ít nhất 1-2 thành viên active (gán qua #46 nếu cần).
- Quan sát số thành viên hiển thị trên card/dòng workspace đó.
- **Kết quả thật (2026-08-16):** số thành viên hiển thị đúng ("1 người"/"0 người" trên cây tổ
  chức thật, khớp chính xác với số lượng active trong DB), tự cập nhật ngay sau khi gán/gỡ/chuyển
  thành viên mà không cần tải lại trang.

### 3. Tìm kiếm theo tên/mã workspace
- Nhập từ khóa vào ô tìm kiếm khớp 1 phần tên hoặc mã của 1 workspace.
- **Kỳ vọng:** kết quả lọc đúng, chỉ hiện workspace khớp từ khóa.

### 4. Lọc theo trạng thái active/inactive
- Áp filter trạng thái (nếu UI có), quan sát kết quả.
- **Kỳ vọng:** chỉ hiện đúng các workspace khớp trạng thái đã chọn.

### 5. Xem cây tổ chức nhiều cấp (3+ tầng, nếu có dữ liệu)
- Nếu tenant có cấu trúc workspace từ 3 tầng trở lên (cha → con → cháu), quan sát cách hiển thị.
- **Kỳ vọng:** cây hiển thị đúng thứ bậc, không bị phẳng hóa hay sai vị trí lồng nhau.

---

## Ghi chú
Case 1-5 đều pass. Case 2 đã xác nhận gap cũ sửa đúng qua UI thật (không chỉ qua code).
