# Kịch bản test thủ công — #39 Cập nhật nhân viên

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không có trường national_id; không ghi audit". Đã xác nhận lại
qua code hiện tại:
- **Ghi audit: ĐÃ SỬA** — `EmployeeService.updateEmployee` giờ ghi audit `employee_updated` đầy
  đủ before/after (email/SĐT được mask tự động trong bản ghi audit).
- **Trường `national_id`: ĐÃ VÁ (2026-08-16)** — thêm cột `national_id` (migration V95), field
  `nationalId` trong `CreateEmployeeRequest`/`UpdateEmployeeRequest`, mask trong response bằng
  `@Masked` (cùng cơ chế email/phone, dựa trên `employees:pii:read`), tự động mask trong audit
  log (key `nationalId` đã có sẵn trong `MaskingUtils.PII_KEYS` từ trước). Web Admin: đã thêm ô
  "Số CCCD/CMND (Tùy chọn)" vào cả `EmployeeFormModal.tsx` (modal tạo/sửa nhanh trên danh sách)
  và `EmployeeForm.tsx` (tab "Thông tin cá nhân" ở trang chi tiết).

**Test qua UI thật (Playwright, 2026-08-16):** đăng nhập tenant test → mở modal "Thêm hồ sơ" →
nhập CCCD `079099001234` cho nhân viên mới "NationalId UITest" → lưu thành công → vào trang chi
tiết nhân viên vừa tạo, tab "Thông tin cá nhân" → xác nhận ô "Số CCCD/CMND" hiển thị đúng giá trị
`079099001234` (đọc trực tiếp giá trị input, khớp 100% với giá trị đã nhập lúc tạo).

---

## A. Test trên Web Admin

### 1. Cập nhật thông tin nhân viên — happy path
- Vào chi tiết 1 nhân viên → Sửa (dùng chung `EmployeeFormModal` với luồng tạo, chế độ edit), đổi
  chức vụ + phòng ban.
- **Kỳ vọng:** lưu thành công, thông tin cập nhật hiện đúng ngay ở danh sách và trang chi tiết.

### 2. Cập nhật một phần (chỉ đổi 1 trường)
- Sửa chỉ riêng SĐT, để nguyên các trường khác.
- **Kỳ vọng:** chỉ SĐT đổi, các trường khác giữ nguyên giá trị cũ (đúng bán chất PATCH một phần).

### 3. Đổi mã nhân viên trùng với người khác
- Sửa mã nhân viên của 1 người thành đúng mã đang dùng bởi người khác trong cùng tenant.
- **Kỳ vọng:** lỗi 409 rõ ràng, không lưu.

### 4. Đổi phòng ban qua workspace — đồng bộ tên hiển thị
- Đổi trường Phòng ban (chọn từ danh sách workspace) sang 1 workspace khác.
- **Kỳ vọng:** cả `departmentId` và tên hiển thị `department` đều cập nhật đúng, đồng bộ (không
  bị lệch tên cũ/mới).

### 5. ⚠️ Xác nhận gap "không ghi audit" đã sửa
- Sau case 1, vào Nhật ký audit, tìm hành động cập nhật nhân viên vừa làm.
- **Kỳ vọng theo code hiện tại:** CÓ bản ghi audit `employee_updated` với before/after — xác nhận
  gap cũ đã sửa xong (khác audit gốc 07-22 báo thiếu).

### 6. ✅ Xác nhận gap "chưa có national_id" — ĐÃ VÁ, test qua UI thật
- Quan sát form sửa nhân viên, tìm trường CCCD/CMND/national_id.
- **Kết quả thật (2026-08-16):** CÓ trường "Số CCCD/CMND (Tùy chọn)" ở cả modal tạo nhanh và
  trang chi tiết. Nhập `079099001234` khi tạo → lưu → mở lại trang chi tiết → giá trị hiển thị
  đúng, không bị mất hay sai lệch qua round-trip API thật (không phải mock).

---

## Ghi chú
Case 1-4 rủi ro fail thấp (hành vi update cơ bản đã ổn định từ trước). Case 5-6 đã xác nhận: cả 2
gap đã biết đều được vá — audit từ trước, national_id vá cùng đợt 2026-08-16 kèm test UI thật.
