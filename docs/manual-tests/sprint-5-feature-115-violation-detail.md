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

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
**Hạ trạng thái từ "✅ đã xong" xuống "🟡 làm một phần"** do 2 gap MỚI audit gốc bỏ sót. Trọng tâm
khi test: case 2 (thiếu bằng chứng cho violation từ check-in thường — ảnh hưởng thực tế tới khả năng
HR xử lý tranh chấp cho loại vi phạm này) và case 3 (thiếu phân quyền hiển thị — cần quyết định
nghiệp vụ có thực sự cần ẩn bớt dữ liệu nhạy cảm theo vai trò hay hiện tại "ai có quyền đọc thì thấy
hết" đã đủ). Case 1 rủi ro fail thấp, đã có `test_hr_violation_detail.sh` phủ.
