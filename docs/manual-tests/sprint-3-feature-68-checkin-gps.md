# Kịch bản test thủ công — #68 Check-in GPS cơ bản

**Nền tảng: Backend, Mobile App.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code hiện tại (`CheckinService.submitCheckin`)
— **đúng, không có gap nghiệp vụ, nhưng có vài nuance quan trọng cần biết khi test:**

- Validate: nhân viên active; site active; có assignment active hôm nay TẠI ĐÚNG site đó; không có
  check-in nào khác đang mở (chưa check-out) của cùng nhân viên (chặn ở cả tầng service lẫn unique
  index DB cho race condition).
- **Geofence: KHÔNG bắt buộc phải trong vùng mới check-in được** — nếu ở ngoài geofence, status
  chuyển `pending_review` (chờ HR duyệt) chứ KHÔNG bị chặn/từ chối hẳn. Nếu site CHƯA cấu hình
  geofence, mặc định coi như "trong vùng" (`insideGeofence=true`), không chặn.
- `gpsRiskScore`: tính điểm rủi ro dựa trên độ chính xác GPS (accuracy>100m: +0.7, >50m: +0.4,
  >20m: +0.2) và có ngoài geofence hay không (+0.5, tối đa 1.0) — không phải nhị phân đạt/không đạt.
- Gap chung cả module (không riêng #68): KHÔNG ghi audit log khi check-in — xem ghi chú cuối các
  kịch bản #67-76.

---

## A. Test trên Mobile App

### 1. Check-in GPS trong vùng geofence — happy path
- Tại site đã cấu hình geofence, đứng trong vùng (hoặc giả lập vị trí trong vùng), check-in.
- **Kỳ vọng:** thành công, status `valid`.

### 2. Check-in GPS ngoài vùng geofence
- Giả lập vị trí ngoài geofence của site, check-in.
- **Kỳ vọng theo code hiện tại:** VẪN tạo được check-in (không bị chặn cứng), nhưng status chuyển
  `pending_review` — không phải lỗi, là cờ chờ HR xem xét.

### 3. Check-in tại site chưa cấu hình geofence
- Với site không có geofence active, check-in bất kỳ vị trí nào.
- **Kỳ vọng:** thành công, `insideGeofence=true` mặc định, status `valid` (nếu không có vấn đề
  khác).

### 4. Check-in khi đang có 1 check-in khác chưa checkout
- Check-in xong (chưa checkout), thử check-in lần nữa (cùng site hoặc site khác).
- **Kỳ vọng:** bị chặn rõ ràng, không tạo bản ghi trùng.

### 5. Check-in tại site KHÔNG có assignment active hôm nay
- Thử check-in vào 1 site không nằm trong danh sách site được phép (xem #67).
- **Kỳ vọng:** bị chặn, thông báo rõ ràng.

### 6. GPS accuracy kém (độ chính xác thấp)
- Giả lập accuracy > 100m khi check-in trong vùng geofence.
- **Kỳ vọng:** vẫn tạo được check-in, nhưng `gpsRiskScore` cao hơn — không nhất thiết đổi status
  nếu vẫn trong geofence (rủi ro chỉ là 1 tín hiệu tham khảo cho HR, không tự động chặn).

### 7. Xác nhận gap "không ghi audit log khi check-in"
- Sau case 1, vào Nhật ký audit tìm hành động liên quan.
- **Kỳ vọng theo code hiện tại:** KHÔNG có bản ghi audit nào.

---

## Ghi chú
Trọng tâm khi test: case 2 (xác nhận đúng hành vi "ngoài geofence không bị chặn, chỉ chờ duyệt" —
dễ bị hiểu nhầm là bug nếu không biết trước).

**ĐÃ TEST + VÁ (2026-08-17):** case 7 (gap audit) — đã vá, thêm `AuditLogService` vào
`CheckinService`, action `checkin_submitted` ghi sau mỗi lần check-in thành công (online lẫn
offline sync). Xác nhận qua DB thật: chạy lại toàn bộ script tự động → 23 bản ghi audit mới xuất
hiện trong `audit_logs` với đúng action/entity/snapshot. Script `test_basic_checkin.sh` 11/11 pass,
không hồi quy. Đã đóng — ĐÃ KHÓA.
