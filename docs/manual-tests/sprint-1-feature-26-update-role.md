# Kịch bản test thủ công — #26 Sửa role và quyền

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi "không ghi audit ROLE_PERMISSION_UPDATE" — đã xác nhận qua code, **đã
sửa** (action thật là `role_updated`, không phải `ROLE_PERMISSION_UPDATE` như tên gốc — chỉ khác
tên action, không phải thiếu). Không cần test lại như gap.

---

## A. Test trên Web Admin

### 1. Sửa tên/mô tả role tùy chỉnh
- Sửa role đã tạo ở kịch bản #25, đổi tên + mô tả.
- **Kỳ vọng:** lưu thành công, mọi user đang giữ role này thấy tên mới ngay (không cần đăng nhập
  lại) nếu UI hiển thị tên role ở đâu đó cho họ.

### 2. Sửa danh sách permission
- Bỏ bớt 1 permission, thêm 1 permission khác.
- **Kỳ vọng:** lưu đúng. Đăng nhập bằng 1 tài khoản đang giữ role này (hoặc dùng tài khoản đã
  đăng nhập sẵn, thử thao tác permission vừa bị bỏ) — **quyền phải mất hiệu lực gần như ngay lập
  tức** (code có evict cache quyền của mọi user giữ role này khi sửa) — thử lại action đó, phải
  bị 403.

### 3. Không sửa được role hệ thống
- Thử sửa role `TENANT_ADMIN`/`HR_MANAGER` (role hệ thống, `isSystem=true`).
- **Kỳ vọng:** bị chặn — UI không nên hiện nút Sửa cho role hệ thống, hoặc nếu cố gọi API thì lỗi
  rõ ràng "System roles cannot be modified".

### 4. Đổi tên trùng role khác trong cùng tenant
- Đổi tên role đang sửa trùng với 1 role khác đã có sẵn trong cùng tenant.
- **Kỳ vọng:** lỗi 409 "Tên role đã tồn tại".

### 5. Deactivate role (thay vì xóa)
- Sửa role tùy chỉnh, tắt `isActive`.
- **Kỳ vọng:** role chuyển trạng thái không hoạt động, không hiện trong danh sách gán role cho
  user mới (nếu UI có lọc theo active), nhưng user đang giữ role đó vẫn giữ nguyên (không tự động
  bị gỡ).

### 6. Kiểm tra audit log
- Sau case 1/2, gọi `GET /audit-logs?tenantId=<id>&entityType=Role&action=role_updated`.
- **Kỳ vọng:** có bản ghi, diff before/after đúng field vừa đổi.

---

## Ghi chú
Case 2 là case quan trọng nhất — xác nhận cơ chế evict cache quyền hoạt động đúng, không chỉ lưu
DB đúng mà còn phải có tác dụng thật ngay lập tức.
