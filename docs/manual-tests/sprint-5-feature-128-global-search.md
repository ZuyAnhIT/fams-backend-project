# Kịch bản test thủ công — #128 Tìm kiếm nhanh nhân viên/site/check-in

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — bằng chứng: `SearchController`/`SearchService`,
`test_global_search.sh`; thiếu: thiếu nhóm kết quả violation. Audit lại code hiện tại (2026-08-18)
xác nhận: **gap #1 vẫn đúng, chưa lỗi thời**. Đồng thời phát hiện 1 điểm audit gốc KHÔNG nhắc tới
nhưng hóa ra rất quan trọng — **access control** (tôn trọng quyền truy cập, cũng nằm trong AC):

- **❌ GAP #1 (đã xác nhận đúng): thiếu nhóm kết quả `violations`** — response chỉ có
  employees/sites/checkins.
- **✅ Access control: ĐÃ ĐƯỢC VÁ TỪ TRƯỚC** (commit `b50913f`, sau ngày audit gốc 07-22) —
  `SiteScopeService.resolveAllowedSiteIds` đã áp dụng cho cả 3 nhóm kết quả, một `SITE_SUPERVISOR`
  bị giới hạn site không còn tìm thấy dữ liệu ngoài phạm vi. Không phải gap còn tồn đọng, nhưng
  **thiếu test coverage** cho hành vi này — không phải lỗi, chỉ là rủi ro hồi quy trong tương lai
  không bị bắt bởi test tự động.

## ✅ ĐÃ VÁ (2026-08-18)

- `GlobalSearchResponse` thêm field `violations` — vi phạm CHƯA XỬ LÝ của nhân viên khớp từ khóa
  tìm kiếm, cộng thêm tra cứu trực tiếp theo UUID chính xác (cùng pattern với nhóm `checkins`
  đã có). Tôn trọng site-scope giống hệt 3 nhóm còn lại.
- Web Admin: `GlobalSearch.tsx` (thanh tìm kiếm nhanh trên header) thêm section "Vi phạm chưa xử
  lý".

### 📝 Chưa vá (ghi nhận, không thuộc phạm vi đợt này)
Thiếu test tự động cho site-scope enforcement trong `test_global_search.sh` (test case
SITE_SUPERVISOR bị giới hạn site chỉ thấy đúng phạm vi của mình) — hành vi ĐÃ ĐÚNG trong code,
chỉ là chưa có test khóa lại để tránh hồi quy sau này. Đề xuất bổ sung ở đợt sau.

---

## A. Test trên Backend

### 1. ✅ `GET /search` không đổi hành vi cũ trên 3 nhóm employees/sites/checkins

### 2. ✅✅ (Case quan trọng nhất) Nhóm `violations` trả đúng vi phạm của nhân viên khớp từ khóa
- Setup: 1 nhân viên tên "V126 Emp2" có 1 violation `resolved=false`.
- **Kỳ vọng — xác nhận đúng qua live API call:** search `q=V126` → `employees` trả 2 nhân viên
  khớp tên, `violations` trả đúng 1 violation của nhân viên đó, đầy đủ field
  (violationType/checkDate/description/resolved).

## B. Test trên Web Admin

### 3. ✅✅ ĐÃ TEST LIVE qua Playwright thật — ô tìm kiếm nhanh trên header
- Xác nhận qua UI thật (viewport rộng 1600px để ô tìm kiếm hiện): gõ từ khóa khớp dữ liệu thật,
  dropdown kết quả hiện đủ section (Nhân viên/Check-in/Vi phạm) không lỗi console, không vỡ layout.

---

## Ghi chú
`tsc --noEmit` phía Web Admin sạch. Backend regression (`tests/search/*.sh`) chạy lại cùng đợt
#126-130, không phát hiện regression.
