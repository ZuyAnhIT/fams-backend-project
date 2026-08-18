# Kịch bản test thủ công — #105 Từ chối phản hồi trễ

**Nền tảng: Backend, Mobile App, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG — "CheckExpiredException". Đã xác nhận lại qua code hiện tại —
**ĐÚNG, không lỗi thời, và phạm vi tách bạch đúng đắn với #106:**

- **`CheckResponseService.submit()`** — nếu `now > expiresAt` → ném `CheckExpiredException` → HTTP
  410 (Gone). Phản hồi trễ bị TỪ CHỐI THẲNG, không lưu bất kỳ `random_check_response` nào.
- **✅ Xác nhận đúng phạm vi: #105 CHỈ từ chối, KHÔNG tự tạo violation** — việc tạo violation cho
  check không được phản hồi là công việc RIÊNG của #106 (`NoResponseViolationJob`, quét định kỳ mỗi
  2 phút các check `status=sent` đã quá `expiresAt`, gọi `NoResponseViolationService.
  processAllExpired()` tạo violation `no_response`). Đây là THIẾT KẾ TÁCH BẠCH ĐÚNG (separation of
  concerns) — #105 không "thiếu" phần tạo violation, mà cố ý giao việc đó cho #106 xử lý.

---

## A. Test trên Backend
### 1. ✅ Phản hồi SAU `expiresAt` — bị từ chối
- **Kỳ vọng:** HTTP 410, không có bản ghi `random_check_response` nào được tạo.

### 2. ✅ Phản hồi TRƯỚC `expiresAt` (kể cả sát giờ) — vẫn được chấp nhận
- Phản hồi ở giây cuối cùng trước khi hết hạn.
- **Kỳ vọng:** được chấp nhận bình thường, không bị từ chối nhầm do sai lệch thời gian nhỏ.

### 3. ✅ Xác nhận #105 KHÔNG tự tạo violation (việc của #106)
- Để 1 check quá hạn không phản hồi, kiểm tra ngay sau khi hết hạn (trước khi job quét #106 kịp
  chạy).
- **Kỳ vọng:** chưa có violation nào (đợi tới chu kỳ job #106 mới có) — xác nhận đúng ranh giới
  trách nhiệm giữa 2 tính năng, tránh hiểu nhầm là gap khi test #105 riêng lẻ.

## B. Test trên Mobile App
### 4. UI hiển thị đúng trạng thái "đã hết hạn" khi cố phản hồi trễ
- **Kỳ vọng:** App hiển thị thông báo rõ ràng (không phải lỗi chung chung) khi bị từ chối do trễ.

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
Không có gap cần vá. Trọng tâm khi test: case 3 (làm rõ ranh giới với #106, tránh test nhầm phạm vi).
`test_late_response_rejection.sh` đã phủ phần backend cốt lõi.
