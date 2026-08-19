# Kịch bản test thủ công — #129 Thông báo lỗi thân thiện

**Nền tảng: Mobile App.**

ℹ️ Audit gốc (07-22): ❌ CHƯA LÀM — không tìm thấy mapping `invalid_reason` sang tiếng Việt. Audit
lại code hiện tại (2026-08-18) xác nhận **audit gốc SAI/lỗi thời hoàn toàn** — không phải "chưa
làm gì", mà đã có hạ tầng mapping lỗi khá đầy đủ từ trước, chỉ thiếu đúng 2 điểm cụ thể trong AC:

- **✅ Mapping lỗi sang tiếng Việt: ĐÃ CÓ ĐẦY ĐỦ** — `parseCheckinError()`/`formatConflictMessage()`
  (check-in), `randomCheckErrorMessage()`/`FAILURE_LABELS` (random check), `formatOfflineSyncReason()`
  (đồng bộ offline) đều map lỗi sang tiếng Việt, bao phủ hầu hết mã lỗi backend.
- **✅ Gợi ý bật GPS: ĐÃ CÓ MỘT PHẦN** — `gps.service.ts` đã có thông báo "Vui lòng bật dịch vụ định
  vị (GPS)" khi GPS bị tắt ở tầng thiết bị.
- **❌ GAP thật #1: thiếu gợi ý hành động cụ thể theo nguyên nhân lỗi** — kết quả check-in/random
  check chỉ hiện nhãn pass/fail ("Ngoài phạm vi cho phép", "Không đạt"), không gợi ý "di chuyển
  gần công trình hơn" hay "chụp lại ảnh" như AC yêu cầu.
- **❌ GAP thật #2: không có nút "Liên hệ HR"** — chữ "liên hệ HR" chỉ xuất hiện dưới dạng text
  tĩnh trong toast, không phải nút bấm được.

## ✅ ĐÃ VÁ (2026-08-18)

- `CheckinResult.tsx`: thêm 2 card gợi ý mới, xuất hiện có điều kiện theo nguyên nhân:
  - "Di chuyển gần công trình hơn" khi `checkInInsideGeofence`/`checkOutInsideGeofence === false`.
  - "Chụp lại ảnh khuôn mặt" khi face/liveness fail CÓ điểm số (phân biệt với case cần đăng ký lại
    Face ID — heurstic embedding cũ — đã có card riêng từ trước, không trùng lặp).
  - Thêm nút "Liên hệ HR" cạnh "Viết giải trình", điều hướng tới màn Trợ giúp (`/help`, đã tồn tại
    sẵn trong app nhưng trước đây không route nào dẫn tới từ luồng chấm công lỗi).
- `RandomCheckResult.tsx`: thêm nút "Liên hệ HR" khi kết quả không đạt (không phải đang xử lý).

---

## A. Test trên Mobile App (không có thay đổi backend cho #129)

### 1. ✅ `tsc --noEmit` sạch sau khi thêm logic điều kiện mới

### 2. ⏳ CẦN BẠN TEST THỦ CÔNG trên thiết bị/simulator — các case gợi ý mới
- Check-in ngoài geofence → xác nhận card "Di chuyển gần công trình hơn" hiện đúng.
- Check-in với Face ID/liveness fail (có điểm số, không phải trường hợp cần đăng ký lại) → xác
  nhận card "Chụp lại ảnh khuôn mặt" hiện đúng, KHÔNG hiện đồng thời với card "Cần đăng ký lại
  Face ID" (2 case loại trừ nhau).
- Kết quả check-in/random check bất kỳ != "hợp lệ" → xác nhận nút "Liên hệ HR" hiện đúng, bấm vào
  điều hướng tới màn Trợ giúp.

---

## Ghi chú
Đây là tính năng chỉ có phía Mobile App (React Native/Expo) — Playwright không lái được nên chưa
test trực quan trong đợt này, cần bạn xác nhận trên thiết bị/simulator thật. Không có thay đổi
backend/API cho #129 — chỉ là logic hiển thị phía client dựa trên field đã có sẵn trong response.
