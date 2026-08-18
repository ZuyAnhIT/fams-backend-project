# Kịch bản test thủ công — #110 HR xem chi tiết random check

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG — "getDetail config_snapshot". Đã xác nhận lại qua code hiện tại —
**hầu hết đúng, nhưng có 1 điểm SAI trong audit gốc cần cải chính (kế thừa trực tiếp gap đã xác
nhận ở #100):**

- **✅ Hiển thị đúng:** `configSnapshot`, `expiresAt`, response (GPS, điểm khớp mặt, liveness),
  `status`, danh sách violation liên quan (join qua `violationRepository.
  findByScheduledCheckIdAndDeletedAtIsNull`, có link sang trang vi phạm).
- **❌ CẢI CHÍNH: AC ghi "hiển thị sent_at" nhưng KHÔNG THỂ hiển thị vì field này KHÔNG TỒN TẠI** —
  kế thừa trực tiếp gap đã xác nhận ở #100 (`ScheduledCheck` không có cột `sentAt`). Response chi
  tiết CHỈ có `scheduledAt` (giờ DỰ KIẾN gửi), Web Admin hiện đang gắn nhãn field này là "Giờ dự
  kiến" — KHÔNG có field/nhãn nào cho "giờ THỰC TẾ đã gửi". Đây KHÔNG PHẢI lỗi hiển thị/UI, mà vì
  dữ liệu gốc chưa từng được lưu (đã ghi nhận ở #100, ảnh hưởng lan sang cả màn chi tiết này).

---

## A. Test trên Backend

### 1. ✅ Hiển thị đủ `configSnapshot`, `expiresAt`, response, violation liên quan
- Xem chi tiết 1 check đã có phản hồi và có violation.
- **Kỳ vọng:** đủ thông tin, đúng dữ liệu.

### 2. ❌ Xác nhận gap "không có sent_at thật" (kế thừa từ #100)
- Xem chi tiết 1 check đã gửi (`status=sent`/`responded`).
- **Kỳ vọng theo code hiện tại:** chỉ thấy `scheduledAt` (giờ dự kiến), KHÔNG có field nào ghi giờ
  THỰC TẾ đã gửi — xác nhận đúng gap, không phải lỗi riêng của #110.

### 3. Xem chi tiết check CHƯA có phản hồi
- **Kỳ vọng:** hiển thị rõ trạng thái "chưa phản hồi", không lỗi khi phần response rỗng.

## B. Test trên Web Admin
### 4. ✅ Modal chi tiết hiển thị đủ thông tin, link sang trang vi phạm liên quan
- **Kỳ vọng:** bấm vào violation liên quan mở đúng trang chi tiết vi phạm đó.

---

## ✅ PASS — ĐÃ KHÓA (2026-08-18)

### Case 2 — gap "sent_at": xác nhận đã tự khắc phục từ #100
Test live: xem chi tiết 1 check thủ công (đã chắc chắn có `sentAt` vì `ManualCheckService` set
trực tiếp) — response trả đúng `sentAt` có giá trị thật (không null), khớp đúng dự đoán rằng vá
#100 sẽ tự động khắc phục gap này ở #110 mà không cần đổi gì thêm.

### Bổ sung nhất quán (2026-08-18)
Thêm field `triggerType` vào `ScheduledCheckDetailResponse` (cùng logic derive như #108/#109) —
không phải fix gap, chỉ để chi tiết và danh sách nhất quán cùng 1 field.

## Ghi chú
Regression: 26/26. Case 1, 3, 4 rủi ro fail thấp, đã có `test_check_detail.sh` phủ phần cốt lõi.
