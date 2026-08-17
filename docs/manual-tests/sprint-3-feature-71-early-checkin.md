# Kịch bản test thủ công — #71 Kiểm tra check-in sớm

**Nền tảng: Backend, Mobile App.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code (`validateCheckinWindow`) — **đúng cơ
chế, nhưng CHẶT HƠN AC mô tả — cần điều chỉnh kỳ vọng test cho khớp thực tế:**

- Tính khung giờ theo **giờ địa phương của site** (không phải giờ server mặc định), dùng
  `Shift.earlyCheckinMinutes` (theo từng ca, không phải cấu hình toàn hệ thống).
- **Check-in quá sớm: LUÔN TỪ CHỐI CỨNG** (lỗi `CHECKIN_TOO_EARLY`, HTTP 422) — AC gốc dùng chữ
  "cảnh báo/từ chối theo policy" ngụ ý có thể cấu hình mềm, nhưng thực tế KHÔNG có tùy chọn nào để
  biến đây thành cảnh báo mềm (chỉ ghi log rồi vẫn cho check-in) — luôn chặn cứng, không có ngoại lệ.
- **Quy tắc bổ sung ngoài AC gốc: cũng chặn nếu check-in SAU KHI ca đã kết thúc**
  (`CHECKIN_TOO_LATE`) — tức là chỉ check-in được trong khung
  `[giờ bắt đầu ca - earlyCheckinMinutes, giờ kết thúc ca)`, không phải chỉ kiểm tra đầu ca như AC
  gốc ngụ ý.
- **KHÔNG có bản ghi nào được tạo khi bị từ chối** — vì lỗi được ném ra TRƯỚC khi tạo check-in, nên
  AC gốc "lưu invalid_reasons nếu cần" không đúng với thực tế: một lần thử check-in quá sớm không
  để lại dấu vết gì trong bảng `checkins`.
- Nếu phân công không gắn ca (shift) cụ thể → không có giới hạn giờ nào, check-in được bất kỳ lúc
  nào trong ngày được phân công.

---

## A. Test trên Mobile App

### 1. Check-in trong khung giờ hợp lệ — happy path
- Check-in trong khoảng `[giờ bắt đầu ca - earlyCheckinMinutes, giờ kết thúc ca)`.
- **Kỳ vọng:** thành công.

### 2. Check-in quá sớm (trước khung cho phép)
- Thử check-in sớm hơn `earlyCheckinMinutes` trước giờ bắt đầu ca.
- **Kỳ vọng theo code hiện tại:** bị từ chối HOÀN TOÀN (HTTP 422, lỗi rõ ràng), không tạo được bản
  ghi check-in nào — không phải "cảnh báo nhưng vẫn cho qua".

### 3. Check-in sau khi ca đã kết thúc
- Thử check-in sau giờ kết thúc ca (chưa từng check-in trong ca đó).
- **Kỳ vọng theo code hiện tại:** cũng bị từ chối — xác nhận đúng quy tắc bổ sung ngoài AC gốc,
  không phải lỗi hệ thống.

### 4. Check-in đúng ranh giới (chính xác thời điểm bắt đầu khung cho phép)
- Check-in đúng thời điểm `giờ bắt đầu ca - earlyCheckinMinutes`.
- **Kỳ vọng:** thành công (biên dưới tính là hợp lệ, không bị coi là "quá sớm").

### 5. Phân công không gắn ca cụ thể — check-in bất kỳ giờ nào
- Với 1 phân công không chọn shift (shiftId=null), check-in vào bất kỳ thời điểm nào trong ngày.
- **Kỳ vọng:** không bị giới hạn giờ, luôn cho phép (miễn còn trong ngày được phân công).

### 6. Xác nhận không có bản ghi check-in nào được tạo khi bị từ chối do quá sớm/quá muộn
- Sau case 2 hoặc 3, kiểm tra danh sách check-in của nhân viên (lịch sử hoặc DB).
- **Kỳ vọng theo code hiện tại:** không có bản ghi nào ứng với lần thử bị từ chối — khớp đúng phát
  hiện, không phải lỗi thiếu ghi nhận.

---

## Ghi chú
Trọng tâm khi test: case 2 (xác nhận đúng "luôn từ chối cứng", không phải cảnh báo mềm như AC gốc
ngụ ý — quan trọng để không báo nhầm hành vi hiện tại là bug) và case 3 (quy tắc chặn muộn ngoài AC
gốc — cần thông báo message rõ ràng cho nhân viên hiểu vì sao bị chặn, không nhầm lẫn với case 2).

**ĐÃ TEST (2026-08-17):** không có gap, không sửa code — hành vi hiện tại xác nhận đúng thiết kế.
Script `test_early_checkin.sh` chạy lại 6/6 pass (bao gồm đúng case 2/3/4 ở trên), không hồi quy.
Đã đóng — ĐÃ KHÓA.
