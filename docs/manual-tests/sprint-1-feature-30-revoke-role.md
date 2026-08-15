# Kịch bản test thủ công — #30 Thu hồi role

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: soft-delete qua `deletedAt` (không phải `is_active`), không lưu
`revoked_by`, không có safeguard mất admin cuối, không ghi audit. Đã xác nhận qua code hiện tại:
- **Soft-delete: đúng như hiện trạng** — `UserRoleService.revokeRole` set `deletedAt`, không dùng
  cột `is_active` (kiến trúc nhất quán với toàn bộ hệ thống, không phải gap).
- **Audit: đã có** — ghi `role_revoked` với đầy đủ before-state.
- **`revoked_by` riêng: không có cột riêng**, nhưng `actor` của audit log `role_revoked` chính là
  người thu hồi — thông tin tương đương, chỉ khác chỗ lưu (audit_logs thay vì user_roles). Không
  coi là gap.
- **Safeguard mất admin cuối cùng của tenant: VẪN CHƯA CÓ** — đây là gap thật, quan trọng nhất
  cần xác nhận ở case 4 dưới đây. Nếu xác nhận đúng là chưa chặn, đây là ứng viên ưu tiên sửa tiếp
  theo (rủi ro tự khóa cả công ty ngoài hệ thống nếu Company Admin lỡ tay thu hồi role admin cuối
  cùng của chính họ hoặc của đồng nghiệp).

---

## A. Test trên Web Admin

### 1. Thu hồi role — happy path
- Đăng nhập Company Admin, vào màn Vai trò & Phân quyền → xem người giữ 1 role → Thu hồi (hoặc từ
  màn Thành viên công ty, click tag role → Thu hồi — tính năng mới thêm 2026-08-14).
- Chọn 1 người đang giữ role không phải chủ sở hữu và không phải chính mình.
- **Kỳ vọng:** thu hồi thành công ngay, người đó biến mất khỏi danh sách người giữ role/khỏi bảng
  Thành viên công ty (nếu đó là role duy nhất của họ) mà không cần tải lại trang, audit
  `role_revoked` được ghi.

### 2. Thu hồi role không tồn tại / đã bị thu hồi trước đó
- Gọi lại thao tác thu hồi đúng `userRoleId` vừa thu hồi ở case 1 lần thứ 2 (VD: gọi thẳng API
  hoặc bấm nhanh 2 lần liên tiếp trước khi UI kịp cập nhật).
- **Kỳ vọng:** lỗi 404 "Active role assignment not found" — không crash, không xóa nhầm dữ liệu
  khác.

### 3. Thu hồi role của người khác tenant — chặn xuyên tenant
- Lấy 1 `userRoleId` thuộc tenant khác (không phải tenant đang đăng nhập), thử thu hồi.
- **Kỳ vọng:** bị chặn 403 (không đủ quyền `roles:update` trong tenant đó).

### 4. ⚠️ Thu hồi role admin cuối cùng của tenant — xác nhận gap "mất admin"
- Chọn 1 tenant test chỉ còn ĐÚNG 1 người giữ `TENANT_ADMIN` (không phải owner, vì owner có kênh
  ownership riêng không qua role) — hoặc tạo tình huống này bằng cách thu hồi bớt cho tới khi chỉ
  còn 1 người.
- Thu hồi role `TENANT_ADMIN` của người cuối cùng đó.
- **Kỳ vọng theo code hiện tại (chưa có safeguard):** thao tác **thành công** — không có gì chặn
  lại, kể cả khi điều này khiến tenant không còn ai giữ quyền quản trị nào (chủ sở hữu vẫn có thể
  vào lại vì ownership tách biệt role, nhưng nếu tenant không có owner active và role cuối cùng
  cũng mất thì không còn ai quản trị được). Nếu thực tế test ra khác (bị chặn), ghi lại là tin
  tốt bất ngờ, không phải fail. Nếu đúng như dự đoán (không chặn), xác nhận đây là gap thật cần
  đưa vào backlog sửa tiếp — không tính feature #30 fail vì AC gốc không yêu cầu safeguard này là
  điều kiện tối thiểu.

### 5. Thu hồi role của chính mình
- Company Admin tự thu hồi role `TENANT_ADMIN` của chính tài khoản đang đăng nhập.
- **Kỳ vọng:** xác nhận hành vi thực tế (có bị chặn tự thu hồi hay không) — ghi lại đúng những gì
  quan sát được, đây cũng là 1 nhánh của gap "mất admin" ở case 4.

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright. Case 4-5 là trọng tâm — không phải để tìm bug
mới mà để **xác nhận và ghi nhận chính thức** 1 gap đã biết trước, quyết định có ưu tiên sửa ngay
hay để lại backlog.
