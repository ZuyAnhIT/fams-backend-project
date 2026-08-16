# Kịch bản test thủ công — #45 Cập nhật workspace

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không ghi audit".

**Gap "không ghi audit": ĐÃ VÁ (2026-08-16)**, giống #43 — `WorkspaceService.updateWorkspace` giờ
gọi `auditLogService.record(...)` với before/after snapshot, action `workspace_updated`.

**AC "Không cho tạo vòng lặp parent": ĐÃ CÓ, hoạt động đúng** — `updateWorkspace` chặn tự làm cha
của chính mình, và gọi `isDescendant(...)` kiểm tra chuỗi cha ngược từ parent mới đề xuất, chặn
nếu parent mới là hậu duệ của workspace đang sửa (tức sẽ tạo vòng lặp). Đây là điểm test XÁC NHẬN
ĐÚNG (positive case), không phải case tìm gap — không cần sửa gì thêm.

---

## A. Test trên Web Admin

### 1. Sửa tên/mã/mô tả workspace — happy path
- Vào 1 workspace bất kỳ, sửa tên và mô tả.
- **Kỳ vọng:** lưu thành công, hiển thị đúng ngay ở danh sách/cây tổ chức.

### 2. Đổi workspace cha (parent) hợp lệ
- Đổi 1 workspace sang 1 cha khác (không tạo vòng lặp), VD: chuyển từ gốc vào làm con của workspace
  khác.
- **Kỳ vọng:** lưu thành công, cây tổ chức cập nhật đúng vị trí mới.

### 3. ✅ Xác nhận chặn vòng lặp parent — gap AC quan trọng, đã có code chặn
- Chuẩn bị cấu trúc A → B → C (A là cha của B, B là cha của C).
- Thử sửa A, đặt parent của A thành C (hậu duệ của chính A).
- **Kỳ vọng theo code hiện tại:** bị chặn rõ ràng (lỗi 400, thông báo dễ hiểu trên UI), không lưu
  được cấu trúc vòng lặp.

### 4. Tự đặt workspace làm cha của chính nó
- Sửa 1 workspace, thử đặt parent = chính nó.
- **Kỳ vọng:** bị chặn ngay (400), thông báo rõ ràng.

### 5. Đổi mã (code) trùng với workspace khác
- Sửa mã của 1 workspace thành mã đang dùng bởi workspace khác trong cùng tenant.
- **Kỳ vọng:** báo lỗi 409 rõ ràng, không lưu.

### 6. ✅ Xác nhận gap "không ghi audit" — ĐÃ VÁ
- Sau case 1, vào Nhật ký audit, tìm hành động sửa workspace vừa làm.
- **Kết quả thật (2026-08-16):** CÓ bản ghi audit `workspace_updated` với before/after (tên/mô
  tả/loại/parent/status) — xác nhận vá đúng.

---

## Ghi chú
Case 1-6 đều pass. Case 3-4 xác nhận cơ chế chặn vòng lặp hoạt động đúng trong thực tế (không chỉ
đọc code) — đây là bảo vệ dữ liệu quan trọng. Case 6 xác nhận gap audit đã vá, cùng đợt với #43.
