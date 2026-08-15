# Kịch bản test thủ công — #28 Xem permission theo nhóm

**Nền tảng: Backend, Web Admin.**

---

## A. Test trên Web Admin

### 1. Xem danh sách permission theo nhóm
- Vào màn Tạo/Sửa role (kịch bản #25/#26), xem phần chọn permission.
- **Kỳ vọng:** permission được nhóm theo nghiệp vụ (VD: "Nhân viên", "Chấm công", "Vi phạm"...),
  không phải danh sách phẳng khó tìm.

### 2. Mỗi permission có mô tả rõ ràng
- Xem qua vài permission bất kỳ.
- **Kỳ vọng:** có tên hiển thị (`display_name`) và mô tả (`description`) dễ hiểu bằng tiếng Việt,
  không chỉ mã kỹ thuật kiểu `employees:create`.

### 3. Chỉ hiển thị permission đang active
- Nếu có permission nào bị tắt (`is_active=false`) trong DB (kiểm tra nhanh):
  ```bash
  docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT count(*) FROM permissions WHERE is_active=false;"
  ```
  nếu > 0, xác nhận các permission đó **không** hiện trong danh sách chọn ở UI.
- **Kỳ vọng:** không hiển thị permission đã tắt.

### 4. Lọc theo scope (nếu UI có)
- Nếu form có ô lọc phạm vi (VD: chỉ permission cấp tenant, không phải cấp platform).
- **Kỳ vọng:** lọc đúng.

---

## Ghi chú
Tính năng đơn giản, không có gap đã biết — chủ yếu xác nhận trải nghiệm chọn permission khi tạo/
sửa role (kịch bản #25/#26) đủ rõ ràng, dễ dùng.
