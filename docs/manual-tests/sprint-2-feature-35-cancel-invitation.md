# Kịch bản test thủ công — #35 Hủy lời mời

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi ✅ ĐÃ XONG, chỉ thiếu ghi audit. Đã xác nhận lại qua code hiện tại: hành
vi chính đúng như audit — `cancelInvitation` set status "cancelled", chỉ cho phép hủy khi đang
"pending" (422 nếu không), không cho hủy lời mời đã chấp nhận. Gap "không ghi audit" **vẫn còn
thật** — `EmployeeInvitationService` không gọi `AuditLogService` ở bất kỳ method nào (gửi/hủy/chấp
nhận đều thiếu).

---

## A. Test trên Web Admin

### 1. Hủy lời mời đang pending — happy path
- Vào Nhân viên → tab "Lời mời đã gửi", tìm 1 lời mời đang "pending" (mời mới nếu chưa có), bấm
  Hủy.
- **Kỳ vọng:** modal xác nhận hiện ra; sau khi xác nhận, trạng thái chuyển "cancelled" ngay trong
  bảng, không cần tải lại trang.

### 2. Nút Hủy chỉ hiện với lời mời pending
- Quan sát các lời mời đã "accepted" hoặc "cancelled" (nếu có sẵn từ trước).
- **Kỳ vọng:** nút Hủy KHÔNG hiện/bị ẩn với các lời mời không còn "pending" — đúng logic
  `record.status === "pending"` trong code.

### 3. Hủy lời mời đã chấp nhận (qua API, vì UI đã ẩn nút)
- Nếu có 1 lời mời đã "accepted", thử gọi thẳng API hủy với `invitationId` đó.
- **Kỳ vọng:** lỗi 422 rõ ràng ("chỉ hủy được lời mời đang pending"), không đổi trạng thái đã
  accepted.

### 4. Người không có quyền `employees:create` không thấy nút Hủy
- Đăng nhập 1 tài khoản không có quyền `employees:create` (nếu có sẵn trong tenant test), vào tab
  "Lời mời đã gửi".
- **Kỳ vọng:** không thấy nút Hủy ở bất kỳ dòng nào (kể cả pending).

### 5. ⚠️ Xác nhận gap "không ghi audit" khi hủy
- Sau case 1, kiểm tra Nhật ký audit có bản ghi cho hành động hủy lời mời không.
- **Kỳ vọng theo code hiện tại:** không có. Ghi lại đúng thực tế quan sát được — nếu có nghĩa là
  đã được sửa từ lúc audit gốc.

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright. Case 5 là điểm duy nhất cần xác nhận lại (gap
nhỏ, không chặn khóa) — phần hành vi chính (case 1-4) đã rõ ràng qua code, rủi ro fail thấp.
