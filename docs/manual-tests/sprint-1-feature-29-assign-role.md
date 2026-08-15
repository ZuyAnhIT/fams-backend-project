# Kịch bản test thủ công — #29 Gán role cho user

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: scope theo site, `expires_at`, audit `ROLE_GRANT`. Đã xác nhận qua
code hiện tại:
- **Scope theo site: đã có** — `AssignRoleRequest.siteIds`, lưu vào `user_roles.site_ids`, dùng
  để giới hạn 1 role chỉ áp dụng ở 1/nhiều công trình cụ thể (VD: `SITE_SUPERVISOR` chỉ quản 2
  công trình, không phải toàn tenant).
- **Audit: đã có** — `UserRoleService.assignRole` ghi `role_assigned` qua `AuditLogService`, có
  đủ before/after.
- **`expires_at`: VẪN CHƯA CÓ** — gán role là vĩnh viễn cho tới khi bị thu hồi thủ công, không có
  cơ chế tự hết hạn. Đây là gap thật còn tồn tại — không chặn khóa tính năng (không nằm trong
  Acceptance Criteria tối thiểu là "kiểm tra permission + ghi audit"), nhưng cần note lại nếu sau
  này có yêu cầu "cấp quyền tạm thời N ngày".

Tính năng liên quan mới xây trong đợt audit RBAC (2026-08-14): **Gán role hàng loạt** (Bulk
Assign) và **Clone role** — không thuộc phạm vi #29 (vốn chỉ nói "gán 1 role cho 1 user"), nhưng
dùng chung `UserRoleService.assignRole` bên dưới nên nếu case đơn lẻ ở đây pass thì phần lõi của
2 tính năng kia coi như đã được xác nhận gián tiếp.

---

## A. Test trên Web Admin

### 1. Gán role cho 1 user — happy path, toàn tenant
- Đăng nhập Company Admin, vào màn Vai trò & Phân quyền → chọn 1 role → "Gán" (hoặc từ màn Thành
  viên công ty / Nhân viên → gán role cho 1 người).
- Chọn 1 nhân viên trong tenant chưa giữ role này, không chọn site (để trống = áp dụng toàn
  tenant).
- **Kỳ vọng:** gán thành công, người đó xuất hiện ngay trong danh sách người giữ role (không cần
  reload), audit `role_assigned` được ghi (kiểm qua Nhật ký audit hoặc DB).

### 2. Gán role có giới hạn theo site
- Gán 1 role khác (VD: `SITE_SUPERVISOR`) cho 1 nhân viên, lần này chọn 1-2 công trình cụ thể ở ô
  site.
- **Kỳ vọng:** gán thành công, xem lại chi tiết assignment thấy đúng site đã chọn (không phải áp
  dụng toàn tenant).

### 3. Gán lại role đã có (idempotent / reactivate)
- Với người ở case 1, thử gán lại đúng role đó lần nữa.
- **Kỳ vọng:** không tạo bản ghi trùng gây lỗi lạ — hoặc báo đã có, hoặc "reactivate" bản ghi cũ
  nếu trước đó đã bị thu hồi (xem code `assignRole`: có nhánh reactivate bản ghi cũ nếu tìm thấy).

### 4. Gán role vượt quyền — chặn leo thang đặc quyền
- Đăng nhập 1 tài khoản chỉ giữ đúng `roles:update` (không có gì khác), thử gán cho người khác 1
  role có quyền mà tài khoản này KHÔNG có (VD: role có `employees:delete` trong khi người gán
  không có quyền đó).
- **Kỳ vọng:** bị chặn 403 (tính năng chặn leo thang đặc quyền đã làm ở đợt audit RBAC trước, áp
  dụng cho tạo/sửa/clone role — xác nhận ở đây có áp dụng luôn cho **gán** role hay không; nếu
  không áp dụng, ghi lại làm gap mới cần bàn, không phải lỗi bug).

### 5. Gán role hệ thống cấp nền tảng (PLATFORM_ADMIN/PLATFORM_STAFF) qua API tenant-scoped
- Thử gán `PLATFORM_ADMIN` cho 1 người trong tenant qua đúng luồng UI ở case 1.
- **Kỳ vọng:** bị chặn (400 hoặc không thấy 2 role này trong danh sách để chọn) — đây là lỗ hổng
  leo thang đặc quyền đã vá ở đợt audit RBAC (xem
  `docs/reviews/backend/rbac-role-permission-audit-2026-08-13.md` mục 6), chỉ cần xác nhận lại
  vẫn đúng.

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright. Case 4-5 là trọng tâm bảo mật, case 1-3 là
luồng nghiệp vụ chính.
