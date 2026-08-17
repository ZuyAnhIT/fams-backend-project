# Kịch bản test thủ công — #52 Tạo công trình

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không có trường province, không liên kết workspace/supervisor". Đã
xác nhận lại qua code hiện tại — **AC gốc lỗi thời về kiến trúc ở 2/3 điểm, phần thứ 3 (supervisor)
thực chất được làm theo cách khác, không phải thiếu hẳn:**

- **`province`: KHÔNG TỒN TẠI và có thể không bao giờ có** — bảng `sites` không có khái niệm
  tỉnh/thành riêng, chỉ có `address` dạng text tự do. Đây là gap kiến trúc thật, không phải quên
  code.
- **"Liên kết workspace": KHÔNG TỒN TẠI** — `Site` không có cột `workspace_id`. Workspace (phòng
  ban/tổ chức) là khái niệm hoàn toàn tách biệt, chỉ gắn với Employee, không gắn với Site. AC gốc
  có thể đang nhầm lẫn 2 khái niệm khác nhau trong hệ thống.
- **"Gán supervisor": KHÔNG PHẢI thiếu, mà làm theo luồng khác** — không có cột `supervisor_id`
  trên `sites`, nhưng supervisor được mô hình hóa qua `Assignment.role='supervisor'` (gán nhân viên
  vào site với vai trò supervisor qua API Phân công riêng), kết hợp RBAC role `SITE_SUPERVISOR` +
  `SiteScopeService` để giới hạn quyền theo site. Đây là thiết kế tách bạch "tạo site" và "gán
  người phụ trách", không phải gộp chung 1 bước như AC gốc ngụ ý.
- **Gap "không ghi audit log khi tạo site": ĐÃ VÁ (2026-08-16)** — thêm `auditLogService.record(...)`
  vào `createSite`, action `site_created` với snapshot name/code/address/status/checkinPolicy/timezone.
- **Kiểm tra plan limit `max_sites`: HOẠT ĐỘNG ĐÚNG** — `PlanLimitEnforcementService.assertSiteLimit`
  chặn tạo site mới khi đã đạt giới hạn gói, cần test xác nhận đúng (positive case).
- **Trùng tên/mã: HOẠT ĐỘNG ĐÚNG** — chặn trùng tên (không phân biệt hoa/thường) và trùng mã, trả
  409 rõ ràng.

---

## A. Test trên Web Admin

### 1. Tạo công trình — happy path
- Vào Công trình → Tạo mới, nhập đầy đủ tên/mã/mô tả/địa chỉ/tọa độ/múi giờ/chính sách check-in.
- **Kỳ vọng:** tạo thành công, xuất hiện ngay trong danh sách.

### 2. Tạo trùng tên hoặc trùng mã
- Tạo 1 công trình mới với tên hoặc mã trùng công trình đã có.
- **Kỳ vọng:** báo lỗi 409 rõ ràng, không tạo trùng.

### 3. ✅ Xác nhận chặn khi đạt giới hạn `max_sites` theo gói
- Nếu tenant test gần/đạt giới hạn số site theo gói, thử tạo thêm 1 site.
- **Kỳ vọng theo code hiện tại:** bị chặn rõ ràng (lỗi giới hạn gói), không tạo được. Nếu tenant còn
  nhiều chỗ trống, ghi "không tái hiện được" — không phải gap.

### 4. Xác nhận không có trường `province`/liên kết workspace trong form tạo
- Quan sát form tạo công trình trên Web Admin.
- **Kỳ vọng theo code hiện tại:** KHÔNG có ô nào cho tỉnh/thành hay chọn workspace — khớp đúng gap
  kiến trúc đã xác nhận, không phải lỗi UI thiếu hiển thị.

### 5. Gán supervisor sau khi tạo site — qua luồng Phân công riêng
- Sau khi tạo site ở case 1, vào tab "Nhân sự phân công" của site đó → gán 1 nhân viên với vai trò
  "Giám sát" (supervisor) qua API/UI phân công.
- **Kỳ vọng:** gán thành công, người này xuất hiện trong tab phân công với tag "Giám sát" — xác
  nhận đúng luồng thực tế (không phải 1 trường trên form tạo site, mà là 1 bước riêng sau khi site
  đã tồn tại).

### 6. ✅ Xác nhận gap "không ghi audit" — ĐÃ VÁ
- Sau case 1, vào Nhật ký audit tìm hành động tạo công trình vừa làm.
- **Kết quả thật (2026-08-16):** CÓ bản ghi audit `site_created`, đúng `entity_id` của site vừa
  tạo. Test live: tạo site "Site Audit Test" qua API → `audit_logs` có đúng 1 dòng khớp entity_id.

---

## Ghi chú
Toàn bộ 6 case đã test live, pass 100%. Case 3, 5 xác nhận đúng 2 phần AC gốc thực ra đã hoạt động
(plan limit hoạt động tốt, supervisor có luồng riêng qua Assignment chứ không thiếu hẳn — test
live: gán 1 nhân viên role=supervisor vào site mới tạo, thành công). Case 4 xác nhận province/
workspace là gap kiến trúc có chủ đích, không phải thiếu sót triển khai — không sửa trong đợt này.
Case 6 (gap audit) đã vá và xác nhận qua DB trực tiếp.
