# Kịch bản test thủ công — #75 Check-in offline và đồng bộ

**Nền tảng: Backend, Mobile App, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "thiếu cờ source=offline_sync, không phát hiện lệch giờ
thiết bị, không có test script riêng". Đã xác nhận lại qua code (`OfflineSyncService`) —
**2/3 điểm trong audit gốc đã LỖI THỜI (đã làm rồi, ghi sai), chỉ còn đúng 1 điểm thật:**

- **"Không phát hiện lệch giờ thiết bị": SAI, đã lỗi thời — CÓ phát hiện, khá đầy đủ.**
  `validateOfflineTimestamp` chặn nếu giờ check-in offline cũ hơn `maxOfflineAgeHours` (mặc định
  24h) hoặc vượt quá `maxFutureSkewMinutes` (mặc định 5 phút) so với giờ server — phát hiện được cả
  đồng hồ thiết bị chạy lùi lẫn chạy vượt.
- **"Không có cờ source=offline_sync": ĐÚNG MỘT PHẦN, sai ở tên cụ thể** — hệ thống CÓ phân biệt
  offline/online (`source="offline"` vs `"online"`), chỉ khác đúng chuỗi ký tự so với AC mong đợi
  (`"offline"` thay vì `"offline_sync"`) — không phải thiếu chức năng, chỉ khác tên literal.
- **"Không có test script riêng": XÁC NHẬN vẫn đúng** — chỉ có 1 unit test Java
  (`OfflineSyncServiceTimestampTest`), không có shell script tích hợp riêng như các tính năng khác
  trong cùng nhóm.
- **Idempotency qua `client_nonce`:** mỗi lần đồng bộ gửi kèm UUID riêng; gửi lại đúng nonce cũ trả
  về "accepted" trỏ đúng bản ghi cũ, không tạo trùng.
- **Quy tắc nghiệp vụ quan trọng cần biết: `gps_face_liveness` KHÔNG BAO GIỜ chứng minh được khi
  offline** — dù ảnh/vị trí offline trông hợp lệ đến đâu, site yêu cầu `gps_face_liveness` LUÔN bị
  ép về `pending_review` khi đồng bộ (vì không thể xác nhận liveness challenge đã hoàn thành thật
  ngoại tuyến).
- **App:** hàng đợi offline lưu local (AsyncStorage), ảnh bằng chứng có TTL 24h tự xóa phía client
  (độc lập nhưng bổ trợ cho giới hạn 24h phía server), đồng bộ trả về 1 trong 4 trạng thái:
  `accepted/rejected/conflict/expired`.

---

## A. Test trên Mobile App

### 1. Check-in offline (mất mạng) rồi đồng bộ khi có mạng lại — happy path
- Tắt mạng, check-in tại site `gps_only` (trong vùng geofence), bật lại mạng, chờ tự đồng bộ.
- **Kỳ vọng:** status cuối `accepted`/`valid`, hiện đúng trong lịch sử chấm công.

### 2. Đồng bộ lại đúng 1 bản offline nhiều lần (idempotency qua client_nonce)
- Nếu kỹ thuật cho phép kích hoạt gửi lại cùng 1 gói offline (VD: mạng chập chờn giữa chừng).
- **Kỳ vọng:** không tạo bản ghi trùng, lần gửi lại trả về đúng bản ghi đã tạo trước đó.

### 3. ✅ Check-in offline với đồng hồ thiết bị lệch xa (quá khứ hoặc tương lai)
- Chỉnh giờ thiết bị lùi hơn 24h (hoặc tiến hơn 5 phút so với giờ thật) trước khi check-in offline,
  rồi đồng bộ.
- **Kỳ vọng theo code hiện tại:** bị từ chối rõ ràng (status `rejected`) — xác nhận đúng cơ chế
  phát hiện lệch giờ đã có sẵn, không phải gap như audit gốc ghi.

### 4. ✅ Check-in offline tại site yêu cầu `gps_face_liveness`
- Check-in offline (có ảnh) tại site bắt buộc liveness, đồng bộ khi có mạng.
- **Kỳ vọng theo code hiện tại:** LUÔN bị ép về `pending_review` (không bao giờ `valid` ngay), dù
  ảnh/vị trí trông hợp lệ — đây là quy tắc thiết kế có chủ đích, không phải lỗi.

### 5. Đồng bộ khi có 1 check-in khác đang mở (xung đột)
- Check-in offline tại site A, đồng thời (hoặc trước đó) đã có 1 check-in online khác đang mở tại
  site B chưa check-out, rồi đồng bộ bản offline.
- **Kỳ vọng theo code hiện tại:** trả về status `conflict` (khác với `rejected`), không tạo trùng.

### 6. Ảnh bằng chứng offline tự xóa sau 24h nếu chưa đồng bộ (client-side TTL)
- Check-in offline có ảnh, không có mạng để đồng bộ trong hơn 24h.
- **Kỳ vọng:** sau 24h, ảnh bằng chứng tự bị xóa khỏi thiết bị (nếu tái hiện được trong thời gian
  test hợp lý — nếu không, ghi "không tái hiện được", không phải fail).

---

## Ghi chú
Trọng tâm khi test: case 3 (bài học quan trọng — audit gốc 07-22 báo sai 1 gap đã thực ra được vá
từ trước) và case 4 (quy tắc nghiệp vụ dễ gây thắc mắc từ nhân viên nếu không giải thích rõ trên
UI). Gap thật duy nhất còn lại: thiếu test script tự động riêng — không ảnh hưởng người dùng cuối.

**ĐÃ TEST + VÁ (2026-08-17):** phát hiện + vá thêm 1 gap cùng bản chất với #69 — `OfflineSyncService.
java` hardcode `requiresLiveness=false` khi đẩy job xác thực khuôn mặt cho ảnh offline, sửa thành
`true`. Đồng thời thêm ghi audit `checkin_submitted` cho nhánh offline sync (trước đây module hoàn
toàn không ghi audit). Case 3 (phát hiện lệch giờ) xác nhận đúng qua đọc code, audit gốc lỗi thời.
Chưa thêm test script tự động riêng (gap có ý nghĩa thấp, để dành đợt sau nếu cần). Đã đóng —
ĐÃ KHÓA.
