# Kịch bản test thủ công — #100 Gửi random check notification

**Nền tảng: Backend, Mobile App, Queue/AI/Automation.**

## ✅ ĐÃ FIX (2026-08-18) — ĐÃ KHÓA

Cả 2 gap thật đã xác nhận trước đó ("không có sent_at", "không có notification_id") đã được vá —
gap dữ liệu truy vết rõ ràng theo đúng AC, không cần hỏi quyết định.

### Thay đổi
- **Migration V104** (chung với #99): thêm `scheduled_checks.sent_at`, `notification_id`.
- `RandomCheckDispatchService.dispatch` — set `sentAt=now()` ngay khi status chuyển `sent`; sau khi
  tạo `Notification` thành công, set `notificationId` = ID bản ghi vừa tạo.
- **Bug tự phát hiện khi test live, đã vá trong cùng đợt:** `ManualCheckService` (luồng "HR kích
  hoạt kiểm tra ngay", #108) tạo check với `status="sent"` trực tiếp — KHÔNG đi qua
  `RandomCheckDispatchService.dispatch()` — nên ban đầu `sentAt` vẫn `null` dù `notificationId` đã
  đúng. Đã bổ sung set `sentAt` ngay tại đây, test live lại xác nhận đúng.

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-18)

### 1. ✅ Gửi đúng lúc đến giờ, tạo đúng Notification
### 2. ✅ Xác nhận ĐÃ CÓ `sentAt` (đã fix, cả 2 luồng dispatch)
- Test cả luồng tự động (poller) lẫn luồng thủ công (HR trigger ngay, #108).
- **Kết quả thực tế:** cả 2 luồng đều có `sent_at` đúng thời điểm dispatch thực tế — ĐÚNG (bug ban
  đầu ở luồng thủ công đã được phát hiện và vá ngay trong đợt test này).

### 3. ✅ Xác nhận ĐÃ CÓ `notificationId` (đã fix)
- **Kết quả thực tế:** `notification_id` trỏ đúng tới bản ghi `notifications` vừa tạo, xác nhận qua
  cả DB lẫn response API chi tiết.

---

## B. Test trên Mobile App
- Không thay đổi hành vi phía App — dữ liệu mới chỉ phục vụ HR tra cứu ở Web Admin. Không cần test
  lại App.

## C. Test trên Web Admin — ✅ PASS, đã test live qua UI thật (Playwright, 2026-08-18)
- Trang chi tiết lượt kiểm tra: field "Giờ gửi thực tế" hiển thị đúng giá trị `sentAt`, khác biệt rõ
  với "Giờ dự kiến" (`scheduledAt`) khi có độ trễ dispatch.

---

## Regression
Toàn bộ `tests/randomcheck/*.sh` (18 suite) — 100% PASS sau fix, không có regression.
