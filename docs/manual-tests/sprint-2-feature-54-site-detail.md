# Kịch bản test thủ công — #54 Xem chi tiết công trình

**Nền tảng: Backend, Web Admin, Mobile App.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không hiển thị supervisor (Site không có trường này)". Đã xác nhận
lại qua code hiện tại:

- **Bản đồ tâm (lat/long): CÓ, đúng.**
- **Geofence active: CÓ, đúng** — hiển thị geofence đang active của site (null nếu chưa có).
- **Shift: CÓ, nhưng khác cách hiểu "shift default"** — trả về DANH SÁCH các shift template đang
  active của site (`shifts`, số nhiều), không phải 1 "shift mặc định" duy nhất như AC gốc ngụ ý —
  hệ thống hiện không có khái niệm "shift default" (chỉ có "active hay không").
- **Assignment active: CÓ, nhưng chỉ là SỐ ĐẾM, không phải danh sách** — response chi tiết site chỉ
  trả `activeAssignmentCount` (số nguyên); danh sách đầy đủ từng assignment nằm ở tab riêng "Nhân
  sự phân công" trên Web Admin (gọi API khác), không nhúng sẵn trong response chi tiết site.
- **Supervisor: gap UX ĐÃ VÁ (2026-08-16)** — `SiteDetailResponse` giờ có field `supervisors`
  (danh sách, vì 1 site có thể có nhiều supervisor active cùng lúc), lấy từ
  `Assignment.role='supervisor' AND status='active'` (không thêm cột mới trên `sites`, tận dụng
  đúng mô hình dữ liệu đã có). Web Admin: card "Thông tin công trình" giờ hiện dòng "Người phụ
  trách" với tag tên từng supervisor ngay tại trang chi tiết, không cần mở tab riêng nữa.
- **Site-scope: được enforce riêng, độc lập với quyền chung** — tài khoản site-scoped xem site
  ngoài phạm vi được gán sẽ bị 403 dù có quyền `sites:read` tổng quát.

---

## A. Test trên Web Admin

### 1. Xem chi tiết công trình — happy path
- Vào 1 site bất kỳ, xem trang chi tiết.
- **Kỳ vọng:** hiển thị đúng thông tin cơ bản (tên, mã, địa chỉ, chính sách check-in), bản đồ với
  tâm đúng tọa độ.

### 2. Xem geofence active trên trang chi tiết
- Với 1 site đã có geofence active, xem lại card geofence trên trang chi tiết.
- **Kỳ vọng:** hiển thị đúng geofence đang active (polygon/buffer trên bản đồ). Với site chưa có
  geofence, card hiện trạng thái "chưa có" rõ ràng, không lỗi.

### 3. Xem danh sách shift active của site
- Với 1 site đã có 1-2 shift template active, xem card ca làm việc.
- **Kỳ vọng:** hiển thị đúng DANH SÁCH các shift đang active (không chỉ 1 cái) — xác nhận đúng cách
  hiểu mới, không phải "1 shift mặc định".

### 4. Xem số lượng phân công active
- Với 1 site đã có nhân viên được phân công, xem số đếm trên trang chi tiết.
- **Kỳ vọng:** số đếm khớp đúng số assignment active thật của site đó (đối chiếu qua tab phân công).

### 5. ✅ Xác nhận supervisor hiện ngay ở card chính — ĐÃ VÁ, test qua UI thật
- Với 1 site đã gán supervisor (qua #52 case 5), xem lại card "Thông tin công trình" trên trang
  chi tiết.
- **Kết quả thật (Playwright, giao diện thật):** dòng "Người phụ trách" hiện đúng tag tên nhân
  viên đã gán ("Test Terminate") ngay tại card chính — không cần mở tab riêng nữa.

### 6. Xác nhận vẫn xem được supervisor qua tab phân công (đường dự phòng)
- Từ case 5, chuyển sang tab "Nhân sự phân công", tìm người có tag "Giám sát".
- **Kỳ vọng:** vẫn thấy đúng người đã gán ở đây — cách xem cũ vẫn hoạt động song song với cách xem
  mới nhanh hơn ở case 5, không có gì bị thay thế hay mất đi.

### 7. ✅ Xác nhận chặn xem site ngoài phạm vi site-scope
- Đăng nhập tài khoản site-scoped, thử xem chi tiết 1 site KHÔNG thuộc phạm vi được gán (qua URL
  trực tiếp nếu cần).
- **Kỳ vọng:** bị chặn 403 với thông báo rõ ràng (VD: "Công trình nằm ngoài phạm vi site được
  giao"), không rò rỉ thông tin site đó.

---

## Ghi chú
Case 5 là trọng tâm — gap "không hiện supervisor" đã vá, test live qua UI thật (tạo site → gán
supervisor qua Assignment → xem trang chi tiết → dòng "Người phụ trách" hiện đúng ngay lập tức).
Case 7 xác nhận bảo mật site-scope hoạt động đúng ở tầng chi tiết, không chỉ ở danh sách (#53).
Case 1-4 xác nhận lại các phần AC đã đúng nhưng khác cách diễn đạt so với audit gốc (danh sách
thay vì đơn lẻ, số đếm thay vì mảng).
