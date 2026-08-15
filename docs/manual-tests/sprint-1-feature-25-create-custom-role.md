# Kịch bản test thủ công — #25 Tạo role tùy chỉnh

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi "không ghi audit" — đã xác nhận qua code, **đã sửa** (`recordRoleAudit`,
action `role_created` — kiểm tra tên action thật khi test case 5). Ghi chú "không có cờ
is_editable/is_deletable riêng" vẫn đúng về mặt kỹ thuật (chỉ dùng `isSystem`), nhưng hành vi thực
tế tương đương: mọi role không phải hệ thống (`isSystem=false`) đều sửa/xóa được — không có role
tùy chỉnh nào bị khóa riêng lẻ. Không tính là gap cần test riêng.

---

## A. Test trên Web Admin

### 1. Tạo role tùy chỉnh — happy path
- Đăng nhập Company Admin có quyền `roles:create` (hoặc chính chủ tenant), vào màn Role → Tạo mới.
- Nhập tên (VD: `Kế toán công trình`), mô tả, chọn 1 vài permission.
- **Kỳ vọng:** tạo thành công, hiện trong danh sách, `isSystem=false`.

### 2. Trùng tên trong cùng tenant
- Tạo lại role trùng tên vừa tạo ở case 1 (cùng tenant).
- **Kỳ vọng:** lỗi rõ ràng "Tên role đã tồn tại trong công ty này".

### 3. Trùng tên khác tenant — vẫn tạo được
- Nếu có quyền truy cập tenant khác (Platform Admin), tạo role cùng tên ở tenant khác.
- **Kỳ vọng:** tạo thành công — tên role chỉ cần unique trong phạm vi 1 tenant, không unique toàn
  hệ thống.

### 4. User thường không có quyền `roles:create` — bị chặn
- Đăng nhập 1 tài khoản không có quyền `roles:create` trong tenant, thử tạo role.
- **Kỳ vọng:** 403, không tạo được (nút Tạo có thể bị ẩn luôn trên UI nếu đã kiểm tra quyền phía
  client — xác nhận UI có ẩn đúng không).

### 5. Kiểm tra audit log
- Sau case 1, gọi `GET /audit-logs?tenantId=<id>&entityType=Role&action=role_created`.
- **Kỳ vọng:** có bản ghi, `newValue` chứa đúng tên + danh sách permission vừa chọn.

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright.
