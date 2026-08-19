# Kịch bản test thủ công — #132 Export danh sách vi phạm

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — bằng chứng: `ReportController.exportViolations`,
`test_export_violations.sh`; thiếu: không ghi audit. Audit lại code hiện tại (2026-08-19) xác
nhận **đúng và vẫn còn tới trước lần vá này** — khác với #124 (export bảng công, đợt trước), gap
này KHÔNG phải do lỗi transaction âm thầm (bug đã vá REQUIRES_NEW) mà là **hoàn toàn chưa có dòng
code gọi audit nào cả** trong `exportViolations`.

- **❌ GAP thật (đã xác nhận đúng): hoàn toàn không ghi audit log.**
- **✅ Format/filter: đã đúng** — CSV/XLSX, tôn trọng đầy đủ filter (from/to/site/employee/type/
  workspace).
- **✅ Photo reference: không phải gap** — export hiện KHÔNG xuất bất kỳ cột ảnh nào (kể cả
  `employeePhotoUrl` — vốn đã tồn tại trên entity nhưng không được đọc trong export), nên AC
  "không xuất ảnh trực tiếp" được thỏa mãn tự nhiên (thậm chí nghiêm ngặt hơn yêu cầu).

## ✅ ĐÃ VÁ (2026-08-19)
Thêm `auditLogService.record(..., "EXPORT_VIOLATIONS", ...)` vào cuối `exportViolations`, cùng
pattern với `EXPORT_ATTENDANCE` (#124) — bọc try/catch, không làm hỏng request export nếu ghi
audit lỗi. An toàn dùng trong transaction `readOnly=true` nhờ `AuditLogService.record()` đã là
`REQUIRES_NEW` từ đợt vá #124.

---

## A. Test trên Backend

### 1. ✅✅ (Case quan trọng nhất) Export vi phạm ghi đúng audit log
- **Kỳ vọng — xác nhận đúng qua live API call:** gọi `GET /reports/violations/export` → query
  `audit_logs` ngay sau đó thấy đúng 1 dòng `action='EXPORT_VIOLATIONS'`, `actor_id` đúng người
  export.

## B. Test trên Web Admin

### 2. ✅ Nút "Xuất Excel" trên trang Vi phạm — không đổi, đã xác nhận từ đợt trước hoạt động đúng

---

## Ghi chú
`tsc --noEmit` phía Web Admin sạch (không có thay đổi frontend cho #132). Backend regression
(`tests/report/*.sh`) 91/91 pass cùng đợt #131-135.
