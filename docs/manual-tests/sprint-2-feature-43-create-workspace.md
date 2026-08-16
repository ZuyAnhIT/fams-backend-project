# Kịch bản test thủ công — #43 Tạo workspace

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không ghi audit".

**ĐÃ VÁ (2026-08-16):** `WorkspaceService.createWorkspace` giờ gọi `auditLogService.record(...)`
đúng pattern đã dùng ở Employee/RBAC/Tenant — ghi action `workspace_created` với snapshot
name/description/type/parentId/status. Áp dụng cho toàn bộ module workspace (create + update +
assign member + transfer member, xem #45-47). Test live: tạo workspace → kiểm tra
`audit_logs` → có bản ghi `workspace_created` đúng entity_id.

---

## A. Test trên Web Admin

### 1. Tạo workspace gốc (không có parent) — happy path
- Vào Phòng ban → Tạo workspace mới, nhập tên + mã, không chọn workspace cha.
- **Kỳ vọng:** tạo thành công, xuất hiện ngay trong danh sách/cây tổ chức.

### 2. Tạo workspace con (có parent)
- Tạo 1 workspace mới, chọn workspace cha là workspace vừa tạo ở case 1.
- **Kỳ vọng:** tạo thành công, hiển thị đúng vị trí lồng trong cây tổ chức (dưới workspace cha).

### 3. Tạo trùng mã (code) trong cùng tenant
- Tạo workspace mới với mã trùng với 1 workspace đã tồn tại.
- **Kỳ vọng:** báo lỗi rõ ràng (409 hoặc validation lỗi hiển thị trên form), không tạo trùng.

### 4. Tạo workspace với tên/mã để trống hoặc không hợp lệ
- Thử submit form khi thiếu tên bắt buộc.
- **Kỳ vọng:** form chặn submit, báo lỗi validate rõ ràng, không gọi API khi dữ liệu chưa hợp lệ.

### 5. ✅ Xác nhận gap "không ghi audit" — ĐÃ VÁ
- Sau case 1, vào Nhật ký audit, tìm hành động tạo workspace vừa làm.
- **Kết quả thật (2026-08-16):** CÓ bản ghi audit `workspace_created`, đúng entity_id của workspace
  vừa tạo, snapshot đầy đủ tên/mô tả/loại/parent.

---

## Ghi chú
Case 1-5 đều pass. Gap audit đã vá cùng ngày với #45 (áp dụng chung logic cho toàn module workspace).
