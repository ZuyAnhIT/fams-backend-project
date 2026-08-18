# Kịch bản test thủ công — #102 Phản hồi mode chỉ vị trí

**Nền tảng: Backend, Mobile App.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG — "CheckResponseService.submit location_only". Đã xác nhận lại qua
code hiện tại — **ĐÚNG, không lỗi thời:**

- **`CheckResponseService.submit()`** — với `checkMode=location_only`: lấy `checkMode` từ snapshot
  (không phải config sống), verify vị trí đồng bộ qua PostGIS (`isPointWithinBufferedPolygon`),
  `faceVerified`/`livenessVerified` giữ nguyên `null` (đúng AC "face fields NULL"), `outcome`
  pass/fail CHỈ dựa trên kết quả vị trí.

---

## A. Test trên Backend
### 1. ✅ Phản hồi trong vùng geofence — `pass`
- **Kỳ vọng:** `outcome=pass`, `faceVerified=null`, `livenessVerified=null`.

### 2. ✅ Phản hồi ngoài vùng geofence — `fail`
- **Kỳ vọng:** `outcome=fail`, `failureReason` liên quan vị trí.

### 3. Không gửi kèm ảnh selfie (đúng vì mode không yêu cầu)
- **Kỳ vọng:** request không cần field ảnh, không lỗi validation.

## B. Test trên Mobile App
### 4. UI chỉ yêu cầu bật GPS, không yêu cầu chụp ảnh
- **Kỳ vọng:** màn phản hồi cho mode `location_only` không hiển thị bước chụp ảnh.

---

## ✅ PASS — ĐÃ KHÓA (2026-08-18)

Backend: `test_respond_check.sh` 23/23 PASS. Frontend: test live qua Expo Web thật (Playwright) —
mở panel phản hồi cho 1 check `location_only` thật, xác nhận UI chỉ hiện nút "Xác minh vị trí và
gửi", không có bất kỳ bước chụp ảnh/selfie nào trong panel — đúng thiết kế.

Không có gap cần vá.

## Ghi chú
`test_respond_check.sh` đã phủ phần backend cốt lõi.
