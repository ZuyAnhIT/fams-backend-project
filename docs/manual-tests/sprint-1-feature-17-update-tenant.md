# Kịch bản test thủ công — #17 Cập nhật thông tin tenant

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi "không ghi audit" — đã xác nhận lại qua code, **gap này đã được sửa**
(`recordTenantAudit(..., "tenant_updated", ...)`), không cần test lại như 1 gap, chỉ cần xác nhận
qua UI thật ở case 5.

⚠️ **Điểm quan trọng cần biết trước khi test:** chỉ **chính chủ sở hữu (owner)** của tenant mới
sửa được thông tin — kể cả Platform Admin/Staff **cũng không** sửa được hồ sơ tenant của người
khác (chỉ có quyền xem). Case 2 xác nhận đúng giới hạn quyền này.

---

## A. Test trên Web Admin

### 1. Cập nhật thông tin — happy path
- Đăng nhập đúng chủ sở hữu tenant (owner), vào Cài đặt công ty, đổi tên hiển thị/logo/địa chỉ/
  timezone.
- **Kỳ vọng:** lưu thành công, hiển thị đúng ngay không cần reload.

### 2. Platform Admin thử sửa tenant của người khác — phải bị từ chối
- Đăng nhập Platform Admin, vào chi tiết 1 tenant **không phải do mình sở hữu**, thử sửa thông
  tin (tên, logo...).
- **Kỳ vọng:** hoặc UI không hiện nút "Sửa" (chỉ có nút xem), hoặc nếu cố gọi API thì bị 403 với
  message "Only this tenant's owner may update its profile" — xác nhận đúng UI không cho thao
  tác nhầm (không phải chỉ dựa vào lỗi 403 phía sau).

### 3. Domain trùng với tenant khác
- Thử đổi domain sang 1 giá trị đã thuộc tenant khác.
- **Kỳ vọng:** lỗi rõ ràng "Domain đã được đăng ký".

### 4. Đổi timezone ảnh hưởng tính ngày (kiểm tra kỹ)
- Đổi timezone tenant từ `UTC` sang `Asia/Ho_Chi_Minh` (hoặc ngược lại nếu đang là giờ VN).
- Sau khi đổi, kiểm tra 1 bản ghi chấm công/báo cáo mới tạo sau thời điểm đổi có tính đúng theo
  timezone MỚI không (VD: giờ check-in gần nửa đêm có bị lệch sang ngày khác không).
- **Kỳ vọng:** dữ liệu MỚI tính theo timezone mới; dữ liệu CŨ (trước khi đổi) không bị tính toán
  lại ngược — nếu bạn thấy dữ liệu cũ bị đổi theo, đây là bug thật, báo lại ngay (rất nhạy cảm vì
  ảnh hưởng lịch sử chấm công).

### 5. Kiểm tra audit log
- Sau case 1, gọi `GET /audit-logs?tenantId=<id>&action=tenant_updated`.
- **Kỳ vọng:** có bản ghi với diff đúng field vừa đổi (`before`/`after`).

---

## Ghi chú
Case 4 là case quan trọng nhất và tốn công nhất — chỉ cần làm nếu tenant test có sẵn vài bản ghi
chấm công để so sánh trước/sau; nếu không có dữ liệu sẵn, có thể bỏ qua case này và ghi chú lại để
làm sau khi tới các tính năng chấm công (Sprint 3).
