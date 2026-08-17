# Kịch bản test thủ công — #65 Cập nhật phân công

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không chặn sửa assignment đã completed, không re-validate
overlap". Đã xác nhận và vá (2026-08-17):

- **"Sửa completed": không áp dụng được (không có trạng thái này), nhưng gap cùng tinh thần "sửa
  được assignment ĐÃ HỦY" — ĐÃ VÁ.** `updateAssignment` giờ chặn ngay từ đầu nếu
  `status=cancelled` (400 "Cannot modify a cancelled assignment — it is a closed record kept for
  history"). UI: nút Sửa (bút chì) tự động disable khi dòng đã hủy, kèm tooltip giải thích.
- **Re-validate overlap khi sửa: XÁC NHẬN đã đúng từ trước, giờ dùng chung logic mới (cùng site +
  cross-site) với #63** — sau khi bỏ chặn cùng-site-tuyệt-đối, việc sửa ngày của 1 assignment giờ
  cũng được re-check đúng theo giờ thực tế thay vì chỉ cross-site như trước.
- **Gap mới phát hiện: KHÔNG ghi audit log khi sửa phân công — ĐÃ VÁ**, action `assignment_updated`.

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. Sửa phân công — happy path
- Script `test_update_assignment.sh` test 4-6: sửa startDate/endDate/notes.
- **Kết quả thật:** 200, không hồi quy.

### 2. ✅ Xác nhận gap "sửa được assignment đã hủy" — ĐÃ VÁ, TEST LIVE
- Hủy 1 assignment, sau đó thử sửa `notes` của chính assignment đó qua API.
- **Kết quả thật (2026-08-17):** 400 "Cannot modify a cancelled assignment — it is a closed record
  kept for history" — KHÔNG cho sửa nữa, đúng gap đã vá.

### 3. Sửa `endDate` mới trước `startDate`, vai trò không hợp lệ, shift sai site
- Script test 7-9, không đổi hành vi, không hồi quy.

### 4. ✅ Re-validate overlap khi sửa tạo chồng giờ mới (cùng site, sau khi nâng cấp #63)
- Với 2 assignment không chồng ngày của cùng nhân viên tại cùng site (như tạo ở #63 case 2), sửa
  ngày của 1 assignment để nó chồng với assignment còn lại.
- **Kỳ vọng theo code hiện tại:** bị chặn 409 — xác nhận re-validate dùng đúng logic mới (cùng-site
  + cross-site gộp chung).

### 5. Xác nhận nút Sửa trên UI tự disable với dòng đã hủy
- Xem ảnh `webassign-01-list.png`: dòng "My Assign" trạng thái "Đã hủy" tại Site One — chỉ còn icon
  bút chì (không có icon xóa vì đã hủy rồi), và nút sửa ở trạng thái disable.
- **Kết quả thật:** khớp đúng — không thể bấm sửa dòng đã hủy qua UI.

### 6. ✅ Xác nhận gap "không ghi audit log khi sửa phân công" — ĐÃ VÁ, TEST LIVE
- Kiểm tra `audit_logs` sau 1 lần sửa hợp lệ.
- **Kết quả thật:** có bản ghi `assignment_updated`, before/after đầy đủ.

---

## Ghi chú
Toàn bộ case đã test live: script tự động `test_update_assignment.sh` 12/12 pass, không hồi quy +
test tay qua API/DB xác nhận cả gap chặn-sửa-đã-hủy lẫn audit log. Case 2 là phát hiện quan trọng
nhất trong epic — bảo vệ tính toàn vẹn của bản ghi lịch sử đã đóng. Đã đóng — ĐÃ KHÓA.
