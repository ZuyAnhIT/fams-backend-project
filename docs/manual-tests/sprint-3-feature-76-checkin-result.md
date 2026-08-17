# Kịch bản test thủ công — #76 Hiển thị kết quả check-in/out

**Nền tảng: Backend, Mobile App.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code (`getCheckinResult` + App
`CheckinResult.tsx`) — **đúng về bản chất, có vài điểm khác cách AC mô tả:**

- Trạng thái hiển thị: `valid | pending_review | rejected`.
- Message hiển thị cho `pending_review` CỐ Ý CHUNG CHUNG ("vị trí hoặc xác thực khuôn mặt") thay vì
  chỉ đích danh lý do cụ thể trong 1 câu — nhưng App bù lại bằng cách hiện TỪNG MỤC riêng biệt
  (trong/ngoài geofence, đã xác thực khuôn mặt hay chưa + điểm số, liveness đạt hay không) — lý do
  cụ thể vẫn xem được, chỉ là qua các trường có cấu trúc thay vì 1 câu duy nhất.
- App hiện nút "Cần bổ sung thông tin?" (gửi giải trình) bất cứ khi nào `status !== 'valid'`, và
  riêng nút "Đăng ký lại Face ID" khi fail khuôn mặt mà điểm số trả về là `null` (suy luận: có thể
  do embedding cũ/lỗi trích xuất/chưa có hồ sơ — vì mã lỗi callback gốc không được lộ ra client).
- **"Hướng dẫn thử lại": KHÔNG có nút/hành động "thử check-in lại" tường minh trên màn kết quả** —
  chỉ có 2 lối đi (giải trình / đăng ký lại Face ID), không có CTA "Check-in lại ngay" — khác biệt
  nhỏ so với AC gốc, không phải thiếu chức năng nghiêm trọng (nhân viên vẫn quay lại tab Chấm công
  để thử lại được, chỉ là không có nút tắt ngay tại màn kết quả).

---

## A. Test trên Mobile App

### 1. Xem kết quả check-in `valid` — happy path
- Check-in thành công hoàn toàn (GPS trong vùng, Face ID/liveness đạt nếu áp dụng), xem kết quả.
- **Kỳ vọng:** hiện rõ trạng thái thành công, không có nút giải trình/đăng ký lại.

### 2. Xem kết quả `pending_review` do ngoài geofence
- Check-in ngoài vùng geofence (xem #68 case 2), xem kết quả.
- **Kỳ vọng:** hiện trạng thái chờ duyệt, mục "vị trí" hiện rõ ngoài vùng; có nút "Cần bổ sung
  thông tin?".

### 3. Xem kết quả `pending_review` do Face ID fail
- Check-in Face ID sai người (xem #69 case 2), xem kết quả.
- **Kỳ vọng:** mục khuôn mặt hiện rõ chưa xác thực + điểm số (nếu có); nếu điểm số null, hiện thêm
  nút "Đăng ký lại Face ID".

### 4. Xác nhận KHÔNG có nút "thử check-in lại" tường minh trên màn kết quả
- Quan sát toàn bộ màn kết quả ở các case trên.
- **Kỳ vọng theo code hiện tại:** không có CTA "Check-in lại" trực tiếp tại đây — khớp đúng phát
  hiện, người dùng phải tự quay lại tab Chấm công.

### 5. Gửi giải trình từ màn kết quả
- Bấm "Cần bổ sung thông tin?" ở 1 case `pending_review`, gửi giải trình.
- **Kỳ vọng:** gửi thành công, không thuộc phạm vi sâu của kịch bản này (test kỹ hơn ở tính năng
  giải trình riêng nếu có).

---

## Ghi chú
Tính năng này rủi ro fail thấp — cách trình bày "lý do cụ thể qua các trường có cấu trúc" thực tế
tốt hơn 1 câu message chung, chỉ khác hình thức so với AC gốc. Trọng tâm khi test: case 4 (xác nhận
đúng thiếu sót nhỏ về UX — có thể cân nhắc bổ sung nút tắt "Thử lại" sau này nếu người dùng phàn
nàn, nhưng không cấp thiết).

**ĐÃ TEST (2026-08-17):** không sửa code — thiếu nút "thử lại" xác nhận là thiếu sót nhỏ, không
cấp thiết, để dành đợt sau nếu người dùng thực tế phàn nàn. Script `test_checkin_result.sh` 8/8
pass. Đã đóng — ĐÃ KHÓA.

---

# Gap chung cho cả nhóm #67-76: KHÔNG ghi audit log

Áp dụng cho MỌI tính năng trong nhóm này (#67-76) — không lặp lại riêng từng kịch bản:

- `CheckinService` (toàn bộ module Chấm công) hoàn toàn KHÔNG gọi `AuditLogService` ở bất kỳ đâu —
  check-in, check-out, tạo violation đều không để lại dấu vết trong Nhật ký audit, dù đây là hành
  động nhạy cảm ảnh hưởng trực tiếp tới tính lương/công.
- **Test case chung:** sau bất kỳ hành động check-in/check-out/tạo violation nào ở các kịch bản
  #68-75, vào Nhật ký audit tìm — kỳ vọng theo code hiện tại: KHÔNG có bản ghi nào.
- Đây là gap thật, nhất quán, nên cân nhắc vá 1 lần cho toàn module thay vì lẻ tẻ — action đề xuất:
  `checkin_submitted`, `checkout_submitted`, `checkin_violation_created` (hoặc tương đương).
