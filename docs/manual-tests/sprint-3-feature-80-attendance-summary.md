# Kịch bản test thủ công — #80 Tự động tạo attendance summary

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code hiện tại (`AttendanceSummaryService`)
— **đúng, không có gap thật, chỉ khác tên field so với AC:**

- **`calculated_at`: KHÔNG TỒN TẠI như 1 cột riêng** — dùng chung `updatedAt` (timestamp chuẩn của
  entity) để đóng vai trò này. Không phải thiếu sót, chỉ khác tên.
- **Upsert đúng theo tenant+employee+site+date**, tính đúng `firstCheckinAt`/`lastCheckoutAt`/
  `totalWorkMinutes`/`sessionCount`/`status`.
- **Chỉ tính các phiên check-in `status=valid`** — phiên `pending_review`/`rejected` bị loại khỏi
  tổng hợp nhưng vẫn được đánh dấu qua `hasPendingReviewSession`/`hasRejectedSession` (đã vá từ đợt
  audit trước, không phải gap mới).
- **Nhiều đường trigger tính lại:** sau mỗi lần checkout, khi giải quyết violation, cron đêm
  (`recomputeForDate`), job bắt kịp mỗi 2 tiếng trong ngày (`catchUpTodaySummaries`), và HR có thể
  tự bấm tính lại thủ công (`POST .../recompute`, đã tenant/site-scope đúng từ đợt audit trước).
- **Bản ghi đã bị HR "khóa" (đã điều chỉnh thủ công, có `adjustmentReason`) sẽ TỰ ĐỘNG BỎ QUA mọi
  lần tính lại tự động** — cho tới khi HR chủ động "Mở khóa và tính lại" (xem #84/HR-adjust).

---

## A. Test trên Backend/Web Admin

### 1. Tạo summary tự động sau khi checkout — happy path
- Nhân viên check-in rồi check-out hoàn chỉnh trong ngày.
- **Kỳ vọng:** ngay sau checkout, có 1 bản ghi attendance summary đúng ngày/site/nhân viên, đúng
  giờ vào/ra đầu-cuối, tổng phút làm việc.

### 2. Nhiều phiên check-in/out trong cùng ngày (nghỉ giữa ca rồi quay lại)
- Check-in/out 2 lần trong cùng 1 ngày tại cùng site.
- **Kỳ vọng:** `firstCheckinAt` = phiên đầu tiên, `lastCheckoutAt` = phiên cuối cùng, tổng phút =
  cộng dồn cả 2 phiên, `sessionCount=2`.

### 3. Phiên `pending_review` không được tính vào tổng
- Tạo 1 phiên check-in/out bị `pending_review` (VD: ngoài geofence), xem summary ngày đó.
- **Kỳ vọng:** phiên này KHÔNG cộng vào `totalWorkMinutes`, nhưng `hasPendingReviewSession=true` để
  HR biết có phiên cần xem lại.

### 4. HR tự bấm tính lại (`recompute`) thủ công
- Vào Web Admin, bấm tính lại cho 1 site/khoảng ngày cụ thể.
- **Kỳ vọng:** chỉ ảnh hưởng đúng phạm vi site/ngày đã chọn (tenant/site-scope đúng), không đụng
  tới site khác.

### 5. ✅ Bản ghi đã bị HR điều chỉnh thủ công không bị tính lại tự động
- Điều chỉnh thủ công 1 bản ghi (xem #84/HR-adjust), sau đó tạo thêm 1 phiên check-in/out mới trong
  cùng ngày để kích hoạt recompute tự động.
- **Kỳ vọng theo code hiện tại:** bản ghi giữ nguyên giá trị đã điều chỉnh, KHÔNG bị ghi đè bởi lần
  tính lại tự động — đúng thiết kế "khóa" bảo vệ số liệu HR đã can thiệp.

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
Trọng tâm khi test: case 5 (cơ chế khóa — quan trọng để đảm bảo can thiệp thủ công của HR không bị
mất khi có dữ liệu chấm công mới) và case 3 (đúng hành vi loại trừ phiên pending, tránh nhầm lẫn với
tính năng khác). Case 1-2, 4 rủi ro fail thấp.
