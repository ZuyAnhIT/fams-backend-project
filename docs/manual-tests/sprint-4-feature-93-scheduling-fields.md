# Kịch bản test thủ công — #93 Cấu hình số lần và khung giờ check

**Nền tảng: Backend, Web Admin.**

## ✅ ĐÃ FIX (2026-08-18) — ĐÃ KHÓA

Quyết định nghiệp vụ (project owner, 2026-08-18): **không cần đổi `checksPerShift` sang min/max,
không cần thêm nhiều khung giờ/ngày** — giữ nguyên thiết kế hiện tại (1 số cố định, 1 khung liên
tục). Gap audit log đã vá cùng đợt với #91/#92.

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-18)

### 1. ✅ Validate `end > start`
- Bị từ chối 400 nếu `allowedEndTime <= allowedStartTime`.

### 2. ✅ Validate tính khả thi lịch — từ chối khung giờ không đủ chỗ
- Bị từ chối 400.

### 3. ✅ `checksPerShift` ngoài khoảng 1-10 — bị từ chối
### 4. ✅ `minIntervalMinutes` âm — bị từ chối
### 5. ✅ Xác nhận CÓ ghi audit log khi cập nhật scheduling fields (đã fix)
- `PUT .../random-check-configs/{id}` đổi `checksPerShift`.
- **Kết quả thực tế:** `audit_logs` có bản ghi `random_check_config_updated`, `old_value`/
  `new_value` phản ánh đúng giá trị trước/sau — ĐÚNG.

---

## B. Test trên Web Admin — ✅ PASS, đã test live qua UI thật kết nối backend thật (Playwright, 2026-08-18)
- Modal tạo/sửa cấu hình (dùng chung với #91): xác nhận đúng chỉ 1 ô "Số lần kiểm tra mỗi ca", 1 ô
  "Khoảng cách tối thiểu (phút)", 1 cặp "Bắt đầu/Kết thúc khung giờ" — khớp đúng quyết định giữ
  nguyên (không có ô min/max, không có nhiều khung giờ).
- Sửa `checksPerShift` qua UI (2 → 5) — bảng cập nhật đúng ngay, audit log ghi đúng field thay đổi
  (xem chi tiết ở `sprint-4-feature-91-random-check-tenant-default.md`, cùng 1 modal dùng chung).

---

## Regression
Toàn bộ `tests/randomcheck/*.sh` (18 suite) — 100% PASS sau fix, không có regression.
