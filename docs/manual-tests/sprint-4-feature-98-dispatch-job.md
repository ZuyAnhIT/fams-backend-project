# Kịch bản test thủ công — #98 Tạo job gửi check

**Nền tảng: Backend, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG — "RandomCheckDispatchQueue (Redis) + Job". Đã xác nhận lại qua code
hiện tại — **ĐÚNG bản chất, nhưng AC ghi "lưu bull_job_id" là chi tiết THỪA/lỗi thời từ 1 bản đặc
tả cũ khác kiến trúc thật, cần làm rõ khi test để không tốn công tìm 1 field không bao giờ tồn tại:**

- **Kiến trúc THẬT (không phải BullMQ — đó là tên thư viện Node.js, backend này là Java/Spring):**
  `RandomCheckDispatchQueue` — 1 **Redis ZSET** (`fams:randomcheck:dispatch`, điểm số = thời gian
  `scheduled_at`), được `RandomCheckDispatchJob` (`@Scheduled` mỗi phút) quét lấy các check ĐÃ ĐẾN
  GIỜ. Phần tử trong ZSET CHÍNH LÀ `scheduledCheckId` — không hề có 1 "job ID" riêng biệt nào khác
  được cấp phát ở đâu cả (khác hẳn hình dung "tạo delayed job, nhận về jobId rồi lưu jobId" theo
  kiểu BullMQ/Sidekiq).
- **✅ AC "worker kiểm tra status pending trước khi gửi" — ĐÚNG:** `RandomCheckDispatchService.
  dispatch` kiểm tra lại `"pending".equals(check.getStatus())` NGAY TRƯỚC KHI gửi — tránh gửi trùng
  nếu check đã bị hủy/gửi bởi luồng khác giữa lúc enqueue và lúc worker xử lý.
- **⚠️ Làm rõ: "lưu bull_job_id" trong AC là chi tiết KHÔNG ÁP DỤNG ĐƯỢC cho kiến trúc thật, KHÔNG
  PHẢI thiếu sót cần vá.** Vì bản thân UUID của `scheduled_check` đã được dùng lại làm khóa trong
  Redis ZSET, không có khái niệm "job ID độc lập" để mà lưu — đây là AC còn sót lại từ 1 bản đặc tả
  cũ dùng công nghệ khác (BullMQ, thường đi kèm hệ Node.js), không khớp với backend Java/Spring
  hiện tại. **Không cần thêm field `bull_job_id` nào cả.**

---

## A. Test trên Backend

### 1. ✅ Enqueue vào Redis ZSET đúng lúc sinh lịch
- Sinh 1 scheduled_check, kiểm tra Redis key `fams:randomcheck:dispatch` (VD qua `ZSCORE`).
- **Kỳ vọng:** có 1 phần tử với `scheduledCheckId` tương ứng, điểm số = timestamp `scheduled_at`.

### 2. ✅ Worker chỉ gửi khi đến giờ (poll mỗi phút)
- Sinh 1 scheduled_check với `scheduled_at` ở tương lai gần (VD +2 phút).
- **Kỳ vọng:** trước thời điểm đó, check vẫn `status=pending`, chưa gửi. Sau khi tới giờ (tối đa
  +1 phút do chu kỳ poll), check chuyển `status=sent`.

### 3. ✅ Double-check status pending trước khi gửi — tránh gửi trùng
- Sinh 1 scheduled_check, HỦY nó (case #99) NGAY TRƯỚC thời điểm worker quét tới.
- **Kỳ vọng:** worker KHÔNG gửi thông báo cho check đã hủy dù nó từng nằm trong hàng đợi Redis —
  xác nhận double-check hoạt động đúng, tránh race condition.

### 4. ⚠️ Xác nhận KHÔNG có field `bull_job_id` — tránh tìm nhầm
- Kiểm tra entity `ScheduledCheck` / response API.
- **Kỳ vọng theo code hiện tại:** không có field này — ĐÚNG THEO THIẾT KẾ, không phải thiếu sót.

---

## ✅ PASS — ĐÃ KHÓA (2026-08-18)
Không có gap cần vá — xác nhận qua `tests/randomcheck/test_dispatch_job.sh` (15/15 pass) và toàn bộ
regression suite. Đã bổ sung `sentAt` cho đúng thời điểm dispatch thật ở bước #100 (cùng module,
cùng đợt fix) — không thay đổi cơ chế Redis ZSET + poll đã xác nhận đúng ở đây.
