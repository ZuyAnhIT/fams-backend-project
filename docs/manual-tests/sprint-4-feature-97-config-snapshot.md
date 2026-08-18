# Kịch bản test thủ công — #97 Snapshot config khi sinh check

**Nền tảng: Backend, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG — "buildSnapshot config_snapshot". Đã xác nhận lại qua code hiện
tại — **ĐÚNG, không lỗi thời, đây là 1 trong số ít tính năng hoàn thành đúng và đủ ngay từ đầu:**

- **`ScheduledCheck.configSnapshot`** (jsonb) được `buildSnapshot` (trong
  `ScheduledCheckGeneratorService`) chụp lại NGAY LÚC SINH LỊCH — đầy đủ `checkMode,
  checksPerShift, minIntervalMinutes, allowedStartTime/EndTime, applicableRoles,
  responseWindowSeconds` — đúng các trường AC yêu cầu (verification_mode, threshold, response
  window, requirements — đặt tên khác nhưng đúng ý nghĩa).
- **✅ Xác nhận QUAN TRỌNG NHẤT: khi nhân viên phản hồi, hệ thống đọc từ SNAPSHOT, KHÔNG re-fetch
  config hiện tại** — `CheckResponseService.extractCheckMode(configSnapshot)` và
  `RandomCheckDispatchService.extractResponseWindow(check.getConfigSnapshot())` đều đọc trực tiếp
  từ `configSnapshot` đã lưu trên `ScheduledCheck`, không query lại `RandomCheckConfig` hiện hành.
  Đây chính xác là điều AC yêu cầu: "response validate theo snapshot, không theo config hiện tại" —
  đã làm ĐÚNG, không có bug live-refetch.

---

## A. Test trên Backend — Trọng tâm: xác nhận snapshot "đóng băng" đúng lúc

### 1. ✅ Snapshot chụp đủ các trường quan trọng lúc sinh lịch
- Sinh 1 scheduled_check, kiểm tra `configSnapshot` trong DB/response.
- **Kỳ vọng:** có đủ `checkMode, checksPerShift, minIntervalMinutes, allowedStartTime, allowedEndTime, applicableRoles, responseWindowSeconds`.

### 2. ✅ (Case quan trọng nhất) Đổi config SAU KHI đã sinh lịch — check cũ vẫn dùng snapshot cũ
- Sinh 1 scheduled_check với config A (`checkMode=location_only`). Sau đó HR đổi config sang
  `checkMode=location_face_liveness`. Nhân viên phản hồi scheduled_check đã sinh TRƯỚC KHI đổi.
- **Kỳ vọng:** hệ thống validate phản hồi theo `checkMode=location_only` (snapshot cũ), KHÔNG bắt
  nhân viên phải chụp Face ID/liveness dù config hiện tại đã đổi — xác nhận đúng nguyên tắc
  "snapshot bất biến", tránh tình huống nhân viên bị áp luật mới cho 1 lượt kiểm tra đã lên lịch từ
  trước khi luật đổi.

### 3. ✅ `responseWindowSeconds` dùng đúng snapshot, không phải config hiện tại
- Tương tự case 2 nhưng đổi `responseWindowSeconds` sau khi đã sinh lịch.
- **Kỳ vọng:** thời hạn phản hồi tính theo giá trị TẠI THỜI ĐIỂM SINH LỊCH, không theo giá trị mới.

---

## ✅ PASS — ĐÃ KHÓA (2026-08-18)
Không có gap cần vá — xác nhận qua `tests/randomcheck/test_config_snapshot.sh` (16/16 pass) và toàn
bộ regression suite sau các fix của #99/#100. Nguyên tắc snapshot bất biến không bị ảnh hưởng bởi
các thay đổi đó (chỉ thêm field mới cho cancel/dispatch traceability, không đụng tới configSnapshot).
