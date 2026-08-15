# Kịch bản test thủ công — #24 Danh sách role

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi "thiếu số user đang gán" — đã xác nhận qua code, **gap này đã được sửa**
(`assignmentCount` được tính kèm theo mỗi role, batch-load hiệu quả — không phải N+1 query). Không
cần test lại như gap, chỉ cần xác nhận hiển thị đúng ở case 4.

---

## A. Test trên Web Admin

### 1. Xem danh sách role — happy path
- Đăng nhập Company Admin (hoặc Platform Admin), vào màn Vai trò/Role.
- **Kỳ vọng:** hiện đủ role hệ thống (TENANT_ADMIN, HR_MANAGER, SITE_SUPERVISOR, EMPLOYEE...) và
  role tùy chỉnh (nếu có), có phân trang.

### 2. Tìm kiếm theo tên
- Gõ 1 phần tên role vào ô tìm kiếm.
- **Kỳ vọng:** lọc đúng.

### 3. Lọc active/system
- Lọc chỉ hiện role hệ thống (`isSystem=true`), rồi lọc chỉ role tùy chỉnh.
- **Kỳ vọng:** kết quả đúng theo từng lượt lọc.

### 4. Số lượng user đang gán hiển thị đúng (xác nhận gap đã sửa)
- Với 1 role đã biết có bao nhiêu user đang gán (VD: `TENANT_ADMIN` của tenant test — thường chỉ
  1-2 người), xác nhận số hiển thị trong danh sách khớp thực tế:
  ```bash
  docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT count(*) FROM user_roles WHERE role_id=(SELECT id FROM roles WHERE name='TENANT_ADMIN' AND tenant_id IS NULL) AND deleted_at IS NULL AND tenant_id='<tenant id test>';"
  ```
- **Kỳ vọng:** số trong UI khớp đúng kết quả SQL trên.

### 5. Sắp xếp
- Đổi thứ tự sắp xếp (theo tên, hoặc priority nếu UI có).
- **Kỳ vọng:** thứ tự đổi đúng.

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright. Case 4 là trọng tâm để xác nhận gap cũ đã sửa
đúng, không phải case dễ fail — chỉ cần đối chiếu số liệu.
