# Kịch bản test thủ công — #83 Tính OT

**Nền tảng: Backend, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code hiện tại — **cơ chế tính KHÁC HẲN AC mô
tả (không dùng `standard_hours_per_day`), nhưng hoạt động đúng và có thêm tính năng tốt hơn AC:**

- **AC gốc SAI về cơ chế: KHÔNG so `total_work_minutes` với `standard_hours_per_day`** — field này
  không tồn tại ở đâu cả. Công thức thật: **OT = số phút làm việc VƯỢT QUÁ giờ kết thúc ca**, giới
  hạn trần bởi `shiftEnd + lateCheckoutMinutes` (đã test ở #73 — dùng chung logic với check-out
  muộn, không phải 1 công thức OT riêng biệt).
- **Chỉ tính khi `allowOvertime=true`** (từ snapshot Shift lúc check-in) — đúng như AC.
- **Tính năng tốt hơn AC gốc: cảnh báo vượt giới hạn OT ngày/tuần** (`otDailyLimitExceeded`/
  `otWeeklyLimitExceeded`, dựa trên `Shift.maxOtMinutesPerDay`/`maxOtMinutesPerWeek` đã test ở #60)
  — chỉ CẢNH BÁO cho HR xem, KHÔNG tự động cắt bớt `otMinutes` đã tính. Giới hạn tuần cộng dồn theo
  tuần ISO (Thứ 2 - Chủ nhật), tính theo TỪNG NHÂN VIÊN (không phải theo site).
- Dùng snapshot giờ ca/OT config đã chụp lúc check-in, không lấy lại Shift hiện tại.

---

## A. Test trên Backend

### 1. Làm việc trong giờ ca, không có OT — happy path
- Check-out đúng hoặc trước giờ kết thúc ca.
- **Kỳ vọng:** `otMinutes=0`.

### 2. Làm thêm giờ tại ca cho phép OT (`allowOvertime=true`)
- Check-out muộn 30 phút so với giờ kết thúc ca, trong giới hạn `lateCheckoutMinutes`.
- **Kỳ vọng:** `otMinutes=30`.

### 3. Làm thêm giờ tại ca KHÔNG cho phép OT (`allowOvertime=false`)
- Check-out muộn 30 phút tại ca có `allowOvertime=false`.
- **Kỳ vọng:** `otMinutes=0` — dù có làm thêm thực tế, ca không cho phép ghi nhận OT.

### 4. Làm thêm vượt quá giới hạn trần (`shiftEnd + lateCheckoutMinutes`)
- Check-out muộn vượt xa giới hạn cho phép.
- **Kỳ vọng:** `otMinutes` chỉ tính tới đúng giới hạn trần, phần vượt thêm không được tính — khớp
  đúng với kết quả đã test ở #73 case 3.

### 5. ✅ Vượt giới hạn OT ngày — chỉ cảnh báo, không cắt bớt
- Cấu hình `maxOtMinutesPerDay=60` cho ca, làm OT thực tế 90 phút (trong giới hạn trần
  lateCheckoutMinutes).
- **Kỳ vọng theo code hiện tại:** `otMinutes=90` (KHÔNG bị cắt về 60), nhưng
  `otDailyLimitExceeded=true` để HR biết mà xem lại — không tự động chặn hay giảm số liệu.

### 6. ✅ Vượt giới hạn OT tuần — cộng dồn theo tuần ISO, theo từng nhân viên
- 1 nhân viên làm OT nhiều ngày trong cùng tuần (Thứ 2 - Chủ nhật), tổng vượt `maxOtMinutesPerWeek`.
- **Kỳ vọng:** `otWeeklyLimitExceeded=true` từ ngày làm OT khiến tổng tuần vượt ngưỡng trở đi; cộng
  dồn đúng theo đúng nhân viên đó, không lẫn với nhân viên khác dù cùng site/ca.

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
Trọng tâm khi test: case 5-6 (2 tính năng cảnh báo OT tốt hơn AC gốc, cần xác nhận đúng bản chất
"chỉ cảnh báo không chặn" để không báo nhầm là thiếu tính năng chặn cứng) và case 3-4 (2 nhánh quan
trọng của công thức thật, khác AC gốc — cần ghi rõ trong tài liệu để tránh audit sai ở đợt sau, đã
từng xảy ra hiểu nhầm tương tự ở #55). Case 1-2 rủi ro fail thấp.
