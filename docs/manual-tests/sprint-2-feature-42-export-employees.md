# Kịch bản test thủ công — #42 Export danh sách nhân viên

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi "ĐÃ XONG". Đã xác nhận lại qua code hiện tại:
- **Mask email/phone theo quyền: ĐÚNG** — `EmployeeExportService` tính `bypassMasking` giống hệt
  logic `MaskedSerializer` (dựa trên `employees:pii:read`/PLATFORM_ADMIN), áp `MaskingUtils.maskEmail
  /maskPhone` cho các dòng khi không có quyền. Filter (search/status/department) và giới hạn theo
  site-scope đều được tôn trọng trong query.
- **Gap "thiếu cột nationalId": ĐÃ VÁ (2026-08-16)** — thêm cột `nationalId` vào `HEADERS` và vòng
  ghi dữ liệu của `EmployeeExportService`, mask bằng literal `"***"` khi không có quyền PII (khớp
  đúng cách `Masked.MaskType.DEFAULT` mask nationalId trong JSON API — không phải format che một
  phần như email/phone). Test live: PATCH nationalId cho 1 nhân viên → export → mở file `.xlsx` →
  cột `nationalId` xuất hiện đúng vị trí (sau cột `phone`), giá trị đúng cho caller có quyền PII.

---

## A. Test trên Web Admin

### 1. Export không filter — happy path
- Vào danh sách Nhân viên, không áp filter nào, bấm "Xuất Excel".
- **Kỳ vọng:** tải về đúng 1 file `.xlsx`, số dòng khớp tổng số nhân viên đang hiển thị (kể cả
  các trang khác, không chỉ trang hiện tại — export phải lấy toàn bộ kết quả theo filter, không bị
  giới hạn bởi phân trang UI).

### 2. Export có filter (search/status/department)
- Áp 1-2 filter trên UI (VD: trạng thái "Đã nghỉ việc", hoặc tìm theo tên), sau đó bấm "Xuất Excel".
- **Kỳ vọng:** file tải về chỉ chứa đúng các nhân viên khớp filter đang áp dụng trên UI, không phải
  toàn bộ danh sách.

### 3. ✅ Xác nhận mask PII theo quyền — tài khoản CÓ quyền `employees:pii:read`
- Đăng nhập tài khoản Company Admin/Owner (có quyền xem PII đầy đủ), export.
- **Kỳ vọng theo code hiện tại:** cột email/phone trong file hiển thị đầy đủ, không bị che.

### 4. ✅ Xác nhận mask PII theo quyền — tài khoản KHÔNG có quyền `employees:pii:read`
- Đăng nhập tài khoản không có quyền PII (VD: role tùy chỉnh không gán quyền này), export.
- **Kỳ vọng theo code hiện tại:** cột email/phone trong file bị che (dạng `ab***@...`/`09***...`,
  giống cách hiển thị trên UI danh sách), không lộ dữ liệu thật.

### 5. ✅ Xác nhận cột `nationalId` trong file export — ĐÃ VÁ
- Chuẩn bị ít nhất 1 nhân viên đã có `nationalId` (nhập qua form, xem hướng dẫn ở kịch bản #39).
- Export danh sách có chứa nhân viên đó.
- **Kết quả thật (2026-08-16):** file Excel CÓ cột `nationalId` (nằm sau `phone`), giá trị hiển thị
  đúng với tài khoản có quyền `employees:pii:read`.

### 6. Export khi danh sách rỗng (filter không khớp ai)
- Áp filter chắc chắn không khớp nhân viên nào (VD: tìm tên ngẫu nhiên không tồn tại).
- **Kỳ vọng:** vẫn tải được file (không lỗi 500), file chỉ có header, không có dòng dữ liệu.

---

## Ghi chú
Case 1-4 rủi ro fail thấp (logic mask/filter đã ổn định qua audit trước). Case 5 đã xác nhận vá
xong qua test live — cột nationalId giờ có mặt và mask đúng theo quyền.
