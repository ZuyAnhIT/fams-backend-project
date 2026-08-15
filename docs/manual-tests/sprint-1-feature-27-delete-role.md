# Kịch bản test thủ công — #27 Xóa hoặc vô hiệu hóa role

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi "chặn xóa nhưng không có fallback deactivate; không ghi audit" — đã xác
nhận qua code: **audit đã có** (action `role_deleted`). **Fallback deactivate không tự động** —
khi xóa bị chặn (role đang có người giữ), hệ thống chỉ báo lỗi, KHÔNG tự chuyển sang deactivate —
nhưng Company Admin vẫn có thể tự làm việc đó thủ công qua màn Sửa role (kịch bản #26 case 5,
`isActive=false`). Case 3 dưới đây xác nhận đúng hành vi thật này (không phải gap chặn, chỉ là
không tự động).

---

## A. Test trên Web Admin

### 1. Xóa role tùy chỉnh chưa gán cho ai
- Tạo 1 role test mới (chưa gán cho user nào), xóa.
- **Kỳ vọng:** xóa thành công, biến mất khỏi danh sách.

### 2. Không xóa được role hệ thống
- Thử xóa role hệ thống (VD: `EMPLOYEE`).
- **Kỳ vọng:** bị chặn — UI không hiện nút Xóa cho role hệ thống, hoặc lỗi rõ ràng "System roles
  cannot be deleted" nếu cố gọi API.

### 3. Không xóa được role đang có người giữ — chỉ báo lỗi, không tự deactivate
- Với 1 role tùy chỉnh đang gán cho ít nhất 1 user, thử xóa.
- **Kỳ vọng:** bị từ chối, thông báo rõ "Role vẫn đang được gán cho người dùng, vui lòng gỡ hết
  trước khi xóa" (hoặc tương tự). Role **không** tự động chuyển sang trạng thái tắt — vẫn active
  bình thường sau khi xóa thất bại. Nếu bạn muốn "vô hiệu hóa" thay vì xóa, phải tự vào màn Sửa
  role và tắt `isActive` thủ công (kịch bản #26 case 5) — xác nhận UI có gợi ý rõ hướng xử lý này
  không (VD: nút "Vô hiệu hóa thay vì xóa" ngay trong thông báo lỗi) — nếu chưa có, ghi nhận là
  điểm UX nên cải thiện, không phải bug chặn.

### 4. Gỡ hết user rồi xóa lại — thành công
- Gỡ role khỏi user đang giữ nó (ở case 3), thử xóa lại.
- **Kỳ vọng:** xóa thành công.

### 5. Kiểm tra audit log
- Sau case 1, gọi `GET /audit-logs?tenantId=<id>&entityType=Role&action=role_deleted`.
- **Kỳ vọng:** có bản ghi, `oldValue` chứa snapshot role trước khi xóa.

---

## Ghi chú
Case 3 không phải "phải fail" — mục đích là xác nhận đúng hành vi thật (chặn cứng, không tự động
deactivate) và đánh giá xem UX hiện tại có đủ rõ ràng hướng dẫn người dùng xử lý tiếp hay không.
