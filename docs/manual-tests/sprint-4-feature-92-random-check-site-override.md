# Kịch bản test thủ công — #92 Tạo cấu hình override theo site

**Nền tảng: Backend, Web Admin.**

## ✅ ĐÃ FIX (2026-08-18) — ĐÃ KHÓA

Gap audit log đã vá cùng đợt với #91 (chung `RandomCheckConfigService`, xem chi tiết thay đổi ở
`sprint-4-feature-91-random-check-tenant-default.md`).

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-18)

### 1. ✅ Tạo override cho 1 site thành công
- `siteId` đúng site đã chọn.

### 2. ✅ Chỉ 1 override/site — thử tạo lần 2 cho CÙNG site
- Bị từ chối 409.

### 3. ✅ `getEffectiveConfig` ưu tiên đúng override > mặc định
- Site có override dùng override, site không có override dùng mặc định tenant.

### 4. ✅ Tắt override (`is_active=false`) — site quay lại dùng mặc định
- Đúng như thiết kế.

### 5. ✅ Supervisor chỉ 1 site — không tạo được override cho site khác
- Bị từ chối 403.

### 6. ✅ Xác nhận CÓ ghi audit log (đã fix)
- Tạo override qua API thật.
- **Kết quả thực tế:** `audit_logs` có bản ghi `random_check_config_created` với `entity_id` đúng
  ID config vừa tạo cho site — ĐÚNG.

---

## B. Test trên Web Admin — ✅ PASS, đã test live qua UI thật kết nối backend thật (Playwright, 2026-08-18)
- Vào chi tiết công trình (site "HQ") → tab "Kiểm tra ngẫu nhiên": ban đầu hiển thị đúng banner
  "Đang kế thừa policy mặc định công ty · TENANT DEFAULT" kèm giá trị của config mặc định
  (`checksPerShift=5`, giá trị vừa sửa ở #91) — xác nhận `getEffectiveConfig` fallback đúng khi
  site chưa có override.
- Bấm "Tùy chỉnh riêng cho site" → tạo override → **kết quả thực tế:** `POST .../random-check-
  configs/sites/{siteId}` trả 201, tab tự động load lại và hiển thị config MỚI (không còn banner
  "kế thừa mặc định") — xác nhận override được ưu tiên đúng ngay sau khi tạo, khớp đúng logic
  `getEffectiveConfig`.
- **Xác nhận audit log:** `audit_logs` có thêm 1 dòng `random_check_config_created` mới với
  `entity_id` đúng ID của site override vừa tạo qua UI — tổng cộng 4 bản ghi audit (1 create + 2
  update từ #91, 1 create từ #92) đều xuất phát từ thao tác UI thật, không phải gọi API tay.

---

## Regression
Toàn bộ `tests/randomcheck/*.sh` (18 suite) — 100% PASS sau fix, không có regression.
