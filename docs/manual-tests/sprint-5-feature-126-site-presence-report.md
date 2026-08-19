# Kịch bản test thủ công — #126 Báo cáo hiện diện theo site

**Nền tảng: Backend, Web Admin, Mobile App.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG — bằng chứng: `ReportController.getSitePresenceReport`,
`test_site_presence_report.sh`. Audit lại code hiện tại (2026-08-18), cùng đợt rà soát toàn bộ
module Report (#126-130), phát hiện **audit gốc SAI/lỗi thời trên 2 điểm** — cùng dạng gap đã lặp
lại ở #122/#123/#125 trong đợt trước:

- **❌ GAP #1: hoàn toàn không có filter workspace** dù AC yêu cầu rõ "filter site/workspace".
- **❌ GAP #2: thiếu số liệu `violation`** dù AC yêu cầu rõ "Tổng assigned hôm nay, checked-in,
  missing, violation" — response chỉ có assigned/present/absent, không có violation ở đâu cả.

## ✅ ĐÃ VÁ (2026-08-18)

- `GET /reports/sites/presence` nhận thêm `workspaceId` — resolve tập `employeeId` thuộc
  workspace trước (cùng pattern dùng chung với #122/#123/#125), lọc cả `presentEmployees` lẫn
  `assignedIds` theo tập đó trước khi tính present/absent/assigned.
- Thêm `unresolvedViolations` per-site (tái dùng `ViolationRepository.countUnresolved` đã có sẵn
  từ #118/#121) và `totalUnresolvedViolations` tổng toàn báo cáo.
- Web Admin: thêm dropdown workspace, StatCard "Vi phạm chưa xử lý", Tag đỏ trên site-card khi
  site đó có vi phạm chưa xử lý.

---

## A. Test trên Backend

### 1. ✅ `GET /reports/sites/presence` không kèm `workspaceId` — không đổi hành vi cũ ngoài field mới
- **Kỳ vọng:** giống hệt trước khi vá, chỉ thêm `unresolvedViolations=0`/`totalUnresolvedViolations=0`
  khi không có vi phạm.

### 2. ✅✅ (Case quan trọng nhất) `workspaceId=X` chỉ tính nhân viên thuộc workspace X
- Setup: 2 nhân viên cùng site cùng có assignment; chỉ 1 người thuộc workspace X; người còn lại
  check-in (present).
- **Kỳ vọng — xác nhận đúng qua live API call:** không filter → `assignedCount=2`; filter
  `workspaceId=X` → `assignedCount=1`, đúng `presentEmployees` chỉ chứa nhân viên thuộc workspace.

### 3. ✅✅ `unresolvedViolations`/`totalUnresolvedViolations` đếm đúng
- Setup: seed 1 violation `resolved=false` tại site.
- **Kỳ vọng — xác nhận đúng qua live API call:** `totalUnresolvedViolations=1`, đúng site đó có
  `unresolvedViolations=1`.

## B. Test trên Web Admin

### 4. ✅✅ ĐÃ TEST LIVE qua Playwright thật — dropdown workspace + StatCard vi phạm mới
- Trang Báo cáo > Hiện diện theo site: xác nhận qua DOM thật dropdown
  `aria-label="Lọc hiện diện theo workspace"` hiện đúng cạnh dropdown site, đủ 5 StatCard bao gồm
  "Vi phạm chưa xử lý" (trước đó chỉ có 4). Không có lỗi console.

## C. Test trên Mobile App
Không có thay đổi trực tiếp cho #126 phía Mobile App (site presence report chỉ có trên Web
Admin) — Nền tảng "Mobile App" trong BACKLOG.md áp dụng cho báo cáo hiện diện tổng quan hiển thị
gián tiếp qua Supervisor Dashboard (#121), đã test riêng ở đợt trước.

---

## Ghi chú
`tsc --noEmit` phía Web Admin sạch. Backend regression (`tests/report/*.sh`) chạy lại cùng đợt
#126-130, không phát hiện regression.
