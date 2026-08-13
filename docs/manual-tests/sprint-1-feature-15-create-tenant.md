# Kịch bản test thủ công — #15 Tạo tenant mới

**Nền tảng: Backend, Web Admin** (không có trên Mobile App).

ℹ️ Audit gốc (07-22) ghi "chỉ PLATFORM_ADMIN tạo được; không tự gán role admin cho người tạo;
không ghi audit" — đã xác nhận lại qua code, **cả 3 điểm này đã được sửa** từ trước (không rõ
đợt nào), vẫn còn nguyên trong bản mới nhất:
- Người dùng thường (không phải Platform Admin) cũng tự tạo được tenant cho chính mình (self-serve,
  tenant khởi tạo ở trạng thái `trial`) — chỉ khi **gán tenant cho người khác** mới cần quyền
  Platform Admin/Staff (`tenants:create` + truyền `ownerEmail`/`ownerUserId`).
- Người tạo được tự động gán role `TENANT_ADMIN` ngay khi tạo tenant.
- Có ghi audit `tenant_created`.

Không cần test lại 3 điểm này như "gap" — chỉ cần xác nhận qua UI thật là chạy đúng.

---

## A. Test trên Web Admin

### 1. Tự tạo tenant cho chính mình (self-serve) — happy path
- Đăng nhập bằng 1 tài khoản **chưa thuộc tenant nào** (đăng ký mới theo kịch bản #6), vào màn
  "Tạo công ty"/"Thiết lập không gian làm việc".
- Điền tên công ty, slug (để trống nếu UI tự sinh từ tên), timezone.
- **Kỳ vọng:** tạo thành công, tự động vào tenant vừa tạo với vai trò Admin (Tenant Admin), tenant
  ở trạng thái `trial`.

### 2. Slug trùng
- Thử tạo tenant với slug đã tồn tại (VD: slug của 1 tenant seed sẵn).
- **Kỳ vọng:** lỗi rõ ràng "Slug đã được sử dụng", không tạo tenant mới.

### 3. Slug tự sinh từ tên nếu để trống (nếu UI hỗ trợ)
- Nhập tên công ty có dấu/khoảng trắng (VD: "Công Ty Test ABC"), để trống ô slug.
- **Kỳ vọng:** UI tự gợi ý/tạo slug hợp lệ (không dấu, không khoảng trắng, chữ thường, nối gạch
  ngang) — nếu UI không có tính năng này và bắt buộc nhập slug tay, không phải bug, chỉ ghi nhận.

### 4. Platform Admin tạo tenant thay cho người khác
- Đăng nhập Platform Admin, vào màn quản trị Platform → "Tạo tenant mới", chọn gán cho 1 email
  người dùng **đã có tài khoản FAMS sẵn** (không phải tạo tài khoản mới).
- **Kỳ vọng:** tạo thành công, tenant ở trạng thái `active` (khác `trial` của self-serve), owner
  là đúng người được chỉ định, người đó có role `TENANT_ADMIN` ở tenant mới.

### 5. Platform Admin gán owner là email chưa có tài khoản
- Thử tạo tenant, gán `ownerEmail` là 1 email chưa từng đăng ký FAMS.
- **Kỳ vọng:** lỗi rõ ràng "Không tìm thấy tài khoản với email này — chủ sở hữu phải đã đăng ký
  trước" (không tự tạo tài khoản mới ngầm).

### 6. User thường KHÔNG được gán owner cho người khác
- Đăng nhập user thường (không phải Platform Admin), thử gọi API tạo tenant kèm `ownerEmail` (nếu
  UI không có ô này thì test bằng curl):
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/tenants \
    -H "Authorization: Bearer <token user thường>" -H "Content-Type: application/json" \
    -d '{"name":"Test Co","slug":"test-co-xyz","ownerEmail":"admin@fams.com"}' -w "\nHTTP:%{http_code}\n"
  ```
- **Kỳ vọng:** 403, lỗi "Only Platform Admins/Staff... may assign an owner at creation" (hoặc bản
  dịch tiếng Việt).

### 7. Xác nhận audit log
- Sau case 1, gọi `GET /audit-logs?tenantId=<id vừa tạo>&action=tenant_created` với token phù hợp.
- **Kỳ vọng:** có đúng 1 bản ghi, đủ dữ liệu snapshot tenant vừa tạo.

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright — cần bạn xác nhận UI thật có form phù hợp cho
cả 2 luồng (self-serve và Platform Admin provisioning) hay chỉ có 1 luồng hiện có trên giao diện.
