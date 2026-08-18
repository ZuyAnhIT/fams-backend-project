# Kịch bản test thủ công — #125 Báo cáo vi phạm theo kỳ

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): không nằm trong 14 issue gốc — audit code hiện tại (2026-08-18), cùng đợt
audit module Report, phát hiện gap giống #122/#123/#124: AC yêu cầu filter workspace nhưng
`getViolationReport`/`exportViolations` chưa nhận `workspaceId`.

## ✅ ĐÃ VÁ (2026-08-18)

- `GET /reports/violations` và export vi phạm nhận thêm `workspaceId` — resolve tập `employeeId`
  thuộc workspace trước (cùng repository/method mới dùng chung với #122/#123/#124), lọc qua
  `ViolationSpecification` (overload mới nhận `Collection<UUID> employeeIds`).
- `exportViolations`: khi workspace không có thành viên nào, trả danh sách rỗng ngay (không gọi
  query chính) — tránh nhầm với case "không lọc gì".
- Web Admin: thêm dropdown "Lọc vi phạm theo workspace" trong hàng filter sẵn có (cạnh site,
  nhân viên, loại vi phạm).

### 📝 Gap đã biết, KHÔNG thuộc phạm vi vá đợt này
`ViolationReportParams` (GET list) không có filter `resolved` — chỉ `ViolationExportParams` mới
có. Đây là quyết định thiết kế có chủ đích từ #106 (2026-08-06: "resolved chỉ cần trên export, xem
danh sách để xử lý dùng màn Vi phạm riêng có filter resolved đầy đủ"), không phải gap phát sinh
đợt này — không sửa để tránh mở rộng phạm vi ngoài yêu cầu.

---

## A. Test trên Backend

### 1. ✅ `GET /reports/violations` không kèm `workspaceId` — không đổi hành vi cũ

### 2. ✅✅ (Case quan trọng nhất) `workspaceId=X` chỉ đếm vi phạm của nhân viên thuộc workspace X
- Setup: 1 violation gắn nhân viên KHÔNG thuộc workspace X.
- **Kỳ vọng — xác nhận qua live API call:** không filter → `totalElements=1`; filter
  `workspaceId=X` → `totalElements=0` (đúng, vì nhân viên vi phạm không thuộc workspace đó).

### 3. ✅ Workspace hợp lệ nhưng 0 thành viên → trả rỗng ngay cả trên export
- **Kỳ vọng:** không throw lỗi, không rơi về "không lọc" — trả file/list rỗng.

## B. Test trên Web Admin

### 4. ✅✅ ĐÃ TEST LIVE qua Playwright thật — dropdown workspace mới trên trang báo cáo vi phạm
- Mở tab Vi phạm (không filter, 2 violation của 2 nhân viên khác workspace): "Tổng vi phạm"=2,
  "Chưa xử lý"=2, "Ảnh hưởng bảng công"=2, bảng 2 dòng.
- Chọn workspace trong dropdown mới → cả 3 stat giảm đúng còn 1, bảng còn 1 dòng (đúng nhân viên
  thuộc workspace).

---

## Ghi chú
`tsc --noEmit` phía Web Admin sạch. Regression `tests/violation/*.sh` chạy lại cùng đợt #121-125,
không phát hiện regression.
