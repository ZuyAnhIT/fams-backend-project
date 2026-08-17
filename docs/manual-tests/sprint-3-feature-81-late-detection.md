# Kịch bản test thủ công — #81 Tính đi muộn

**Nền tảng: Backend, Queue/AI/Automation.**

## ✅ ĐÃ FIX (2026-08-17) — ĐÃ KHÓA

Quyết định nghiệp vụ (project owner, 2026-08-17): **cần thêm grace period, mặc định 5 phút** cho mọi
ca (kể cả ca đã tồn tại từ trước, áp dụng hồi tố qua migration default — số liệu chấm công CŨ không
đổi vì late/early/OT luôn tính theo snapshot chụp lúc check-in, không tính lại từ Shift hiện tại).

### Thay đổi
- Migration `V101`: `shifts.grace_minutes INTEGER NOT NULL DEFAULT 5` (+ CHECK >= 0),
  `checkins.shift_grace_minutes INTEGER` (snapshot).
- `Shift.graceMinutes` — field mới.
- `CreateShiftRequest.graceMinutes` (default 5, `@Min(0)`) — **cố ý cho set ngay lúc tạo ca**, khác
  với `earlyCheckinMinutes`/`lateCheckoutMinutes` (chỉ set được qua `configure-ot` sau khi tạo), vì
  yêu cầu nghiệp vụ là mọi ca MỚI mặc định có ân hạn ngay từ đầu.
- `ConfigureShiftOtRequest.graceMinutes` (nullable, `@Min(0)`) — sửa sau qua `PATCH .../configure-ot`.
- `ShiftResponse.graceMinutes` — trả về trong mọi response.
- `CheckinRecord.shiftGraceMinutes` — snapshot chụp lúc check-in (cùng nguyên lý các field `shift_*`
  khác), dùng trong `AttendanceSummaryService.recompute()`.
- **Công thức mới:** trong ân hạn (`rawDelayMinutes <= graceMinutes`) → `isLate=false`,
  `lateMinutes=0`. Vượt ân hạn (`rawDelayMinutes > graceMinutes`) → `isLate=true`,
  `lateMinutes=rawDelayMinutes` (số phút trễ THẬT, KHÔNG trừ đi phần ân hạn — ân hạn chỉ quyết định
  CÓ bị gắn cờ muộn hay không, không giảm trừ số phút báo cáo).

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-17)

### 1. Check-in trong khoảng ân hạn (< graceMinutes phút muộn) — KHÔNG tính muộn
- Ca có `graceMinutes=5`, giờ bắt đầu = hiện tại - 3 phút. Check-in ngay bây giờ (muộn 3 phút, trong
  ân hạn 5 phút).
- **Kết quả thực tế:** `"late":false,"lateMinutes":0` — ĐÚNG.

### 2. Check-in vượt ân hạn — tính muộn với số phút TRỄ THẬT (không trừ ân hạn)
- Ca có `graceMinutes=5`, giờ bắt đầu = hiện tại - 10 phút. Check-in ngay bây giờ (muộn ~11 phút do
  độ trễ xử lý, vượt ân hạn 5 phút).
- **Kết quả thực tế:** `"late":true,"lateMinutes":11` — ĐÚNG (11, không phải 11-5=6 — xác nhận đúng
  quy tắc "báo trễ thật, ân hạn chỉ quyết định có gắn cờ hay không").

### 3. Ca cũ (tạo trước migration V101) — tự động có `graceMinutes=5`
- Xác nhận qua migration default `NOT NULL DEFAULT 5` — mọi ca hiện có được set 5 phút hồi tố, không
  cần HR phải sửa tay từng ca. Số liệu chấm công CŨ (đã tính trước đó) không bị tính lại.

### 4. `graceMinutes` hiển thị đúng trong response tạo ca / OT-config / danh sách ca
- Tạo ca mới với `graceMinutes:5` trong request → response trả về `"graceMinutes":5` — ĐÚNG
  (`ShiftService.toResponse()` đã được bổ sung field này, trước đó bị thiếu — đã fix).

### 5. Sửa giờ ca SAU KHI nhân viên đã check-in — không ảnh hưởng ngược
- Giữ nguyên hành vi snapshot đã test trước đó — không bị ảnh hưởng bởi thay đổi này.

---

## B. Cần test thủ công thêm (Web Admin / App)
- **Web Admin:** form tạo ca và form cấu hình OT hiện CHƯA có input cho `graceMinutes` — đang audit
  riêng (xem ghi chú dưới), nếu thiếu sẽ cần bổ sung UI để HR có thể chỉnh ân hạn qua giao diện thay
  vì chỉ qua API trực tiếp.
- Chưa test qua App di động (không có màn hình chỉnh sửa ca, không áp dụng).

## Regression
Toàn bộ `tests/attendance/*.sh` (9 suite) và `tests/site/test_create_shift.sh`,
`test_shift_ot_config.sh`, `test_update_shift.sh` — 100% PASS sau fix, không có regression.
