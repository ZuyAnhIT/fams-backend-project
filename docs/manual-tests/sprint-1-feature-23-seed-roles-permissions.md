# Kịch bản test thủ công — #23 Seed role và permission hệ thống

**Nền tảng: chỉ Backend** (chạy tự động lúc khởi động/migration, không có màn hình UI riêng —
đây là dữ liệu nền cho toàn bộ tính năng RBAC #24 trở đi). Test bằng cách kiểm tra dữ liệu, không
cần click UI.

---

## A. Kiểm tra qua DB/API

### 1. Role hệ thống đã được seed đủ
```bash
docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
  "SELECT name, tenant_id IS NULL AS is_system_wide FROM roles WHERE is_system = true ORDER BY name;"
```
- **Kỳ vọng:** thấy đủ các role: `PLATFORM_ADMIN`, `TENANT_ADMIN` (hoặc `COMPANY_ADMIN` tùy tên
  thật trong code), `HR`, `SITE_SUPERVISOR`, `FIELD_EMPLOYEE` (đối chiếu đúng tên thật đang dùng
  trong code hiện tại, không nhất thiết khớp 100% tên trong Acceptance Criteria cũ).

### 2. Permission được seed theo resource/action
```bash
docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
  "SELECT count(*) FROM permissions;"
```
- **Kỳ vọng:** số lượng permission > 0, đủ lớn (hàng chục tới hàng trăm, tùy độ chi tiết module).

### 3. Idempotent — chạy lại migration không tạo trùng
- Restart lại container backend (migration Flyway chạy lại kiểm tra, không re-apply migration đã
  chạy do Flyway tự theo dõi version — nhưng nếu seed nằm ở code khởi động thay vì migration SQL,
  kiểm tra riêng):
  ```bash
  docker restart fams-api
  ```
  đợi khởi động xong, chạy lại query case 1/2.
- **Kỳ vọng:** số lượng role/permission **không đổi** sau khi restart — xác nhận không tạo trùng
  mỗi lần app khởi động lại.

### 4. Role permission mapping đúng
```bash
docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
  "SELECT r.name, count(rp.permission_id) FROM roles r LEFT JOIN role_permissions rp ON rp.role_id=r.id WHERE r.is_system=true GROUP BY r.name ORDER BY r.name;"
```
- **Kỳ vọng:** `PLATFORM_ADMIN`/`TENANT_ADMIN` có nhiều permission nhất, `FIELD_EMPLOYEE` có ít
  permission nhất (đúng thứ bậc quyền hạn) — không có role nào có 0 permission (trừ khi chủ đích).

---

## Ghi chú
Tính năng này không có UI để test tay theo nghĩa thông thường — "test" ở đây là xác nhận dữ liệu
nền đúng và ổn định qua các lần khởi động, làm nền tảng để test tiếp #24 (danh sách role) trở đi.
