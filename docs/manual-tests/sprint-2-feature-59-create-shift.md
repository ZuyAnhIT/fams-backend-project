# Kịch bản test thủ công — #59 Tạo ca làm việc

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "model giờ đơn giản (không JSON schedule), không có cờ default/
standard_hours". Đã xác nhận lại qua code — **gap "default" là thật, đã VÁ (2026-08-17)**; phần
"JSON schedule"/"standard_hours"/"code" là khác biệt kiến trúc có chủ đích so với AC gốc, không sửa
(cùng dạng với province/workspace ở epic Site).

- **`is_default`: ĐÃ VÁ** — thêm cột `is_default` (migration `V99__shift_is_default.sql`) + unique
  index từng phần `(site_id) WHERE is_default = true AND deleted_at IS NULL` — chỉ 1 ca mặc định
  mỗi site. Tạo/sửa ca đặt `isDefault=true` sẽ tự động bỏ mặc định ở ca khác cùng site (pattern
  giống hệt `SavedFilter.isDefault` đã có sẵn trong codebase). Field DTO đặt tên `defaultShift`
  (không đặt tên `isDefault` trực tiếp) để tránh xung đột Lombok/Jackson đã biết
  (`@JsonProperty("isDefault")` ghim đúng tên JSON).
- **`standard_hours`, `code`, "JSON schedule": XÁC NHẬN là khác biệt kiến trúc có chủ đích, không
  sửa** — model thật (start/end LocalTime + allowOvernight) đã đơn giản và đủ dùng, không cần JSON
  tự do; không có nhu cầu nghiệp vụ rõ ràng cho "mã ca" hay "giờ chuẩn" riêng biệt.
- **Gap mới phát hiện: KHÔNG ghi audit log khi tạo ca — ĐÃ VÁ** cùng đợt, action `shift_created`.
- **Cũng đã vá thêm (thuộc #61 nhưng liên quan trực tiếp form tạo ca): tìm kiếm theo tên** — xem
  chi tiết ở kịch bản #61.

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. ✅ Tạo ca làm việc — happy path, TEST LIVE qua UI thật
- Tạo "Ca Chieu Test UI" 13:00-21:00 qua UI (Playwright, ảnh `shift-04-create-modal.png`).
- **Kết quả thật:** tạo thành công, toast "Tạo mới ca làm việc thành công!", xuất hiện ngay trong
  bảng danh sách.

### 2. ✅ Đặt ca mặc định lúc tạo — ĐÃ VÁ, TEST LIVE
- Trong modal tạo ca, bật switch "Đặt làm ca mặc định" (ảnh `shift-05-create-modal-default-on.png`)
  → lưu.
- **Kết quả thật (ảnh `shift-06-after-create.png`):** ca mới hiện tag "Mặc định" màu vàng ngay cạnh
  tên; ca "Morning Shift" (đang là mặc định trước đó, test qua API) **tự động mất tag "Mặc định"**
  — xác nhận đúng cơ chế "chỉ 1 mặc định/site" hoạt động cả từ UI, không chỉ API.

### 3. Tạo ca qua đêm (overnight)
- Test qua script `test_create_shift.sh`: 22:00→06:00, `allowOvernight=true`.
- **Kết quả thật:** 201, không báo lỗi thời gian dù start>end.

### 4. Tạo ca qua đêm nhưng start=end
- Script test tương ứng.
- **Kết quả thật:** 400, đúng như validate `validateShiftTimes`.

### 5. Tạo ca trong ngày start>end, không đánh dấu qua đêm
- Script test.
- **Kết quả thật:** 400 rõ ràng.

### 6. Trùng tên ca trong cùng site / khác site được phép
- Script test 9: tên trùng khác site → 201 (cho phép).
- **Kết quả thật:** đúng như kỳ vọng, ràng buộc unique chỉ trong phạm vi 1 site.

### 7. ✅ Xác nhận KHÔNG có trường `code`/`standard_hours` — đúng kiến trúc, không phải thiếu UI
- Quan sát form tạo ca trên UI (ảnh `shift-04-create-modal.png`).
- **Kết quả thật:** không có ô nào cho mã ca/giờ chuẩn — khớp đúng quyết định kiến trúc.

### 8. ✅ Xác nhận gap "không ghi audit log khi tạo ca" — ĐÃ VÁ, TEST LIVE
- Sau case 1, kiểm tra `audit_logs` qua DB trực tiếp.
- **Kết quả thật (2026-08-17):** có đúng bản ghi `shift_created`, entity `Shift`, `new_value` chứa
  đầy đủ snapshot (name, startTime, endTime, isDefault, status...).

---

## Ghi chú
Toàn bộ 8 case đã test live: script tự động `test_create_shift.sh` 12/12 pass (không hồi quy sau
khi thêm cột `is_default`) + test tay qua API/DB + Playwright qua UI thật. Gap `is_default` (duy
nhất có ý nghĩa nghiệp vụ thật trong epic này) đã vá và xác nhận hoạt động đúng cả 2 chiều (tạo mới
tự bỏ mặc định cũ). Đã đóng — ĐÃ KHÓA.
