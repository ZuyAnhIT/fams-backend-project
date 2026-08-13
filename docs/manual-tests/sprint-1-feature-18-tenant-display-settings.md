# Kịch bản test thủ công — #18 Cấu hình giao diện và định dạng

**Nền tảng: Backend, Web Admin.**

⚠️ **2 gap đã biết** (xác nhận lại khi viết kịch bản này — vẫn còn nguyên):
1. `TenantSettingsService` **không** gọi `auditLogService.record(...)` khi cập nhật — không có
   audit trail cho hành động này. Case 6 xác nhận lại.
2. `language`/`currency` (Acceptance Criteria gốc yêu cầu lưu ở `tenant_settings`) thực tế nằm ở
   **`Tenant` entity** (field `locale`, `currencyCode` — sửa qua API `PATCH /tenants/{id}`, tính
   năng #17, **không phải** `PATCH /tenants/{id}/settings` của #18 này). `TenantSettings` chỉ có
   `dateFormat`, `timeFormat`, `brandPrimaryColor/SecondaryColor/AccentColor`,
   `employeeCodePrefix/Padding`. Case 5 xác nhận lại — không phải bug UI nếu form "Cấu hình giao
   diện" của bạn thực chất đang gọi 2 API khác nhau cho 2 nhóm field này, chỉ cần xác nhận đúng
   hành vi.

---

## A. Test trên Web Admin

### 1. Cập nhật định dạng ngày/giờ — happy path
- Đăng nhập chủ sở hữu tenant, vào Cài đặt → Giao diện/Định dạng, đổi `dateFormat` (VD:
  `DD/MM/YYYY` → `MM/DD/YYYY`) và `timeFormat` (12h/24h).
- **Kỳ vọng:** lưu thành công, các màn hình khác trong app hiển thị ngày/giờ theo định dạng MỚI
  ngay (không cần đăng xuất/đăng nhập lại).

### 2. Đổi màu thương hiệu (brand color)
- Đổi `brandPrimaryColor`/`brandSecondaryColor`/`brandAccentColor` (color picker hoặc nhập mã hex).
- **Kỳ vọng:** lưu thành công, giao diện đổi màu theo (nếu tính năng theme-theo-tenant đã áp dụng
  màu này ở đâu đó — nếu chưa có nơi nào dùng màu này để hiển thị, ghi nhận là chưa áp dụng UI,
  không phải lỗi lưu dữ liệu).

### 3. Cấu hình mã nhân viên tự động (employee code prefix/padding)
- Đổi tiền tố mã nhân viên (VD: `NV`) và số chữ số đệm (VD: `4` → mã dạng `NV0001`).
- **Kỳ vọng:** lưu thành công; tạo thử 1 nhân viên mới (nếu đã tới phần test Sprint 2) để xác nhận
  mã sinh ra đúng định dạng — nếu chưa tới Sprint 2, chỉ cần xác nhận lưu đúng giá trị, chưa cần
  test tác dụng thật.

### 4. Nhập mã màu không hợp lệ
- Nhập `brandPrimaryColor` = `"khong-phai-hex"` (không phải mã hex hợp lệ).
- **Kỳ vọng:** validate từ chối rõ ràng, không lưu giá trị sai vào DB.

### 5. Kiểm tra vị trí lưu ngôn ngữ/tiền tệ (xác nhận gap #2 đã nêu ở đầu file)
- Tìm trong form "Cấu hình giao diện" xem có ô đổi Ngôn ngữ (`language`) / Đơn vị tiền tệ
  (`currency`) không.
- **Kỳ vọng:** nếu có, xác nhận UI đang gọi đúng API `PATCH /tenants/{id}` (tính năng #17), không
  phải `PATCH /tenants/{id}/settings` — mở DevTools/Network tab kiểm tra request thật khi bấm lưu.
  Báo lại chính xác request nào được gọi.

### 6. Kiểm tra audit log (xác nhận gap #1 đã nêu ở đầu file)
- Sau case 1, gọi `GET /audit-logs?tenantId=<id>&action=tenant_settings_updated` (hoặc action
  name tương ứng nếu khác).
- **Kỳ vọng theo code hiện tại:** rỗng — xác nhận đúng gap.

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright. Case 5 quan trọng nhất để làm rõ đúng hành vi
thật của UI trước khi quyết định có cần sửa lại kiến trúc field (gộp language/currency vào
tenant_settings cho khớp AC gốc) hay giữ nguyên và chỉ sửa lại tài liệu AC cho khớp thực tế.
