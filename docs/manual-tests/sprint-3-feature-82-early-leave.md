# Kịch bản test thủ công — #82 Tính về sớm

**Nền tảng: Backend, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code hiện tại — **đúng AC, có 1 nuance quan
trọng cần biết khi test:**

- **`earlyLeaveMinutes = giờ kết thúc ca - giờ check-out cuối cùng`**, chỉ tính khi có checkout —
  đúng như AC.
- **QUAN TRỌNG: chỉ tính về sớm khi TẤT CẢ phiên trong ngày đã checkout hết (không còn phiên nào
  đang mở)** — nếu ngày đó có 1 phiên check-in nhưng CHƯA check-out (thiếu checkout, xem #84), toàn
  bộ tính toán "về sớm" của ngày đó bị bỏ qua hoàn toàn (`earlyLeave=false, earlyLeaveMinutes=0`) dù
  thực tế nhân viên có thể đã rời sớm trước khi quên check-out. Đây là hành vi có chủ đích (không
  đủ dữ liệu tin cậy để kết luận "về sớm" khi còn phiên treo), không phải bug.
- Dùng snapshot giờ ca đã chụp lúc check-in, không lấy lại Shift hiện tại (cùng nguyên lý #81).

---

## A. Test trên Backend

### 1. Check-out đúng giờ hoặc muộn hơn — không tính về sớm
- Check-out đúng giờ kết thúc ca hoặc muộn hơn.
- **Kỳ vọng:** `earlyLeave=false`, `earlyLeaveMinutes=0`.

### 2. Check-out sớm hơn giờ kết thúc ca
- Check-out sớm 20 phút so với giờ kết thúc ca.
- **Kỳ vọng:** `earlyLeave=true`, `earlyLeaveMinutes=20`.

### 3. ✅ Ngày có phiên còn thiếu checkout — KHÔNG tính về sớm dù có phiên khác đã về sớm
- Tạo 1 ngày có 2 phiên: phiên 1 check-in/out về sớm 15 phút; phiên 2 chỉ check-in, KHÔNG check-out
  (còn treo).
- **Kỳ vọng theo code hiện tại:** `earlyLeave=false, earlyLeaveMinutes=0` cho CẢ NGÀY — dù phiên 1
  đã thực sự về sớm, hệ thống không kết luận vì còn phiên treo chưa đủ dữ liệu. Đây là hành vi thiết
  kế có chủ đích, không phải bug — quan trọng để tránh báo sai kết quả test.

### 4. Nhiều phiên, tất cả đã checkout — tính theo phiên CUỐI CÙNG
- 2 phiên trong ngày đều đã checkout, phiên đầu về sớm nhưng phiên cuối đúng giờ.
- **Kỳ vọng:** `earlyLeave` tính theo checkout CUỐI CÙNG trong ngày, không bị ảnh hưởng bởi phiên
  đầu.

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
Trọng tâm khi test: case 3 — đây là hành vi dễ bị hiểu nhầm là bug nếu tester không biết trước
("tại sao nhân viên rõ ràng về sớm mà hệ thống không báo?"), cần xác nhận đúng ý đồ thiết kế (ưu
tiên độ tin cậy dữ liệu hơn là báo cáo vội) trước khi kết luận sai lệch. Case 1-2, 4 rủi ro fail thấp.
