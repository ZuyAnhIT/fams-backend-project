# Kịch bản test thủ công — #91 Tạo cấu hình random check mặc định tenant

**Nền tảng: Backend, Web Admin.**

## ✅ ĐÃ FIX (2026-08-18) — ĐÃ KHÓA

Quyết định nghiệp vụ (project owner, 2026-08-18): **không cần đổi `checksPerShift` sang khoảng
min/max** — giữ 1 số cố định đã đủ dùng thực tế, tránh thay đổi schema lớn không cần thiết. **Chỉ
vá gap audit log.**

### Thay đổi
- `RandomCheckConfigService` — thêm `AuditLogService`, ghi audit cho ĐỦ CẢ 6 method mutate:
  `createTenantDefault`, `createSiteOverride`, `updateConfig`, `updateApplicableRoles`,
  `updateCheckMode`, `deleteConfig` (gap này ảnh hưởng chung cả module, vá 1 lần cho toàn bộ thay
  vì lặp lại riêng lẻ từng tính năng #91/#92).
- Action tương ứng: `random_check_config_created`, `random_check_config_updated`,
  `random_check_config_applicable_roles_updated`, `random_check_config_check_mode_updated`,
  `random_check_config_deleted` — đều ghi đầy đủ before/after (snapshot 9 field mutable).

### KHÔNG thay đổi (quyết định giữ nguyên)
- `checksPerShift` vẫn là 1 số cố định (1-10), KHÔNG đổi sang min/max.

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-18)

### 1. ✅ Tạo config mặc định tenant thành công
- `siteId=null`, field lưu đúng.

### 2. ✅ Chỉ 1 config mặc định/tenant — thử tạo lần 2
- Bị từ chối 409 (enforce kép: service check + DB unique index).

### 3. ✅ Validate tính khả thi lịch
- Cấu hình không khả thi (5 lần × 60 phút trong khung 2 tiếng) bị từ chối.

### 4. ✅ Xác nhận CÓ ghi audit log (đã fix)
- Tạo config mặc định qua API thật.
- **Kết quả thực tế:** `audit_logs` có bản ghi `random_check_config_created`, `new_value` chứa đầy
  đủ snapshot 9 field, `old_value=null` (đúng vì là tạo mới) — ĐÚNG.

---

## B. Test trên Web Admin — ✅ PASS, đã test live qua UI thật kết nối backend thật (Playwright, 2026-08-18)
- **Lưu ý quan trọng phát hiện trong lúc test:** tài khoản Platform Admin (`admin@fams.com`) bị
  chặn 403 khi vào trang "Kiểm tra ngẫu nhiên" dù backend cho phép (`callerIsPlatformAdmin` bypass)
  — do `RoleGuard` ở FE chỉ đọc permission literal, không biết khái niệm platform-admin bypass
  (đúng gotcha đã ghi nhận trước đây trong RBAC audit). Đã tạo 1 tài khoản TENANT_ADMIN thật để test
  — **đây không phải gap của #91, nhưng là 1 hạn chế UX có thật cho tài khoản Platform Admin khi
  thao tác trong ngữ cảnh 1 tenant cụ thể, nên ghi nhận lại.**
- Đăng nhập tài khoản TENANT_ADMIN, vào "Kiểm tra ngẫu nhiên" → tab "Cấu hình policy" → bấm "Tạo cấu
  hình mặc định" → modal hiện đúng 1 ô `checksPerShift` (không có ô min/max, khớp quyết định giữ
  nguyên) → điền form → bấm "Tạo cấu hình".
- **Kết quả thực tế:** toast "Đã tạo cấu hình mặc định công ty" hiện đúng, bảng hiển thị ngay giá
  trị vừa tạo (2 lần/ca, 08:00-17:00, Chỉ GPS...) — request `POST .../tenant-default` trả 201.
- Sửa `checksPerShift` từ 2 → 5 qua form "Sửa" → bấm "Lưu cấu hình" → bảng cập nhật đúng thành "5
  lần" ngay lập tức, `PUT .../{id}` trả 200.
- **Xác nhận audit log hiển thị đúng trên UI** (trang "Nhật ký audit"): 2 dòng
  `random_check_config_created`/`random_check_config_updated` xuất hiện đúng, đúng entity
  `RandomCheckConfig`. Mở "Xem" chi tiết dòng update → modal "So sánh dữ liệu trước và sau" hiển
  thị bảng diff CHÍNH XÁC từng field: `checksPerShift` đánh dấu "Thay đổi" (2 → 5), 8 field còn lại
  đánh dấu "Không đổi" — xác nhận đúng audit trail hoạt động end-to-end từ backend tới UI.

---

## Regression
Toàn bộ `tests/randomcheck/*.sh` (18 suite) — 100% PASS sau fix, không có regression.
