# Kịch bản test thủ công — #74 Tính work_minutes cho cặp check-in/out

**Nền tảng: Backend, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code (`computeWorkMinutes`, dùng chung với
#73) — **XÁC NHẬN 2 GAP THẬT trong chính AC gốc, KHÔNG được vá:**

- **"Ghép đúng cặp": không phải vấn đề cần giải quyết** — vì check-in/check-out là CÙNG 1 dòng
  (xem #72), không có 2 dòng riêng cần ghép, nên không có rủi ro ghép sai cặp.
- **"Trừ break theo schedule": KHÔNG TỒN TẠI — gap thật, chưa làm gì cả.** Không có logic trừ giờ
  nghỉ trưa/giải lao nào trong `computeWorkMinutes` hay bất kỳ đâu trong module Chấm công.
  `workMinutes` = khoảng thời gian thô, chỉ bị cắt theo `[giờ bắt đầu ca, min(giờ check-out, giờ
  kết thúc ca ± lateCheckoutMinutes)]`, không trừ break.
- **"Bỏ qua invalid nếu policy": KHÔNG TỒN TẠI — gap thật.** `workMinutes` được tính và lưu KHÔNG
  PHÂN BIỆT status (`valid`/`pending_review`/`rejected`) — 1 check-out đang chờ HR duyệt vẫn có
  `workMinutes` tính sẵn, không bị loại trừ hay để trống chờ duyệt xong mới tính.
- **"Trigger summary refresh": XÁC NHẬN hoạt động đúng** — gọi
  `attendanceSummaryService.recomputeForCheckin` sau cả check-in lẫn check-out, nhưng bọc trong
  try/catch chỉ log cảnh báo nếu lỗi (API check-in/check-out vẫn trả về thành công dù bước cập nhật
  tổng hợp công bị lỗi ngầm) — cần biết để không nhầm lẫn khi debug dữ liệu tổng hợp công sai lệch.

---

## A. Test trên Backend (qua API + kiểm tra dữ liệu)

### 1. Tính work_minutes cơ bản — happy path
- Check-in đúng giờ, check-out đúng giờ (không có yếu tố cắt/giới hạn nào).
- **Kỳ vọng:** `workMinutes` = đúng số phút giữa 2 thời điểm.

### 2. ✅ Xác nhận KHÔNG trừ giờ nghỉ trưa
- Check-in 08:00, check-out 17:00 (ca hành chính có giờ nghỉ trưa thông thường 12:00-13:00 theo
  thực tế văn phòng, nhưng hệ thống không có khái niệm này).
- **Kỳ vọng theo code hiện tại:** `workMinutes` = 540 phút (9 tiếng nguyên, KHÔNG trừ 1 tiếng nghỉ
  trưa) — xác nhận đúng gap đã phát hiện, không phải lỗi tính toán.

### 3. ✅ Xác nhận `workMinutes` vẫn được tính dù status `pending_review`
- Tạo 1 cặp check-in/out có status cuối `pending_review` (VD: ngoài geofence lúc check-out — xem
  #72 case 2).
- **Kỳ vọng theo code hiện tại:** `workMinutes` vẫn có giá trị tính sẵn (không phải null/0 chờ HR
  duyệt) — xác nhận đúng gap đã phát hiện.

### 4. Xác nhận trigger cập nhật attendance summary sau check-in/check-out
- Sau case 1, kiểm tra bảng tổng hợp công (attendance summary) của ngày đó.
- **Kỳ vọng:** có cập nhật đúng số phút làm việc trong ngày, đồng bộ với `workMinutes` của check-in.

---

## Ghi chú
Đây là 2 gap thật DUY NHẤT có ý nghĩa nghiệp vụ rõ ràng trong cả nhóm #67-76 (ngoài audit log
chung) — case 2 (không trừ break) ảnh hưởng trực tiếp tới độ chính xác bảng lương nếu công ty có
quy định giờ nghỉ trưa không tính công; case 3 (tính work_minutes cả khi pending_review) có thể
khiến báo cáo công "nhìn có vẻ đủ" trước khi HR thực sự duyệt.

**Đã hỏi ý kiến chủ dự án (2026-08-17) — quyết định: KHÔNG cần trừ break, KHÔNG cần loại trừ
work_minutes khi pending_review.** Cả 2 hành vi hiện tại được xác nhận là ĐÚNG theo nhu cầu nghiệp
vụ thực tế, không phải gap cần vá. Không có thay đổi code nào cho tính năng này. Đã đóng — ĐÃ KHÓA.
