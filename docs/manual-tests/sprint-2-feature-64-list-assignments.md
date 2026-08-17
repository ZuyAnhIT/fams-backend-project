# Kịch bản test thủ công — #64 Danh sách phân công

**Nền tảng: Backend, Web Admin, Mobile App.**

ℹ️ Audit gốc (07-22) ghi thiếu: "thiếu filter khoảng ngày". Đã xác nhận và vá (2026-08-17); phát
hiện thêm khoảng trống Mobile App (theo AC gốc liệt kê Mobile App là 1 nền tảng) — chủ dự án đã
quyết định làm thêm màn hình mới, đã triển khai.

- **Filter khoảng ngày: ĐÃ VÁ** — thêm `dateRangeFrom`/`dateRangeTo` (kiểu overlap: khớp assignment
  đang hiệu lực bất kỳ ngày nào trong khoảng, không phải khớp chính xác) vào `AssignmentSpecification`
  và query param API. UI thêm `RangePicker` trong bộ lọc.
- **Mobile App: KHÔNG có màn hình liệt kê phân công riêng — ĐÃ LÀM THEO QUYẾT ĐỊNH CHỦ DỰ ÁN.** Vì
  backend không có endpoint tenant-wide "phân công của tôi" (chỉ có endpoint theo từng site), đã
  thêm MỚI:
  - Backend: `GET /tenants/{tenantId}/assignments/me` (self-service, không cần quyền
    `assignments:*`, cùng mô hình tin cậy với `/attendance/me/monthly` sẵn có) — trả về TOÀN BỘ
    phân công của nhân viên hiện tại, gộp mọi site, sắp mới nhất trước. Response có thêm
    `siteSummary` (tên công trình) để hiển thị đúng khi liệt kê nhiều site cùng lúc.
  - Mobile App: màn hình mới "Phân công của tôi" (`src/features/my-assignments/`), truy cập từ tab
    Hồ sơ → mục mới, hiển thị card theo từng phân công (site, vai trò, khoảng ngày, lịch tuần, ca
    nếu có, trạng thái + thời điểm hủy nếu đã hủy).
- **"Hiển thị ca và site chính": XÁC NHẬN đúng thiết kế cũ (list theo context 1 site tại 1 thời
  điểm, không phải gap)** — không đổi ở endpoint site-scoped; endpoint mới `/assignments/me` giải
  quyết đúng nhu cầu "xem toàn bộ, mọi site" mà AC gốc ngụ ý.

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. ✅ Filter khoảng ngày — TEST LIVE qua UI thật
- Site "Site One" có 1 assignment tháng 8 (đã hủy). Lọc khoảng ngày 2026-09-01 → 2026-09-30.
- **Kết quả thật (ảnh `webassign-02-date-range-filtered.png`):** danh sách trả về RỖNG ("Không tìm
  thấy phân công") — đúng vì assignment tháng 8 không giao với khoảng lọc tháng 9.

### 2. ✅ Filter khoảng ngày — case khớp (overlap đúng với assignment vô thời hạn)
- Site "Site Two" có 1 assignment từ 2026-09-01, KHÔNG có `endDate` (vô thời hạn). Lọc khoảng ngày
  2026-09-01 → 2026-09-30.
- **Kết quả thật (ảnh `webassign-03-siteTwo-sept-filtered.png`):** trả về đúng 1 kết quả — xác nhận
  logic overlap đúng (endDate null = luôn "còn mở", không bị loại khi lọc theo khoảng ngày tương lai).

### 3-9. Các case lọc/sort/phân trang khác
- Không đổi hành vi, script `test_list_assignments.sh` xác nhận 13/13 pass, không hồi quy.

## B. Test trên Mobile App — ĐÃ TEST LIVE (2026-08-17)

### 10. ✅ Xem "Phân công của tôi" — TEST LIVE qua UI thật (Playwright, chế độ web)
- Đăng nhập tài khoản nhân viên có 2 phân công ở 2 site khác nhau (Site One đã hủy, Site Two đang
  active) → Hồ sơ → "Phân công của tôi".
- **Kết quả thật (ảnh `app-04-my-assignments.png`, `app-05-my-assignments-with-cancelled.png`):**
  hiện đúng cả 2 phân công, đúng tên site, vai trò, khoảng ngày, trạng thái; card phân công đã hủy
  hiện mờ đi + dòng đỏ "Đã hủy lúc [thời gian]" — xác nhận `cancelledAt` (xem #66) hiển thị đúng
  ngay trên App.
- **Trường hợp không có employee profile trong tenant:** API trả 404, FE xử lý graceful (trả về
  mảng rỗng, không crash) — cùng pattern với `getCurrentEmployeeId`.

---

## Ghi chú
Toàn bộ case đã test live: script tự động `test_list_assignments.sh` 13/13 pass + test tay qua
API/DB cho filter khoảng ngày + Playwright cho cả Web Admin (filter) và Mobile App (màn hình mới,
chạy qua `expo start --web`, không cần thiết bị thật). Đây là lần đầu thêm 1 endpoint + 1 màn hình
Mobile App hoàn toàn mới trong đợt vá gap (không chỉ sửa code có sẵn) — theo đúng quyết định của
chủ dự án khi được hỏi. Đã đóng — ĐÃ KHÓA.
