# Kịch bản test thủ công — #94 Cấu hình mode kiểm tra

**Nền tảng: Backend, Web Admin.**

## ✅ ĐÃ FIX (2026-08-18) — ĐÃ KHÓA

Quyết định nghiệp vụ (project owner, 2026-08-18): **không cần thêm threshold GPS/face riêng theo
tenant/site** — ngưỡng mặc định hệ thống đã đủ dùng. Gap audit log đã vá cùng đợt với #91-93.

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-18)

### 1. ✅ Tạo/sửa config với từng giá trị `checkMode` hợp lệ
- Cả 3 giá trị (`location_only`/`location_face`/`location_face_liveness`) lưu thành công.

### 2. ✅ Giá trị `checkMode` không hợp lệ — bị từ chối 400
### 3. ✅ "Liveness luôn kèm face" đúng theo thiết kế enum (không đổi)
### 4. ✅ Xác nhận CÓ ghi audit log khi đổi `checkMode` (đã fix)
- `PUT .../random-check-configs/{id}/check-mode` đổi từ `location_face` → `location_face_liveness`.
- **Kết quả thực tế:** `audit_logs` có bản ghi `random_check_config_check_mode_updated`, đúng
  `checkMode` cũ/mới — ĐÚNG.

---

## B. Test trên Web Admin — ✅ PASS, đã test live qua UI thật kết nối backend thật (Playwright, 2026-08-18)
- Modal tạo/sửa cấu hình: dropdown "Phương thức xác minh" hiển thị đúng đủ 3 lựa chọn (mặc định
  "Chỉ GPS — xác nhận trong geofence"), không có input threshold GPS/face riêng — khớp đúng quyết
  định giữ nguyên.
- Bảng hiển thị đúng badge "Chỉ GPS" sau khi tạo — xác nhận field `checkMode` hiển thị đúng trên UI
  (cùng modal dùng chung với #91/#93, xem chi tiết ở
  `sprint-4-feature-91-random-check-tenant-default.md`).

---

## Regression
Toàn bộ `tests/randomcheck/*.sh` (18 suite) — 100% PASS sau fix, không có regression.
