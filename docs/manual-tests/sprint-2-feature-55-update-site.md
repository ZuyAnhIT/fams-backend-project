# Kịch bản test thủ công — #55 Cập nhật công trình

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không có trường supervisor để cập nhật". Đã xác nhận lại qua code
hiện tại — **gap supervisor vẫn còn thật (đúng lý do kiến trúc như #52/#54, không sửa — xem ghi
chú). Nghiên cứu ban đầu còn nêu 1 gap "validate status yếu" — đã KIỂM TRA LẠI VÀ XÁC NHẬN LÀ SAI
(false positive), không phải gap thật. Gap "không ghi audit" là thật và đã vá.**

- **Supervisor: KHÔNG THỂ cập nhật qua API này (đúng, không phải gap cần sửa)** —
  `UpdateSiteRequest` không có field supervisor, vì bản thân `Site` không có cột này (xem #52).
  Đổi supervisor phải làm qua module Phân công (kết thúc assignment cũ, tạo assignment mới
  role=supervisor) — hoàn toàn tách biệt với API sửa thông tin site, đây là thiết kế có chủ đích.
- **❌ Gap "validate status yếu" — ĐÃ KIỂM TRA LẠI, KHÔNG PHẢI GAP THẬT.** Nghiên cứu ban đầu (qua
  agent) kết luận sai rằng `status` không có validate ở tầng service nên có thể gây lỗi 500. Khi
  test live trực tiếp bằng API với `status: "archived"`, hệ thống trả về **400 sạch** với thông báo
  rõ ràng ("Status must be 'active' or 'inactive'") — vì `UpdateSiteRequest.status` đã có sẵn
  `@Pattern(regexp = "^(active|inactive)$")` từ trước (bean validation ở tầng DTO/controller, agent
  nghiên cứu đã bỏ sót annotation này). Đã bổ sung thêm 1 lớp validate ở tầng service
  (`validateStatus()`) cho chắc chắn (phòng vệ kép), nhưng đây KHÔNG phải sửa 1 gap thật — hành vi
  trước và sau khi thêm đều giống nhau (400 sạch), chỉ khác là giờ có 2 lớp bảo vệ thay vì 1.
- **Gap "không ghi audit log khi cập nhật": ĐÃ VÁ (2026-08-16)** — thêm `auditLogService.record(...)`
  vào `updateSite`, action `site_updated` với before/after snapshot.
- **Trùng tên/mã khi đổi: HOẠT ĐỘNG ĐÚNG** — check lại uniqueness, loại trừ chính bản ghi đang sửa.
- **Tính năng tốt ngoài AC gốc: cờ `clearCode`** — cho phép chủ động xóa mã site (set về null) thay
  vì chỉ có thể đổi sang mã khác, hữu ích khi muốn bỏ hẳn mã cũ.

---

## A. Test trên Web Admin

### 1. Sửa thông tin công trình — happy path
- Vào 1 site, sửa tên/địa chỉ/mô tả.
- **Kỳ vọng:** lưu thành công, hiển thị đúng ngay trên danh sách/chi tiết.

### 2. Đổi trạng thái active ↔ inactive qua UI
- Đổi trạng thái 1 site từ "Hoạt động" sang "Ngừng hoạt động" và ngược lại.
- **Kỳ vọng:** lưu thành công (UI chỉ cho chọn 2 giá trị hợp lệ qua dropdown, không tự do nhập).

### 3. ✅ Xác nhận validate `status` — ĐÃ KIỂM TRA LẠI, không phải gap
- Gọi API `PUT .../sites/{id}` trực tiếp (Postman/curl, không qua UI) với `status: "archived"` hoặc
  chuỗi bất kỳ không phải `active`/`inactive`.
- **Kết quả thật (2026-08-16):** trả về **400 sạch** ngay từ tầng bean validation
  (`"status":"Status must be 'active' or 'inactive'"`) — hệ thống đã bảo vệ đúng từ trước, không
  cần sửa gì. Ghi chú này để tránh nghiên cứu sai lần sau lại tưởng đây là gap.

### 4. Đổi tên/mã trùng với site khác
- Sửa tên hoặc mã của 1 site thành giá trị đang dùng bởi site khác trong cùng tenant.
- **Kỳ vọng:** báo lỗi 409 rõ ràng, không lưu.

### 5. Xác nhận cờ `clearCode` hoạt động đúng
- Với 1 site đang có mã, dùng tính năng "xóa mã" (nếu UI có) hoặc gọi API với `clearCode: true`.
- **Kỳ vọng:** mã của site chuyển về rỗng/null, không báo lỗi.

### 6. ✅ Xác nhận gap "không ghi audit" — ĐÃ VÁ
- Sau case 1, vào Nhật ký audit tìm hành động sửa công trình vừa làm.
- **Kết quả thật (2026-08-16):** CÓ bản ghi audit `site_updated`, đúng entity_id. Test live: sửa
  mô tả site "Site Audit Test" qua API → `audit_logs` có đúng dòng `site_updated` mới.

### 7. Xác nhận không có trường supervisor để sửa trực tiếp
- Quan sát form sửa công trình trên Web Admin.
- **Kỳ vọng theo code hiện tại:** KHÔNG có ô nào cho supervisor — đổi supervisor phải qua tab
  "Nhân sự phân công" riêng (xem #52 case 5, #54 case 5-6), không phải qua form sửa site này. Đây
  là thiết kế có chủ đích, không sửa.

---

## Ghi chú
Case 6 (gap audit) đã vá và xác nhận qua DB trực tiếp. Case 3 ban đầu tưởng là gap nghiêm trọng
nhất nhưng sau khi test live lại hóa ra là false positive — bài học: luôn test live để xác nhận
trước khi kết luận gap, không chỉ dựa vào đọc code tĩnh (agent nghiên cứu đã bỏ sót annotation
`@Pattern` trên DTO). Case 1-2, 4-5, 7 rủi ro fail thấp, không cần sửa gì.
