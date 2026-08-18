# Kịch bản test thủ công — #86 HR xem bảng công tổng hợp

**Nền tảng: Backend, Web Admin.**

## ✅ ĐÃ FIX (2026-08-17) — ĐÃ KHÓA

Quyết định nghiệp vụ (project owner, 2026-08-17): **thêm lọc status + sort**; KHÔNG thêm lọc
workspace (workspace chưa gắn với module chấm công ở bất kỳ đâu khác, chi phí lớn hơn giá trị ở
giai đoạn này).

### Thay đổi
- `GET /monthly` — thêm `status` (optional, `present`/`incomplete`, validated qua whitelist) và
  `sortBy`/`sortDir` (optional, whitelist 5 field: `totalWorkMinutes, totalLateMinutes,
  totalOtMinutes, missingCheckoutDays, presentDays`; `sortDir` mặc định `asc`).
- `status` lọc theo kiểu HAVING ở tầng aggregate: giữ lại 1 dòng employee+site+tháng nếu CÓ ÍT
  NHẤT 1 ngày trong tháng đó khớp status yêu cầu (aggregate là 1 dòng/employee+site/tháng, không
  phải 1 dòng/ngày, nên "status" chỉ có ý nghĩa ở mức "có ngày nào khớp không", không phải lọc
  từng dòng theo 1 status duy nhất).
- `sortBy`/`sortDir` triển khai bằng CASE WHEN trong SQL native (KHÔNG string-concat — vẫn là bind
  param so sánh, an toàn injection), fallback về thứ tự ổn định cũ (`employee_id, site_id`) nếu
  không truyền hoặc không khớp whitelist.
- Truyền `status`/`sortBy` sai (ngoài whitelist) → 400 rõ ràng, KHÔNG âm thầm bỏ qua (tránh HR
  tưởng nhầm filter "không hoạt động" khi thực ra là gõ sai tên).

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-17)

### 1. ✅ `status=present` — giữ đúng các dòng có ngày present
- Tạo dữ liệu 2 employee+site có ngày `present` hoàn chỉnh trong tháng, gọi `status=present`.
- **Kết quả thực tế:** `totalElements=2` — ĐÚNG.

### 2. ✅ `status=incomplete` — lọc đúng, trả rỗng khi không có ngày nào incomplete
- Cùng dữ liệu trên (không có phiên nào đang mở/thiếu checkout), gọi `status=incomplete`.
- **Kết quả thực tế:** `totalElements=0` — ĐÚNG.

### 3. ✅ `status` không hợp lệ → 400
- Gọi `status=bogus`.
- **Kết quả thực tế:** HTTP 400 — ĐÚNG.

### 4. ✅ `sortBy` không hợp lệ → 400
- Gọi `sortBy=bogus`.
- **Kết quả thực tế:** HTTP 400 — ĐÚNG.

### 5. ✅ `sortBy=totalWorkMinutes&sortDir=desc` — không lỗi, trả kết quả
- Gọi với sort hợp lệ.
- **Kết quả thực tế:** HTTP 200, dữ liệu trả về đúng field đã sort.

### 6. Không lọc theo workspace (quyết định giữ nguyên, không phải bug)
- Xác nhận `workspaceId` không có tác dụng (không truyền vào query) — đúng quyết định đã chốt.

---

## B. Test trên Web Admin — ✅ PASS, đã test live qua UI thật (Playwright, 2026-08-17)
- Tab "Tổng hợp tháng" (`AttendanceMonthlyTab.tsx`): thêm 2 dropdown mới "Lọc theo trạng thái
  ngày" và "Sắp xếp theo" (+ chiều tăng/giảm khi đã chọn sort).
- Chọn "Có ngày đủ công (present)": request thực tế gửi đúng `status=present`, bảng cập nhật
  đúng kết quả (2 dòng SiteA/SiteB đều có ngày present) — xác nhận filter hoạt động đúng end-to-end
  từ UI đến backend.

---

## Regression
Toàn bộ `tests/attendance/*.sh` (9 suite) — 100% PASS sau fix, không có regression. Đã kiểm tra
thêm caller `ReportService.aggregateMonthlyRows` (dùng chung `aggregateMonthly` cho export báo cáo)
— cập nhật đúng chữ ký mới, không breaking.
