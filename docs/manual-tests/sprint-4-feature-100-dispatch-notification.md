# Kịch bản test thủ công — #100 Gửi random check notification

**Nền tảng: Backend, Mobile App, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "entity không có sent_at/notification_id". Đã xác nhận lại
qua code hiện tại — **ĐÚNG, KHÔNG lỗi thời, gap còn nguyên vẹn:**

- **✅ `RandomCheckDispatchService.dispatch` — luồng gửi đúng:** kiểm tra lại `status=pending`
  trước khi gửi (double-check, xem #98), set `status=sent`, tạo `Notification` với đúng eventType
  `RANDOM_CHECK_SENT` (`RandomCheckEventTypes`), kèm `metadata` có cấu trúc (`checkId, siteId,
  expiresAt`) để App deep-link đúng màn phản hồi kể cả khi app đang đóng hoàn toàn (qua FCM data
  payload, không chỉ khi mở app đồng bộ lại).
- **❌ GAP thật (xác nhận đúng, KHÔNG lỗi thời): `ScheduledCheck` KHÔNG có cột `sent_at` lẫn
  `notification_id`** — kiểm tra toàn bộ entity + mọi migration chạm tới `scheduled_checks`,
  không có field nào trong 2 field này. Hệ quả thực tế: `status` chuyển `sent` nhưng KHÔNG biết
  CHÍNH XÁC lúc nào đã gửi (chỉ suy luận gần đúng từ `scheduled_at` + chu kỳ poll 1 phút, không
  phải thời điểm gửi THẬT), và KHÔNG có liên kết ngược từ `scheduled_check` tới bản ghi
  `notification` đã tạo cho nó — muốn tra "check này đã gửi thông báo nào" phải join gián tiếp qua
  `metadata.checkId` bên trong bảng `notifications`, không có FK trực tiếp.

---

## A. Test trên Backend

### 1. ✅ Gửi đúng lúc đến giờ (`scheduled_at`)
- Sinh check với giờ tương lai gần, chờ tới giờ.
- **Kỳ vọng:** `status` chuyển `pending` → `sent` trong vòng tối đa 1 phút sau `scheduled_at` (chu
  kỳ poll của worker).

### 2. ✅ Tạo đúng `Notification` với eventType `RANDOM_CHECK_SENT`
- Kiểm tra bảng `notifications` sau khi gửi.
- **Kỳ vọng:** có bản ghi mới, `eventType=RANDOM_CHECK_SENT`, `metadata` chứa `checkId`/`siteId`/
  `expiresAt`.

### 3. ✅ Push gửi kèm data payload đầy đủ (deep-link được kể cả khi app đóng)
- Kiểm tra `notification_delivery_logs` / data payload gửi qua FCM.
- **Kỳ vọng:** có `eventType` + các key metadata trong phần "data" của push (không chỉ title/body).

### 4. ❌ Xác nhận gap "không có `sent_at`"
- Kiểm tra entity/response `ScheduledCheck` sau khi đã gửi.
- **Kỳ vọng theo code hiện tại:** không có field nào ghi lại THỜI ĐIỂM CHÍNH XÁC đã gửi — chỉ biết
  `status=sent`, muốn biết "gửi lúc mấy giờ" phải tra `notification_delivery_logs.created_at`
  (gián tiếp, không đảm bảo khớp 100% nếu có nhiều notification liên quan) — xác nhận đúng gap.

### 5. ❌ Xác nhận gap "không có `notification_id`"
- Kiểm tra entity/response `ScheduledCheck`.
- **Kỳ vọng theo code hiện tại:** không có FK trực tiếp tới `notifications.id` — xác nhận đúng gap.

---

## B. Test trên Mobile App
- Nhận push khi có random check gửi tới — chạm vào push mở đúng màn phản hồi kiểm tra ngẫu nhiên,
  đúng `checkId`. **Cần test tay trên thiết bị thật** (đã ghi nhận giới hạn tương tự ở #87/#88 —
  Expo Go không cấp được push token thật).

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
Trọng tâm khi test: case 4-5 (2 gap thật — mức độ ảnh hưởng: KHÔNG chặn chức năng gửi/nhận, chỉ
thiếu dữ liệu truy vết chính xác khi cần điều tra/audit sau này, VD "check này thực sự gửi lúc mấy
giờ, có bị delay bất thường không"). Case 1-3 rủi ro fail thấp, đã có `test_dispatch_notification.sh`
phủ phần cốt lõi.
