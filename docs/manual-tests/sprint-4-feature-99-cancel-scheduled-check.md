# Kịch bản test thủ công — #99 Hủy scheduled check

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "không lưu cancelled_by/at/reason, không ghi audit". Đã xác
nhận lại qua code hiện tại — **ĐÚNG, KHÔNG lỗi thời, gap còn nguyên vẹn — không phải "field có sẵn
nhưng không set", mà HOÀN TOÀN KHÔNG CÓ field này trong entity/migration:**

- **✅ `ScheduledCheckCancelService.cancelCheck` — logic hủy đúng:** chỉ cho hủy khi status CHƯA ở
  trạng thái cuối (`cancelled/responded/no_response`) — tức là còn `pending` hoặc `sent` mới hủy
  được, đúng AC "nếu status pending thì set cancelled" (thực tế còn cho hủy cả `sent` chưa phản
  hồi, hợp lý hơn AC literal).
- **✅ Xóa khỏi hàng đợi Redis khi hủy** — `dispatchQueue.cancel(checkId)` gọi `ZREM`, đảm bảo check
  đã hủy không bị worker gửi nhầm dù đã lỡ nằm trong hàng đợi — đúng AC "remove job nếu có".
- **❌ GAP thật #1 (xác nhận đúng, KHÔNG lỗi thời): KHÔNG có field `cancelledBy`/`cancelledAt`/
  `cancelledReason` NÀO trong `ScheduledCheck` entity** — kiểm tra toàn bộ entity + mọi migration
  liên quan, hoàn toàn không tồn tại. Đây KHÁC với các gap "field có sẵn nhưng service quên set" đã
  gặp ở nhiều tính năng khác — ở đây field còn chưa được TẠO RA, cần thêm migration mới.
- **❌ GAP thật #2 (xác nhận đúng, KHÔNG lỗi thời): KHÔNG ghi audit log** — `ScheduledCheckCancelService`
  không hề gọi `AuditLogService`, dù pattern này ĐÃ CÓ SẴN trong CÙNG module (VD `ManualCheckService`
  gọi `auditLogService.record(...)` cho hành động kích hoạt kiểm tra thủ công) — nghĩa là team đã
  biết cách làm đúng ở chỗ khác trong cùng module, nhưng lại bỏ sót riêng ở hành động hủy.
- **✅ Web Admin ĐÃ CÓ UI hủy hoạt động** (`ScheduledChecksPage.tsx` — nút hủy với modal xác nhận,
  gọi đúng endpoint `POST .../scheduled-checks/{id}/cancel`) — không phải thiếu UI, chỉ thiếu dữ
  liệu audit ở tầng backend.

---

## A. Test trên Backend

### 1. ✅ Hủy check đang `pending` — thành công
- **Kỳ vọng:** `status` chuyển `cancelled`.

### 2. ✅ Hủy check đã `sent` (đã gửi thông báo nhưng chưa phản hồi) — vẫn hủy được
- **Kỳ vọng:** thành công (hợp lý hơn literal AC — cho phép hủy cả khi đã gửi, miễn chưa có kết
  quả cuối).

### 3. Không hủy được check đã `responded`/`no_response`/`cancelled`
- **Kỳ vọng:** bị từ chối — tránh hủy 1 check đã có kết quả cuối cùng.

### 4. ✅ Check đã hủy KHÔNG bị worker gửi nhầm
- Hủy 1 check đang nằm trong hàng đợi Redis TRƯỚC khi worker quét tới giờ.
- **Kỳ vọng:** worker không gửi thông báo, xác nhận `ZREM` hoạt động đúng.

### 5. ❌ Xác nhận gap "không lưu cancelledBy/At/Reason"
- Hủy 1 check kèm lý do (nếu request có field reason).
- **Kỳ vọng theo code hiện tại:** dù truyền lý do gì, response/DB KHÔNG có nơi nào lưu lại ai hủy,
  lúc nào, vì sao — xác nhận đúng gap.

### 6. ❌ Xác nhận gap "không ghi audit log"
- Hủy 1 check, kiểm tra `audit_logs`.
- **Kỳ vọng theo code hiện tại:** KHÔNG có bản ghi nào — xác nhận đúng gap.

---

## B. Test trên Web Admin — ✅ UI đã có sẵn
- Trang danh sách scheduled checks: nút "Hủy" + modal xác nhận hoạt động, gọi đúng API.
- **Lưu ý:** vì thiếu audit log ở backend, HR xem lại lịch sử sau này sẽ KHÔNG biết ai đã hủy check
  nào, lúc nào, lý do gì — chỉ thấy `status=cancelled` trơ trọi.

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
Trọng tâm khi test: case 5-6 (2 gap thật, đều cần migration mới + sửa service — không phải chỉ bug
nhỏ, cần quyết định nghiệp vụ có ưu tiên vá không vì đây là hành động có thể bị lạm dụng để "né"
kiểm tra ngẫu nhiên nếu không có dấu vết ai hủy). Case 1-4 rủi ro fail thấp, logic hủy cốt lõi đã
đúng và có test script `test_cancel_scheduled_check.sh` phủ.
