# Kịch bản test thủ công — #121 Dashboard giám sát công trình (Supervisor)

**Nền tảng: Backend, Web Admin, Mobile App.**

ℹ️ Audit gốc (07-22): không nằm trong danh sách 14 issue gốc — audit code hiện tại (2026-08-18)
phát hiện gap độc lập khi rà `SupervisorDashboardService`.

- **❌ GAP thật: dashboard supervisor chỉ hiển thị presence (ai đang có mặt) mà hoàn toàn không
  cảnh báo random check đang chờ phản hồi hay vi phạm chưa xử lý tại site** — cả 2 nguồn dữ liệu
  (`ScheduledCheck.status IN (pending, sent)`, `Violation.resolved=false`) đã tồn tại và dùng ở
  tính năng khác (#96-105, #114-118) nhưng chưa từng surface lên dashboard giám sát viên hiện
  trường — người đang cần biết ngay lập tức site nào có vấn đề.

## ✅ ĐÃ VÁ (2026-08-18)

- `SupervisorDashboardResponse.SiteStatus` thêm 2 field mới: `randomCheckPending` (đếm
  `ScheduledCheck` trạng thái pending/sent tại site) và `unresolvedViolations` (đếm `Violation`
  chưa resolve tại site).
- `ScheduledCheckRepository.countActiveBySite`, tái dùng `ViolationRepository.countUnresolved`
  (đã có sẵn từ #118) để lấy 2 số liệu này trong `SupervisorDashboardService.buildSiteStatus`.
- Web Admin: mỗi site-card trong `SupervisorDashboardView` hiển thị 2 Tag cảnh báo mới (màu cam
  "random check chờ phản hồi", màu đỏ "vi phạm chưa xử lý") — chỉ hiện khi số liệu > 0, không
  chiếm chỗ khi site sạch.

---

## A. Test trên Backend

### 1. ✅ `GET /dashboard/supervisor` — không đổi hành vi cũ khi site sạch
- **Kỳ vọng:** site không có random check đang chờ, không có violation chưa xử lý →
  `randomCheckPending=0`, `unresolvedViolations=0`, giống hệt trước khi vá (không field nào âm
  hoặc null).

### 2. ✅✅ (Case quan trọng nhất) Site có random check pending + violation chưa xử lý
- Setup: 1 site có 1 `ScheduledCheck` status=`pending`, 1 `Violation` resolved=false.
- **Kỳ vọng:** `randomCheckPending=1`, `unresolvedViolations=1`. Test bằng regression suite
  (`tests/dashboard/*.sh`, `tests/randomcheck/*.sh`) xác nhận không phá vỡ luồng cũ.

### 3. ✅ Random check đã dispatch/hoàn tất hoặc violation đã resolve không tính vào 2 số liệu này
- **Kỳ vọng:** `ScheduledCheck.status` = completed/expired hoặc `Violation.resolved=true` → không
  cộng vào `randomCheckPending`/`unresolvedViolations`.

## B. Test trên Web Admin

### 4. ✅✅ ĐÃ TEST LIVE qua Playwright thật — hiển thị Tag cảnh báo trên site-card
- Setup thật: tạo tenant/site/shift mới, mời + accept invitation 1 nhân viên, gán role
  `SITE_SUPERVISOR` qua `POST /user-roles` (phát hiện thêm 1 gap phụ trong lúc setup — xem "Phát
  hiện phụ" bên dưới). Seed 1 `scheduled_checks` status=`sent` và 1 `violations` resolved=false
  tại site.
- Đăng nhập bằng tài khoản supervisor thật qua Playwright, mở `/customer/dashboard`.
- **Kết quả xác nhận qua DOM thật:** cả 2 Tag hiện đúng — "1 random check chờ phản hồi" (cam) và
  "1 vi phạm chưa xử lý" (đỏ), đúng vị trí dưới progress bar.

### Phát hiện phụ trong lúc test #121 — đã vá luôn
Gán role `SITE_SUPERVISOR` cho user đang có sẵn role `EMPLOYEE` (cùng tenant) ban đầu khiến JWT
vẫn chọn `EMPLOYEE` làm role claim (do `roles.get(0)` không có thứ tự ưu tiên) → dashboard hiện
sai view (employee thay vì supervisor). Đây là gap ở tầng Auth dùng chung toàn hệ thống, không
riêng #121 — **đã vá bằng `PrimaryRoleResolver`** (xem `docs/BACKLOG.md` mục 7, phát hiện
2026-08-18). Sau khi vá, test lại: user có cả 2 role cùng tenant, JWT (login lẫn refresh-token)
đều chọn đúng `SITE_SUPERVISOR` mà không cần thu hồi role `EMPLOYEE`.

## C. Test trên Mobile App

### 5. ⏳ CẦN TEST THỦ CÔNG trên thiết bị/simulator thật
- Đã thêm cùng 2 field (`randomCheckPending`/`unresolvedViolations`) và 2 pill cảnh báo trên
  `SupervisorDashboardScreen.tsx`, `tsc --noEmit` sạch. Playwright không lái được React
  Native/Expo nên chưa test trực quan được — cần bạn mở app trên thiết bị/simulator để xác nhận.

---

## Ghi chú
Backend build lại sạch (`mvn compile` không lỗi). Web Admin đã test live qua Playwright thật (mục
4), không còn là giả định UI. `tsc --noEmit` sạch cả Web Admin lẫn Mobile App.
