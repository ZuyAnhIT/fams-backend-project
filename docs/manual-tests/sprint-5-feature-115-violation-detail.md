# Kịch bản test thủ công — #115 HR xem chi tiết violation

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG — "getViolationDetail". Đã xác nhận lại qua code hiện tại — **CẦN
HẠ TRẠNG THÁI xuống 🟡 LÀM MỘT PHẦN — đúng phần lớn, nhưng có 2 gap thật audit gốc bỏ sót:**

- **✅ Xác nhận: KHÔNG PHẢI response trần trụi** — dù entity `Violation` không có field
  `details`/`metadata` JSON (đã xác nhận ở #107), `getViolationDetail` JOIN THẬT sang
  `ScheduledCheck` (giờ dự kiến/hết hạn/status) VÀ `CheckResponse` (GPS, ảnh selfie, điểm liveness,
  outcome/lý do fail) — đủ để HR xem bằng chứng cho violation PHÁT SINH TỪ RANDOM CHECK.
- **❌ GAP thật, MỚI phát hiện: violation phát sinh từ CHECK-IN THƯỜNG (loại
  `face_verify_timeout`) KHÔNG có bằng chứng đi kèm** — response chỉ trả về `checkinId` dạng UUID
  TRẦN, KHÔNG join sang `CheckinRecord` để lấy ảnh/GPS như đã làm với random-check response. HR xem
  chi tiết loại violation này sẽ KHÔNG thấy ảnh/vị trí, dù AC yêu cầu rõ "checkin ... liên quan".
- **❌ GAP thật, MỚI phát hiện: "ẩn dữ liệu theo quyền" KHÔNG được cài đặt** — bất kỳ ai có quyền
  đọc violation (`violations:read`) đều thấy TOÀN BỘ dữ liệu kể cả ảnh selfie/GPS chi tiết, không
  có phân tầng theo vai trò (VD Supervisor chỉ nên thấy tóm tắt, HR mới thấy đầy đủ ảnh nhạy cảm).

---

## A. Test trên Backend

### 1. ✅ Chi tiết violation từ RANDOM CHECK — đủ bằng chứng
- Xem chi tiết 1 violation loại `location_fail`/`face_fail`/`liveness_fail`/`no_response`.
- **Kỳ vọng:** có đủ thông tin scheduled check (giờ dự kiến/hết hạn) + response (GPS/ảnh/liveness).

### 2. ❌ Chi tiết violation từ CHECK-IN THƯỜNG (`face_verify_timeout`) — thiếu bằng chứng
- Xem chi tiết 1 violation loại này.
- **Kỳ vọng theo code hiện tại:** chỉ có `checkinId` (UUID trần), KHÔNG có ảnh/GPS đi kèm — xác
  nhận đúng gap mới phát hiện.

### 3. ❌ Xác nhận gap "không ẩn dữ liệu theo quyền"
- Đăng nhập với 2 vai trò khác nhau (VD HR đầy đủ quyền vs Supervisor quyền hạn chế), cùng xem 1
  violation.
- **Kỳ vọng theo code hiện tại:** cả 2 thấy CÙNG một lượng thông tin, không có sự khác biệt — xác
  nhận đúng gap.

---

## B. Test trên Web Admin
- Modal/trang chi tiết vi phạm: xác nhận hiển thị đúng dữ liệu backend trả về, xem ảnh selfie/GPS
  cho violation từ random check.

---

## ✅ ĐÃ VÁ (2026-08-18) — ĐÃ KHÓA

### Case 2 — gap thiếu bằng chứng cho violation từ check-in thường: ĐÃ VÁ
- `ViolationDetailResponse` thêm field `checkin` (`CheckinEvidence`) — join qua `checkinId` khi có,
  gồm `status`, `checkInAt`, GPS (lat/lon/accuracy), `checkInInsideGeofence`, `faceVerified`,
  `livenessVerified`, `faceVerifyScore`. Cùng mức chi tiết `checkResponse` đã có cho random-check.
- Web Admin (`ViolationDetailModal.tsx`): thêm block "Bằng chứng check-in" hiển thị đầy đủ các
  field trên khi `data.checkin` có giá trị.

### Test live — ✅ PASS (2026-08-18)
Tạo 1 checkin thật qua API, gắn 1 violation `face_verify_timeout` vào checkin đó. Xem chi tiết
violation trả đúng đầy đủ block `checkin` (status=valid, GPS đúng tọa độ, checkInInsideGeofence=
true, faceVerified/livenessVerified=null vì site không yêu cầu Face ID).

### Case 3 — gap "không ẩn dữ liệu theo quyền": ĐÃ HỎI NGƯỜI DÙNG, quyết định GIỮ NGUYÊN
Người dùng xác nhận không cần phân tầng hiển thị theo vai trò — ai có `violations:read` vẫn thấy
đầy đủ dữ liệu (ảnh selfie, GPS chi tiết) như hiện tại. Không thay đổi gì thêm cho case này.

## Ghi chú
Regression: 31/31 (bao gồm `test_hr_violation_detail.sh`).
