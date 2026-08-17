# Kịch bản test thủ công — #67 Hiển thị site được phép check-in

**Nền tảng: Backend, Mobile App.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code hiện tại
(`CheckinService.getAvailableSites`) — **đúng, không có gap, còn tốt hơn AC gốc mô tả:**

- Dùng `AssignmentService.resolveAvailableAssignmentsNow` — tính theo timezone CỦA TỪNG SITE (không
  phải giờ server mặc định), xử lý đúng ca qua đêm còn mở từ "hôm qua" theo giờ địa phương site.
  Nhân viên inactive → trả về danh sách rỗng (không phải lỗi).
- Mỗi site trả về: thông tin site, ca (start/end/allowOvernight/earlyCheckinMinutes/
  lateCheckoutMinutes), geofence (tọa độ + bufferMeters), `availabilityStatus`
  (`unrestricted|upcoming|open|closed`), `checkinAllowedFrom/Until`, `serverNow`, và
  `effectiveCheckinPolicy` (gps_only/gps_face/gps_face_liveness) — nhiều thông tin hơn AC gốc yêu
  cầu, giúp App tự hiển thị đếm ngược/trạng thái mà không cần gọi thêm API.
- Không ghi audit log (đúng, vì đây là endpoint đọc — không cần).

---

## A. Test trên Mobile App

### 1. Xem danh sách site được phép check-in hôm nay — happy path
- Đăng nhập nhân viên đã có phân công active hôm nay, vào tab Chấm công.
- **Kỳ vọng:** hiện đúng (các) site được phân công, đúng ca, đúng trạng thái khả dụng.

### 2. Nhân viên chưa có phân công nào hôm nay
- Đăng nhập nhân viên không có assignment active hôm nay.
- **Kỳ vọng:** danh sách rỗng, không lỗi.

### 3. Ca qua đêm còn mở từ hôm qua (theo giờ site, không phải giờ server)
- Với nhân viên có ca qua đêm (VD 22:00-06:00) bắt đầu "hôm qua" theo giờ site nhưng chưa kết
  thúc tại thời điểm test.
- **Kỳ vọng:** site đó vẫn hiện trong danh sách (`availabilityStatus=open`), không bị coi là đã hết
  hạn dù ngày dương lịch server đã sang ngày mới.
- **Ghi chú test:** cần chọn site có timezone khác server hoặc dàn thời gian test sát nửa đêm để
  tái hiện đúng — nếu môi trường test không tái hiện được, ghi "không tái hiện được" thay vì fail.

### 4. Site không được phân công KHÔNG hiện trong danh sách
- Với tenant có nhiều site, chỉ phân công nhân viên vào 1 site.
- **Kỳ vọng:** chỉ đúng 1 site đó hiện ra, các site khác không xuất hiện dù nhân viên có quyền xem.

### 5. `availabilityStatus` đúng theo từng mốc thời gian
- Trước giờ vào ca (còn xa) → `upcoming`; trong khung được phép check-in → `open`; sau khi hết hạn
  check-in (quá `earlyCheckinMinutes` trước ca và quá giờ kết ca) → `closed`.
- **Kỳ vọng:** đúng 3 trạng thái tương ứng đúng thời điểm.

---

## Ghi chú
Tính năng này đã xác nhận qua đọc code là hoạt động đúng và đầy đủ hơn AC gốc — rủi ro fail thấp
nhất trong cả 10 tính năng của đợt này. Trọng tâm khi test: case 3 (ca qua đêm theo site-timezone)
vì đây là logic phức tạp nhất, dễ có edge case chưa lường hết.

**ĐÃ TEST (2026-08-17):** không có gap, không sửa code. Script tự động `test_available_sites.sh`
chạy lại 6/6 pass, không hồi quy sau các thay đổi khác trong đợt này. Đã đóng — ĐÃ KHÓA.
