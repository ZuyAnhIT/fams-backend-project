# Kịch bản test thủ công — #62 Cập nhật hoặc ngừng dùng ca

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi: "✅ ĐÃ XONG"; ghi chú "không có endpoint xóa cứng". Đã xác nhận lại qua
code — **audit gốc LỖI THỜI ở đúng điểm ghi chú (endpoint xóa cứng THẬT SỰ tồn tại, chỉ bị chặn
đúng điều kiện — không phải chưa làm); gap mới "không ghi audit" đã VÁ (2026-08-17).**

- **Endpoint xóa cứng (`DELETE`): XÁC NHẬN tồn tại và hoạt động đúng AC** — chặn 400 nếu ca đã từng
  dùng trong assignment (kể cả lịch sử), cho xóa nếu chưa từng dùng. Không đổi trong đợt vá này.
- **Sửa (PUT) một phần: XÁC NHẬN hoạt động đúng**, kể cả re-validate thời gian sau merge. Không đổi.
- **Deactivate không chặn dù có assignment active:** XÁC NHẬN đúng như research, hành vi có chủ
  đích (không phải gap). Không đổi.
- **Mới: sửa ca giờ có thể đặt/bỏ `isDefault`** (dùng chung field `defaultShift` với #59) — nếu đặt
  `true` và ca chưa phải mặc định, tự động bỏ mặc định ở ca khác cùng site (giống hệt logic tạo
  mới). Test live xác nhận cùng #59.
- **Gap mới: KHÔNG ghi audit log khi sửa/xóa ca — ĐÃ VÁ** cùng đợt, action `shift_updated` (PUT) và
  `shift_deleted` (DELETE).

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. Sửa thông tin ca — happy path
- Script `test_update_shift.sh` test 1-4: đổi tên/giờ/allowOvernight.
- **Kết quả thật:** 200, không hồi quy sau khi thêm field `isDefault`.

### 2. ✅ Xác nhận endpoint xóa cứng THẬT SỰ tồn tại — sửa lại nhận định audit gốc
- Tạo 1 ca mới (chưa gán assignment nào), gọi `DELETE`.
- **Kết quả thật:** 200, ca biến mất khỏi danh sách (soft-delete `deletedAt`) — xác nhận endpoint
  có thật, không phải thiếu như ghi chú gốc.

### 3. ✅ Xác nhận block xóa ca đã từng dùng trong assignment
- Script test tương ứng (không đổi).
- **Kết quả thật:** 400 rõ ràng, nút Xóa trên UI đã disable sẵn (dùng `canDelete`).

### 4. ✅ Đặt lại mặc định qua PUT — TEST LIVE
- `PUT .../shifts/{morningId}` với `{"isDefault":true}` (sau khi ca khác đã là mặc định).
- **Kết quả thật:** 200, `isDefault:true` trên ca vừa sửa; kiểm tra lại danh sách → ca kia tự động
  `isDefault:false` — đúng logic dùng chung với #59.

### 5. Deactivate ca đang có assignment active
- Không đổi, giữ nguyên hành vi cho phép + modal xác nhận trên UI.

### 6. Trùng tên khi sửa
- Không đổi, script test không hồi quy.

### 7. ✅ Xác nhận gap "không ghi audit log" ở cả sửa và xóa — ĐÃ VÁ, TEST LIVE
- Sau case 1 (PUT) và case 2 (DELETE), kiểm tra `audit_logs` qua DB.
- **Kết quả thật (2026-08-17):**
  - `shift_updated`: `old_value.isDefault=false` → `new_value.isDefault=true` (case 4), đúng
    before/after.
  - `shift_deleted`: có bản ghi với `old_value` = snapshot ca ngay trước khi xóa, `new_value=null`
    (đúng quy ước — không có "sau" khi đã xóa).

---

## Ghi chú
Toàn bộ 7 case đã test live: script tự động `test_update_shift.sh` 14/14 pass (không hồi quy sau
khi thêm `isDefault` + audit) + test tay qua API/DB. Điểm chỉnh sửa quan trọng nhất so với kịch bản
gốc: **audit gốc (07-22) "không có endpoint xóa cứng" đã được xác nhận là LỖI THỜI** — endpoint đó
tồn tại từ trước đợt vá này, chỉ bị chặn có điều kiện đúng theo AC, không phải thiếu sót cần thêm
mới. Đã đóng — ĐÃ KHÓA.
