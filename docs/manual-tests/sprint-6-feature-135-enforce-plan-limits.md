# Kịch bản test thủ công — #135 Enforce giới hạn gói

**Nền tảng: Backend, Web Admin, Mobile App.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — bằng chứng: `PlanLimitEnforcementService`,
`test_plan_limits.sh`; thiếu: export chưa được kiểm tra limit; không ghi audit denied.

## 🔍 Phát hiện quan trọng: enforcement core KHÔNG hề có bug — sự cố môi trường tự gây ra
Trong lúc xử lý 1 yêu cầu khác (fix gấp 2 gap "tồn đọng"), `tests/tenant/test_plan_limits.sh` bất
ngờ FAIL (tạo nhân viên/site vượt hạn mức trial vẫn trả 201 thay vì 422). Điều tra kỹ phát hiện
**không phải bug** — `PlanLimitEnforcementService.assertEmployeeLimit`/`assertSiteLimit` đã
implement đúng, gọi đúng chỗ (`EmployeeService`, `SiteService`, `EmployeeInvitationService` cả lúc
gửi lẫn lúc accept), map đúng HTTP 422 từ trước. Nguyên nhân thật: **dữ liệu gói `trial` trong DB
bị lệch** (max_employees/max_sites = 8/3 thay vì 5/1 seed gốc) do 1 lần `kill -9` khẩn cấp lúc
khôi phục môi trường cắt ngang `tests/subscription/test_plan_limits.sh` giữa chừng, khiến cơ chế
tự khôi phục dữ liệu (`trap restore_trial_limits EXIT`) của chính test đó không kịp chạy. Đã
restore lại dữ liệu đúng — xem chi tiết đầy đủ trong `docs/BACKLOG.md` mục 7. **Không có thay đổi
code nào cho phần này** — chỉ là background/context của đợt #131-135.

## Audit lại 2 gap còn lại (2026-08-19)

- **❌ GAP thật: không ghi audit khi bị từ chối (denied).** Xác nhận đúng — `PlanLimitEnforcementService`
  chỉ throw exception, không có bất kỳ lời gọi audit nào.
- **🟡 "Export chưa được kiểm tra limit": không có field limit riêng cho export trong
  `PlanLimits`** (chỉ có max_employees/max_sites/max_storage_gb/max_random_checks_per_month) —
  không có khái niệm "export limit" nào tồn tại để enforce. Sau khi #134 xây xong storage
  tracking, đã cân nhắc gắn export vào `max_storage_gb`, nhưng export là thao tác tức thời
  (generate + stream file, không lưu trữ) nên không thực sự "tiêu tốn storage" — gắn vào đây sẽ
  gượng ép, không đúng bản chất. Ban đầu ghi nhận chưa vá, chờ quyết định nghiệp vụ — **đã vá ở
  đợt 2 cùng ngày, xem mục dưới**.

## ✅ ĐÃ VÁ (2026-08-19, đợt 1)
Thêm audit `PLAN_LIMIT_DENIED` — tập trung logic ghi audit ngay trong
`PlanLimitEnforcementService` (không lặp lại try/catch ở từng nơi gọi) bằng cách thêm tham số
`actorUserId` vào cả 3 hàm `assertEmployeeLimit`/`assertSiteLimit`/`assertRandomCheckLimit`, ghi
audit ngay trước khi throw exception. Best-effort, không chặn exception thật hiển thị cho người
dùng nếu ghi audit lỗi.

## ✅ ĐÃ VÁ (2026-08-19, đợt 2) — Export limit
Người dùng yêu cầu xử lý ngay gap "export chưa có limit check" thay vì để chờ quyết định nghiệp
vụ ở đợt sau. Quyết định tự đưa ra (không hỏi lại — user đã chỉ định "fix now"): thêm field mới
thay vì gắn vào field có sẵn (không field nào phù hợp về bản chất, như phân tích ở trên).

- **Migration V107**: thêm cột `plan_limits.max_exports_per_month` (nullable = unlimited). Seed
  theo đúng tỷ lệ đã có của `max_random_checks_per_month` (cùng dạng "hành động tốn tài nguyên
  theo tháng" đã có tiền lệ trong schema): trial=10, basic=100, pro=1000, enterprise=NULL.
- **Đếm usage bằng cách tái dùng audit log `EXPORT_*` sẵn có** (`EXPORT_ATTENDANCE`,
  `EXPORT_VIOLATIONS`, `EXPORT_FACE_ID_NOT_ENROLLED` — đã được ghi từ #124/#132/#127) thay vì
  thêm bảng đếm riêng — query mới `AuditLogRepository.countExportsByTenantInRange` dùng
  `action LIKE 'EXPORT\_%'`.
- **`PlanLimitEnforcementService.assertExportLimit(tenantId, actorUserId)`** — theo đúng khuôn
  mẫu `assertRandomCheckLimit`: bỏ qua nếu limit null (unlimited), đếm export trong tháng hiện
  tại, throw `PlanLimitExceededException` (422) + ghi `PLAN_LIMIT_DENIED` nếu đã đạt/vượt.
- Gọi `assertExportLimit` ở cả 3 export endpoint trong `ReportService`:
  `exportMonthlyAttendance`, `exportViolations`, `exportFaceIdNotEnrolled` — ngay sau bước check
  quyền, trước khi build file.
- `UpdatePlanLimitsRequest`/`PlanLimitsResponse`/`PlanLimits` entity đều thêm field
  `maxExportsPerMonth` (+ `clearMaxExportsPerMonth` trên request) theo đúng pattern 4 field limit
  đã có.

---

## A. Test trên Backend

### 1. ✅✅✅ (Case quan trọng nhất, đã từng nghi ngờ là bug) Enforce đúng, ghi đúng audit denied
- Setup: tenant trial (max_employees=5), tạo đủ 5 nhân viên qua API trực tiếp (không qua lời
  mời — lời mời chưa accept KHÔNG tính vào số lượng).
- **Kỳ vọng — xác nhận đúng qua live API call:** tạo nhân viên thứ 6 → HTTP 422
  `PLAN_LIMIT_EXCEEDED`; query `audit_logs` thấy đúng dòng `action='PLAN_LIMIT_DENIED'`,
  `entity_type='PlanLimit'`, `new_value` chứa `limitType='employee'`.

### 2. ✅ Export limit — enforce đúng, ghi đúng audit denied (đợt 2, 2026-08-19)
- Setup: tạo tenant mới (`ExportLimit Corp`) trên gói trial qua `POST /tenants`; đặt tạm
  `maxExportsPerMonth=1` trên gói trial qua `PATCH /plans/{trial}/limits` (giữ nguyên 4 field
  còn lại từ giá trị gốc để tránh side-effect như bài học từ sự cố `kill -9` ở trên).
- Gọi `GET /tenants/{id}/reports/violations/export` lần 1 → **HTTP 200**, file xlsx trả về bình
  thường (chưa vượt limit, 0→1).
- Gọi lại lần 2 (cùng tháng) → **HTTP 422** `PLAN_LIMIT_EXCEEDED`, message "Export monthly limit
  reached: plan allows 1 per month, currently at 1".
- Query `audit_logs` WHERE `action='PLAN_LIMIT_DENIED'` AND `tenant_id`=tenant test → thấy đúng 1
  dòng, `entity_type='PlanLimit'`, `entity_id='export'`, `new_value` chứa
  `{"limitType":"export","reason":"Export monthly limit reached..."}`.
- Restore lại `maxExportsPerMonth=10` trên gói trial ngay sau test — xác nhận qua `GET
  /plans/{trial}/limits` trả đúng giá trị gốc.
- Regression sau khi vá: `test_plan_limits.sh` 13/13, `test_plans.sh` 17/17, `test_subscription.sh`
  19/19, `test_plan_deactivation_migration.sh` 8/8, `test_export_attendance.sh` 11/11,
  `test_export_violations.sh` 10/10 — toàn bộ PASS, không regression.

## B. Test trên Web Admin / Mobile App
Không có UI riêng cho sự kiện bị từ chối do vượt hạn mức (người dùng thấy thông báo lỗi chung từ
API) — không nằm trong phạm vi AC yêu cầu vá thêm. Field `maxExportsPerMonth` mới cũng không được
thêm vào UI quản lý gói ở đợt này (chưa có yêu cầu cụ thể); ghi nhận là follow-up UI nhỏ nếu Web
Admin team muốn hiển thị/sửa field này trong màn Plan Management.

---

## Ghi chú
Backend regression (`tests/tenant/*.sh`, `tests/subscription/*.sh`) 91/91 pass sau khi restore dữ
liệu gói `trial` + thêm audit denied, cùng đợt #131-135. Đợt 2 (export limit): xem mục A.2 ở trên.
