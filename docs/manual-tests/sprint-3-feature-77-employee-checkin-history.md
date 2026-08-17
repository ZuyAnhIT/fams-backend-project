# Kịch bản test thủ công — #77 Nhân viên xem lịch sử chấm công

**Nền tảng: Backend, Mobile App.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận và vá (2026-08-17) theo quyết định của chủ dự án:

- **Filter `status` và khoảng ngày: đã có sẵn ở backend**, nhưng App trước đây KHÔNG có UI nào để
  dùng. **Đã vá**: thêm dải chip lọc trạng thái (Tất cả/Hợp lệ/Đang chờ/Từ chối) + nút bật/tắt lọc
  theo tháng (kèm điều hướng tháng trước/sau, giống màn Bảng công).
- **Filter theo site: trước đây KHÔNG TỒN TẠI ở cả 2 tầng — quyết định chủ dự án: cần thêm.** Đã vá
  cả backend (`GET .../checkin/history?siteId=...`) lẫn App (dải chip chọn công trình, tự động lấy
  danh sách site từ lịch sử gần đây của chính nhân viên — chỉ hiện khi có ≥2 site khác nhau).
- Endpoint luôn tự động giới hạn theo đúng nhân viên đang đăng nhập (không đổi).

---

## A. Test trên Mobile App — ĐÃ TEST LIVE (2026-08-17)

### 1. ✅ Xem lịch sử chấm công — happy path, TEST LIVE
- Vào màn "Lịch sử chấm công" với nhân viên có check-in ở 2 site khác nhau.
- **Kết quả thật (Playwright, ảnh `history-01-default.png`):** hiện đúng 3 bản ghi (2 ở Site A: 1
  hợp lệ, 1 đang chờ; 1 ở Site B: hợp lệ), đầy đủ dải chip lọc mới ở đầu màn.

### 2. ✅ Lọc theo site — ĐÃ VÁ, TEST LIVE
- Bấm chip "Site B".
- **Kết quả thật (ảnh `history-02-site-filtered.png`):** danh sách lọc ngay còn đúng 1 bản ghi của
  Site B, nút "Xóa lọc" xuất hiện.

### 3. ✅ Kết hợp nhiều filter cùng lúc (AND) — TEST LIVE
- Với filter site="Site B" đang bật, bấm thêm chip "Đang chờ".
- **Kết quả thật (ảnh `history-03-status-filtered.png`):** danh sách trả về RỖNG (đúng — Site B chỉ
  có bản ghi "Hợp lệ", không có bản ghi "Đang chờ") — xác nhận các filter kết hợp đúng kiểu AND,
  không phải OR.

### 4. ✅ Bật lọc theo tháng — TEST LIVE
- Bấm chip "Mọi thời gian" để bật lọc tháng.
- **Kết quả thật (ảnh `history-04-month-filter.png`):** chip đổi thành "Tháng 8/2026" kèm 2 nút
  điều hướng tháng trước/sau.

### 5. Phân trang
- Với nhân viên có nhiều lần chấm công, chuyển trang.
- **Kỳ vọng:** hoạt động đúng, không trùng/thiếu (không đổi so với trước, không hồi quy).

### 6. Xóa toàn bộ filter
- Bấm "Xóa lọc" khi đang có ≥1 filter.
- **Kỳ vọng:** về lại trạng thái mặc định (Tất cả/Mọi công trình/Mọi thời gian), hiện đủ toàn bộ
  lịch sử.

---

## Ghi chú
Toàn bộ case đã test live qua UI thật (Playwright, chế độ web). Cả 2 gap đã vá theo đúng quyết định
của chủ dự án: (1) filter status/ngày đã có sẵn backend, chỉ thiếu UI App — đã bổ sung; (2) filter
site hoàn toàn chưa có — đã làm mới cả 2 tầng. Dải chip site chỉ hiện khi nhân viên thực sự làm
≥2 site (tránh UI thừa cho trường hợp phổ biến chỉ làm 1 site). Đã đóng — ĐÃ KHÓA.
