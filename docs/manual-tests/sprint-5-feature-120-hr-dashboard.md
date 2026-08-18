# Kịch bản test thủ công — #120 Dashboard HR

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "không có filter workspace/site; thiếu
missing-checkout/pending-review". Đã audit lại code hiện tại (2026-08-18) — **CẢ 2 điểm ĐÚNG,
không lỗi thời:**

- **❌ GAP thật #1: hoàn toàn KHÔNG có filter theo site** ở bất kỳ khối số liệu nào
  (`personnel`/`attendance`/`violations`/`sites`) — `HrDashboardController`/`HrDashboardService`
  không nhận `siteId` ở đâu cả.
- **❌ GAP thật #2: thiếu `missingCheckout`/`pendingReview` count** — dù cả 2 field nguồn
  (`AttendanceSummary.missingCheckout`, `CheckinRecord.status='pending_review'`) đã tồn tại và
  được dùng ở tính năng khác (#84, #111), dashboard chưa từng surface chúng thành số liệu tổng.

## ✅ ĐÃ VÁ (2026-08-18) — ĐÃ KHÓA

### Gap #1 — filter site: ĐÃ VÁ đầy đủ (theo quyết định người dùng — làm đủ cả, không hoãn)
- `GET /dashboard/hr?siteId=` — mọi khối số liệu giờ lọc được theo site.
- `attendance`/`violations` lọc trực tiếp qua `siteId` có sẵn trên entity (nullable-OR JPQL,
  cùng pattern đã dùng ở #109/#114).
- `personnel` (tổng nhân viên, nhân viên mới) — Employee KHÔNG có `siteId` trực tiếp (quan hệ qua
  Assignment, nhiều-nhiều theo thời gian) — lọc qua tập `employeeId` lấy từ
  `AssignmentRepository.findDistinctEmployeeIdsByTenantIdAndSiteIdIn` trước, rồi đếm Employee
  theo tập ID đó.
- `sites.totalSites` = 1 khi có filter site (đang xem đúng 1 site).

### Gap #2 — missing-checkout/pending-review: ĐÃ VÁ
- `AttendanceOverview` thêm 2 field mới: `pendingReview` (checkin `status=pending_review` hôm
  nay) và `missingCheckoutToday` (attendance summary hôm nay có `missingCheckout=true`).

### Web Admin
- Thêm dropdown "Lọc theo công trình" phía trên dashboard HR.
- Thêm 2 StatCard mới: "Chờ HR duyệt" và "Quên check-out hôm nay".

---

## A. Test trên Backend

### 1. ✅ `GET /dashboard/hr` không kèm `siteId` — số liệu toàn tenant (không đổi hành vi cũ)
- **Kỳ vọng:** giống hệt trước khi vá.

### 2. ✅✅ (Case quan trọng nhất) `GET /dashboard/hr?siteId=X` — số liệu chỉ tính riêng site X
- Setup: 2 site A/B trong cùng tenant; 1 nhân viên chỉ có assignment ở site A; 1 violation gắn
  site B.
- **Kỳ vọng:** filter `siteId=A` → `personnel.totalEmployees=1`, `violations.unresolved=0`.
  Filter `siteId=B` → `personnel.totalEmployees=0`, `violations.unresolved=1`. Test live xác nhận
  đúng cả 2 chiều, không lẫn.

### 3. ✅ `attendance.pendingReview` và `attendance.missingCheckoutToday` trả đúng số
- **Kỳ vọng:** khớp đúng số checkin `pending_review` và summary `missingCheckout=true` hôm nay.

## B. Test trên Web Admin
### 4. ✅ Dropdown lọc site + 2 StatCard mới hiển thị đúng
- Test live qua UI thật (Playwright): dropdown "Toàn bộ công ty" hiển thị đúng, 2 card "Chờ HR
  duyệt"/"Quên check-out hôm nay" render đúng vị trí, đúng icon/mô tả.

---

## Ghi chú
Regression: `test_hr_dashboard.sh` (36/36 cùng đợt #116-120) PASS. `tsc --noEmit` sạch phía Web
Admin.
