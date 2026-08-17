# Kịch bản test thủ công — #85 Nhân viên xem bảng công ngày/tháng

**Nền tảng: Backend, Mobile App.**

## ✅ ĐÃ FIX (2026-08-17) — ĐÃ KHÓA

Quyết định nghiệp vụ (project owner, 2026-08-17): **cần thêm lọc site**; giữ nguyên cờ dẫn xuất cho
"violation" (không tích hợp bảng `violations` thật — ngoài phạm vi cần thiết).

### Thay đổi
- `GET /me` — thêm `siteId` (optional) qua `AttendanceSummarySpecification.build`.
- `GET /me/monthly` — thêm `siteId` (optional); repository method mới
  `findByTenantIdAndEmployeeIdAndSiteIdAndDateRange` (JPQL `(:siteId IS NULL OR a.siteId = :siteId)`).
- Mobile App: `MyAttendanceScreen.tsx` — thêm dòng chip lọc site ("Tất cả" + từng site), chỉ hiển
  thị khi nhân viên có ≥ 2 site trong tháng (cùng pattern với #77's site filter cho lịch sử
  check-in). Site options được suy ra từ 1 lần fetch KHÔNG lọc riêng
  (`useMyAttendanceSiteOptions`), tách biệt khỏi query chính đã lọc (`useMyMonthlyAttendance`).
- "Violation" giữ nguyên là cờ dẫn xuất (`hasRandomCheckFailure`, `hasPendingReviewSession`,
  `hasRejectedSession`) — quyết định KHÔNG tích hợp bảng `violations` thật, các cờ hiện tại đã đủ
  cho nhân viên biết ngày nào cần chú ý.

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-17)

### 1. ✅ `GET /me/monthly` KHÔNG có `siteId` — gộp tất cả site
- Nhân viên chấm công ở SiteA và SiteB trong cùng tháng, gọi không truyền `siteId`.
- **Kết quả thực tế:** `presentDays=2`, `dailySummaries[]` có cả `SiteA` và `SiteB` — ĐÚNG.

### 2. ✅ `GET /me/monthly` VỚI `siteId=SiteA` — chỉ còn SiteA
- Gọi lại với `siteId` của SiteA.
- **Kết quả thực tế:** `presentDays=1`, chỉ còn bản ghi SiteA — ĐÚNG.

### 3. ✅ `GET /me` VỚI `siteId` — lọc đúng
- Gọi `GET /me?siteId=SiteB`.
- **Kết quả thực tế:** chỉ trả về bản ghi SiteB — ĐÚNG.

### 4. ✅ `dailySummaries[]` có đủ cờ để highlight ngày lỗi (không đổi so với trước)
- Xác nhận 10 cờ đầy đủ, không bị ảnh hưởng bởi thay đổi filter.

---

## B. Test trên Mobile App — ✅ PASS, đã test live qua UI thật (Playwright, 2026-08-17)
- Đăng nhập nhân viên có chấm công 2 site (SiteA, SiteB) trong cùng tháng.
- Màn "Bảng công của tôi": dòng chip "Tất cả / SiteA / SiteB" hiển thị đúng.
- Chọn "Tất cả": tổng quan hiện `2 Ngày ghi nhận`, chi tiết hiện đủ 2 ngày (SiteA + SiteB).
- Chọn "SiteA": tổng quan cập nhật còn `1 Ngày ghi nhận`, chi tiết chỉ còn 1 ngày (SiteA) — xác
  nhận filter hoạt động đúng, đồng bộ với backend.
- Nhân viên chỉ có 1 site: dòng chip filter tự động ẨN (không gây rối giao diện với trường hợp phổ
  biến nhất — đa số nhân viên chỉ làm 1 site).

---

## Regression
Toàn bộ `tests/attendance/*.sh` (9 suite) — 100% PASS sau fix, không có regression.
`npx tsc --noEmit` (cả Web Admin lẫn Mobile App) — không có lỗi type liên quan.
