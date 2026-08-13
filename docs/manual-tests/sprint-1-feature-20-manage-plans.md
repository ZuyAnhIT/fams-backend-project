# Kịch bản test thủ công — #20 Quản lý gói dịch vụ

**Nền tảng: Backend, Web Admin** (Platform Admin only).

---

## A. Test trên Web Admin

### 1. Xem danh sách gói
- Đăng nhập Platform Admin, vào Quản trị Platform → Gói dịch vụ.
- **Kỳ vọng:** hiện danh sách gói seed sẵn (VD: trial, basic, pro, enterprise) kèm giá/trạng thái.

### 2. Tạo gói mới
- Tạo 1 gói test mới (tên, giá, mô tả).
- **Kỳ vọng:** tạo thành công, hiện trong danh sách.

### 3. Sửa gói
- Sửa giá/mô tả gói vừa tạo.
- **Kỳ vọng:** lưu đúng, hiển thị giá trị mới ngay.

### 4. Tắt gói đang có tenant sử dụng — phải bị chặn
- Thử tắt (deactivate) gói `trial` (gói mặc định, chắc chắn đang có tenant dùng vì mọi tenant mới
  tự tạo đều gán gói này — xem kịch bản #15).
- **Kỳ vọng:** bị từ chối, lỗi rõ ràng "không thể tắt gói đang có tenant sử dụng" — không tắt được
  cho tới khi không còn tenant nào dùng gói đó.

### 5. Tắt gói test không ai dùng
- Tắt gói test đã tạo ở case 2 (chưa gán cho tenant nào).
- **Kỳ vọng:** tắt thành công.

### 6. Kiểm tra audit log
- Sau case 2/3, gọi `GET /audit-logs?action=CREATE&entityType=Plan` (hoặc UPDATE tương ứng).
- **Kỳ vọng:** có bản ghi đầy đủ diff before/after (gap audit của #20 đã xác nhận sửa xong từ đợt
  trước, không phải test gap — chỉ xác nhận vẫn hoạt động đúng).

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright. Không có gap nghiệp vụ nào đã biết cho tính
năng này — chủ yếu xác nhận CRUD + ràng buộc "không xóa/tắt gói đang active" hoạt động đúng.
