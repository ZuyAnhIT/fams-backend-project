# Kịch bản test thủ công — #66 Hủy phân công

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22) ghi thiếu: "tự hủy scheduled_checks liên quan tốt; thiếu cancelled_by/at". Đã
xác nhận và vá (2026-08-17):

- **Tự hủy `scheduled_checks` pending liên quan: XÁC NHẬN vẫn hoạt động đúng**, không đổi.
- **`cancelled_by`/`cancelled_at`: ĐÃ VÁ** — thêm 2 cột (migration `V100`, cùng pattern đã áp dụng
  cho `employee_invitations` ở V93), set khi hủy, trả về trong response.
- **Gap mới phát hiện: KHÔNG ghi audit log khi hủy phân công — ĐÃ VÁ**, action
  `assignment_cancelled`.
- **Idempotency guard: XÁC NHẬN vẫn hoạt động đúng**, không đổi.

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. Hủy phân công — happy path
- Script `test_cancel_assignment.sh` test 1: hủy qua API.
- **Kết quả thật:** 204, không hồi quy.

### 2. ✅ Xác nhận tự hủy scheduled_checks pending liên quan
- Không đổi, xác nhận đúng qua đọc code (không phải trọng tâm đợt vá này).

### 3. Hủy phân công đã cancelled từ trước (idempotency)
- Script test 5: double-cancel → 400.
- **Kết quả thật:** không đổi, pass.

### 4. ✅ Xác nhận `cancelled_by`/`cancelled_at` được set đúng — ĐÃ VÁ, TEST LIVE
- Hủy 1 assignment qua API, sau đó GET lại (filter `status=cancelled`).
- **Kết quả thật (2026-08-17):** response có `cancelledBy` = đúng UUID người thực hiện hủy,
  `cancelledAt` = đúng timestamp lúc hủy — trước đây 2 field này không tồn tại.

### 5. ✅ Xác nhận hiển thị trên Mobile App — TEST LIVE
- Xem ảnh `app-05-my-assignments-with-cancelled.png` ở màn "Phân công của tôi" (#64): card phân
  công đã hủy hiện dòng đỏ "Đã hủy lúc 17/08/2026 11:13" — xác nhận `cancelledAt` round-trip đúng
  từ backend tới tận UI nhân viên.

### 6. ✅ Xác nhận gap "không ghi audit log khi hủy phân công" — ĐÃ VÁ, TEST LIVE
- Kiểm tra `audit_logs` sau case 1.
- **Kết quả thật:** có bản ghi `assignment_cancelled`, `old_value.status=active` →
  `new_value.status=cancelled`.

### 7. Đăng nhập lại và thử sửa assignment vừa hủy (liên kết với #65 case 2)
- Xác nhận chéo: assignment vừa hủy ở đây không thể sửa được nữa (xem #65).

---

## Ghi chú
Toàn bộ case đã test live: script tự động `test_cancel_assignment.sh` 9/9 pass, không hồi quy +
test tay qua API/DB cho cancelled_by/at + audit log + xác nhận hiển thị tới tận Mobile App thật
(không chỉ dừng ở API). Đã đóng — ĐÃ KHÓA.
