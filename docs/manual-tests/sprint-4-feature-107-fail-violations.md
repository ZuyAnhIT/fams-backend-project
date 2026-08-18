# Kịch bản test thủ công — #107 Tạo violation khi fail random check

**Nền tảng: Backend, Web Admin, Mobile App, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — **cáo buộc "liveness_fail KHÔNG BAO GIỜ được tạo ra thực
tế"**. Đã điều tra kỹ — **CÁO BUỘC ĐÃ SAI/LỖI THỜI (khớp đúng phát hiện ở #104): bug này có thật lúc
audit gốc nhưng đã được vá ở commit sau đó.** Ngoài ra phát hiện thêm 2 điểm KHÁC với AC (1 điểm chỉ
là đặt tên, 1 điểm là gap thật):

- **✅ ĐÃ VÁ, KHÔNG CÒN GAP: `liveness_fail` được tạo đúng** — `CheckResponseService.
  applyFaceResult()` tạo violation `liveness_fail` khi `livenessRequired=true` và
  `livenessVerified=false`, có guard chống trùng lặp. `test_fail_violations.sh` Test 8 đã có sẵn
  bằng chứng assert đúng `violation_type='liveness_fail'`.
- **⚠️ AC ghi SAI TÊN loại violation (không phải gap chức năng, chỉ là tài liệu ghi nhầm):** AC ghi
  `wrong_location`/`face_mismatch`, nhưng giá trị THẬT SỰ dùng trong code + DB constraint là
  **`location_fail`** và **`face_fail`** (kèm `liveness_fail`, `no_response`,
  `face_verify_timeout`). Không cần sửa code, chỉ cần lưu ý khi test để không tìm nhầm tên field.
- **❌ GAP thật #1 (chung gốc rễ với #106): KHÔNG gửi notification** — xác nhận qua toàn bộ luồng
  tạo violation trong `CheckResponseService`: không có lệnh gọi notification nào khi violation
  được tạo do fail location/face/liveness.
- **❌ GAP thật #2, MỚI phát hiện (không có trong audit gốc): "details chứa snapshot bằng chứng"
  chỉ ĐÚNG MỘT PHẦN** — entity `Violation` KHÔNG có field JSON `details`/`metadata` có cấu trúc,
  chỉ có `description` dạng CHUỖI VĂN BẢN đơn giản (VD "Face verification failed during random
  check"). Bằng chứng THẬT (tọa độ GPS, điểm khớp mặt, ảnh selfie) VẪN TRUY XUẤT ĐƯỢC nhưng phải đi
  GIÁN TIẾP qua `checkResponseId` → gọi riêng API chi tiết response / API lấy ảnh — không nằm sẵn
  trong chính bản ghi violation như AC ngụ ý "details chứa snapshot".

---

## A. Test trên Backend

### 1. ✅ Fail vị trí → violation `location_fail`
- **Kỳ vọng:** tạo đúng violation, `violationType='location_fail'` (KHÔNG PHẢI `wrong_location`
  như AC ghi).

### 2. ✅ Fail khuôn mặt → violation `face_fail`
- **Kỳ vọng:** `violationType='face_fail'` (KHÔNG PHẢI `face_mismatch` như AC ghi).

### 3. ✅✅ (Case quan trọng — xác nhận bug đã vá) Fail liveness → violation `liveness_fail`
- Phản hồi mode `location_face_liveness` với ảnh không đạt liveness (xem chi tiết ở #104).
- **Kỳ vọng:** tạo đúng violation `violationType='liveness_fail'` — xác nhận bug "dead code" của
  audit gốc đã được vá, không còn tồn tại.

### 4. ❌ Xác nhận gap "không gửi notification" khi tạo violation
- Sau case 1-3, kiểm tra hộp thư HR/nhân viên.
- **Kỳ vọng theo code hiện tại:** không có notification nào — xác nhận đúng gap (chung với #106).

### 5. ❌ Xác nhận gap "bằng chứng không nằm sẵn trong violation"
- Lấy 1 violation vừa tạo, kiểm tra field `description`/response API.
- **Kỳ vọng theo code hiện tại:** chỉ có mô tả văn bản ngắn, KHÔNG có tọa độ GPS/điểm face/ảnh
  selfie đính kèm trực tiếp — muốn xem phải tra riêng qua `checkResponseId`.

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
**Tin quan trọng: bug nghiêm trọng nhất (liveness_fail dead code) ĐÃ ĐƯỢC VÁ từ trước — chỉ cần test
live xác nhận (case 3).** Trọng tâm còn lại: case 4 (gap notification, xử lý CHUNG với #106) và case
5 (gap thiếu snapshot bằng chứng trực tiếp trên violation — cần quyết định nghiệp vụ có đáng thêm
field `details` JSON hay giữ nguyên cách tra gián tiếp qua response, vì đây là thay đổi schema có
phạm vi vừa phải, không nhỏ). Case 1-2 chỉ cần lưu ý đúng tên field khi test, không phải gap.
