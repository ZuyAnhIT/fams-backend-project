# Kịch bản test thủ công — #88 Gửi push notification

**Nền tảng: Backend, Mobile App, Queue/AI/Automation.**

## ✅ ĐÃ FIX (2026-08-17) — ĐÃ KHÓA

Các gap đặt tên field (channel/provider_message_id) và gap nghiêm trọng nhất (token chết không bao
giờ bị dọn) đã được xác nhận và vá — quyết định tự đưa ra vì đây là fix đúng-sai rõ ràng, không cần
quyết định nghiệp vụ (không đổi hành vi gửi/retry/fallback, chỉ thêm khả năng tự dọn dẹp + audit).

### Thay đổi
- **Migration V102**: `notification_delivery_logs.provider_message_id` (mới) — lưu `messageId`
  FCM trả về khi gửi thành công (trước đây bị bỏ qua hoàn toàn).
- `FcmClient.SendResult` — thêm field `errorCode` (mã lỗi FCM thô, VD `UNREGISTERED`,
  `INVALID_ARGUMENT`), tách biệt khỏi `lastError` (chuỗi mô tả đầy đủ cho log).
- `FcmClient.sendToToken` — khi gặp `UNREGISTERED`, **dừng retry ngay** (không tốn 3 lần thử +
  backoff cho 1 token chắc chắn chết vĩnh viễn).
- `UserDeviceService.sendPush` — khi `errorCode=UNREGISTERED`, **vô hiệu hóa (soft-delete) thiết bị
  đó ngay lập tức** — mọi lần gửi push trong tương lai cho user đó sẽ bỏ qua token chết này, thay vì
  thử lại 3 lần với backoff MÃI MÃI như trước đây.
- `provider_message_id` được lưu đúng vào `NotificationDeliveryLog` khi gửi thành công.

### KHÔNG thay đổi (quyết định giữ nguyên)
- KHÔNG thêm field `channel=push_fcm` riêng trên `Notification` entity — thông tin tương đương đã
  có trên `NotificationDeliveryLog.channel` (giá trị thật `"FCM"`/`"EMAIL_FALLBACK"`), thêm field
  trùng lặp trên entity chính sẽ không mang lại giá trị mới, chỉ tốn thêm 1 cột không cần thiết.

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-17)

### 1. ✅ Fan-out tới nhiều thiết bị (không đổi, vẫn đúng)
- Regression `test_fcm_retry_fallback.sh` — 11/11 pass.

### 2. ✅ Error code plumbing hoạt động đúng end-to-end
- Đăng ký thiết bị với token giả dạng sai format, trigger gửi push thật (qua luồng thiếu checkout).
- **Kết quả thực tế:** Firebase (đã cấu hình thật trong môi trường) trả lỗi
  `INVALID_ARGUMENT: The registration token is not a valid FCM registration token`, ghi đúng vào
  `error_message` với format `<CODE>: <message>` — xác nhận việc trích xuất `errorCode` từ FCM SDK
  hoạt động chính xác.

### 3. ✅ Restraint đúng: KHÔNG vô hiệu hóa thiết bị cho lỗi KHÁC `UNREGISTERED`
- Cùng test trên (`INVALID_ARGUMENT`, không phải `UNREGISTERED`).
- **Kết quả thực tế:** `user_devices.deleted_at` VẪN NULL sau khi gửi lỗi — xác nhận code chỉ vô
  hiệu hóa đúng trường hợp `UNREGISTERED`, không "quá tay" xóa nhầm thiết bị đang gặp lỗi tạm thời
  khác (network, cấu hình sai tạm thời...).

### 4. ✅ Fallback email vẫn hoạt động đúng sau khi push thất bại
- Cùng test trên, thiết bị là thiết bị active DUY NHẤT nên push thất bại → fallback kích hoạt.
- **Kết quả thực tế:** ghi log `FALLBACK_EMAIL_SENT` đúng ngay sau dòng `FCM FAILED`.

### 5. ⚠️ Chưa test được trực tiếp: vô hiệu hóa thiết bị khi lỗi CHÍNH XÁC là `UNREGISTERED`
- Cần 1 token đã từng hợp lệ rồi bị Firebase thu hồi thật (app gỡ cài đặt/token revoke) — không thể
  tạo giả trong môi trường test (token dạng sai format chỉ tạo ra `INVALID_ARGUMENT`, không phải
  `UNREGISTERED`). Đã xác nhận đúng qua đọc code (logic `if ("UNREGISTERED".equals(errorCode))` rõ
  ràng, không mơ hồ) nhưng CHƯA có bằng chứng chạy thật với 1 token `UNREGISTERED` thật.

---

## B. Test trên Mobile App
- **Cần test tay trên thiết bị thật:** cài app thật, đăng ký push, XÓA APP (không đăng xuất trước
  — mô phỏng tình huống người dùng gỡ app mà quên đăng xuất), sau đó trigger 1 notification tới
  user đó từ Web Admin/HR. Theo lý thuyết, lần gửi ĐẦU sẽ FAIL với `UNREGISTERED` và thiết bị sẽ bị
  vô hiệu hóa ngay — **đây là case duy nhất trong đợt fix này cần người dùng tự tay xác nhận qua
  thiết bị thật**, vì không tái tạo được trong môi trường test tự động.

## Regression
`tests/notification/test_fcm_retry_fallback.sh` — 11/11 PASS, không regression.
