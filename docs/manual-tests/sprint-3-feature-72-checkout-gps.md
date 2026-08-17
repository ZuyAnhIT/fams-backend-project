# Kịch bản test thủ công — #72 Check-out GPS

**Nền tảng: Backend, Mobile App.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code (`submitCheckout`) — **đúng tinh thần
AC, nhưng mô hình dữ liệu khác — không có bảng/cột `paired_checkin_id` riêng:**

- **Check-out KHÔNG tạo bản ghi mới** — cùng 1 dòng `checkins` được cập nhật thêm các cột
  check-out (giờ ra, vị trí, kết quả xác thực...) chứ không phải 1 dòng riêng trỏ tới check-in qua
  `paired_checkin_id` như AC gốc ngụ ý. "Ghép đúng cặp" vì vậy không có nhập nhằng gì để lo — luôn
  đúng vì cùng 1 dòng.
- **App phải truyền đúng `checkinId` cụ thể để check-out** — không phải "tự tìm check-in đang mở"
  ngầm định, phải chỉ rõ ID.
- Trả về 403 nếu không phải chủ bản ghi, 409 nếu đã check-out rồi.
- **Policy áp dụng khi check-out là policy đã CHỤP LẠI (snapshot) lúc check-in, không phải policy
  hiện tại của site** — nếu HR đổi policy site giữa lúc nhân viên đang trong ca (đã check-in nhưng
  chưa check-out), check-out vẫn dùng đúng policy cũ đã áp dụng lúc vào ca. Đây là thiết kế có chủ
  đích (tránh nhân viên bị kẹt giữa ca khi chính sách đổi đột ngột), quan trọng cần test đúng.
- Geofence lúc check-out kiểm tra tương tự lúc check-in: ngoài vùng → chuyển `pending_review` (dùng
  update có điều kiện, tránh race với callback xác thực khuôn mặt đang chạy song song).

---

## A. Test trên Mobile App

### 1. Check-out trong vùng geofence — happy path
- Đã check-in, đứng trong vùng, check-out.
- **Kỳ vọng:** thành công, tính đúng `workMinutes`.

### 2. Check-out ngoài vùng geofence
- Đã check-in trong vùng, di chuyển ra ngoài geofence rồi check-out.
- **Kỳ vọng:** vẫn check-out được (không chặn cứng), nhưng status chuyển `pending_review`.

### 3. Check-out khi đã check-out rồi (double checkout)
- Gọi lại API check-out trên cùng 1 checkinId đã check-out.
- **Kỳ vọng:** 409, không cho check-out lần 2.

### 4. Check-out của người khác (không phải chủ bản ghi)
- Dùng tài khoản khác thử check-out trên checkinId của nhân viên A.
- **Kỳ vọng:** 403.

### 5. ✅ Xác nhận policy dùng đúng bản snapshot lúc check-in, không phải policy hiện tại
- Nhân viên check-in tại site đang có policy `gps_only`. Trong lúc nhân viên còn đang làm ca (chưa
  check-out), HR đổi policy site sang `gps_face_liveness`. Nhân viên check-out.
- **Kỳ vọng theo code hiện tại:** check-out KHÔNG yêu cầu Face ID/liveness (dùng đúng policy
  `gps_only` đã chụp lúc check-in) — xác nhận đúng thiết kế snapshot, không phải bug.

### 6. Check-out tại site không cấu hình geofence
- Site không có geofence active, check-out.
- **Kỳ vọng:** không bị ảnh hưởng, coi như trong vùng mặc định.

---

## Ghi chú
Trọng tâm khi test: case 5 — đây là hành vi thiết kế tinh tế, dễ bị hiểu nhầm là bug nếu tester
không biết trước ("tại sao đổi policy rồi mà check-out vẫn không yêu cầu Face ID?") — cần xác nhận
đúng ý đồ thiết kế trước khi báo cáo bất kỳ sai lệch nào ở đây. Case 1-4, 6 rủi ro fail thấp.

**ĐÃ TEST + VÁ (2026-08-17):** gap audit đã vá — thêm action `checkout_submitted`, xác nhận qua DB
thật. Case 5 (snapshot policy) xác nhận đúng qua đọc code, không đổi. Script `test_checkout.sh`
9/9 pass, không hồi quy. Đã đóng — ĐÃ KHÓA.
