# Kịch bản test thủ công — #78 HR xem danh sách check-in

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code hiện tại (`CheckinService.listCheckins`)
— **các filter cốt lõi hoạt động đúng; `invalid_reason`/`source` trong AC gốc xác nhận CHƯA BAO GIỜ
tồn tại. Đã hỏi chủ dự án — quyết định: KHÔNG cần thêm cả 2, giữ nguyên.**

- **Filter CÓ THẬT: employeeId, siteId, status, khoảng ngày (from/to), sort** — hoạt động đúng, có
  whitelist field sort (`checkInAt, checkOutAt, status, siteId, employeeId, createdAt`).
- **Filter `invalid_reason`/`source`: xác nhận không làm** theo quyết định chủ dự án (giữ nguyên bộ
  filter hiện tại là đủ cho nhu cầu kiểm soát thực tế).
- **Site-scope gating (bảo mật quan trọng):** tài khoản bị giới hạn theo 1 site → tự động lọc đúng
  site đó; giới hạn nhiều site mà KHÔNG truyền `siteId` cụ thể → bị chặn 403, yêu cầu chọn rõ 1
  site; không được gán site nào → trang trống, không lỗi.
- **"Xem ảnh/vị trí nếu có quyền": chỉ có 1 mức quyền chung (`checkins:list`)** — không có phân
  quyền riêng cho việc xem tọa độ GPS/ảnh, xác nhận đúng mô hình hiện tại, không sửa.

---

## A. Test trên Web Admin

### 1. Xem danh sách check-in — happy path
- Vào màn quản lý chấm công, quan sát danh sách.
- **Kỳ vọng:** hiện đúng check-in của tenant/site trong phạm vi quyền.

### 2. Lọc theo nhân viên, site, trạng thái, khoảng ngày
- Áp từng filter riêng lẻ và kết hợp.
- **Kỳ vọng:** lọc đúng theo từng tiêu chí.

### 3. Sort theo các cột hợp lệ
- Thử sort theo `checkInAt`, `status`, `siteId`...
- **Kỳ vọng:** đúng thứ tự.

### 4. ✅ Xác nhận KHÔNG có filter `invalid_reason`/`source` — quyết định chủ dự án, không sửa
- Quan sát toàn bộ khu vực filter.
- **Kỳ vọng theo code hiện tại:** không có 2 ô lọc này — đã xác nhận đúng, giữ nguyên theo quyết
  định.

### 5. ✅ Site-scope: tài khoản giới hạn nhiều site, không chọn site cụ thể
- Đăng nhập tài khoản có quyền xem nhiều (nhưng không phải tất cả) site, gọi API không truyền
  `siteId`.
- **Kỳ vọng theo code hiện tại:** bị chặn 403, yêu cầu chọn rõ 1 site.

### 6. Site-scope: tài khoản chỉ giới hạn đúng 1 site
- Đăng nhập tài khoản chỉ được gán 1 site, xem danh sách (không cần truyền siteId).
- **Kỳ vọng:** tự động lọc đúng site đó.

### 7. Site-scope: tài khoản chưa được gán site nào
- Đăng nhập tài khoản site-scoped nhưng chưa gán site nào.
- **Kỳ vọng:** danh sách trống, không lỗi.

### 8. Xem tọa độ GPS trên danh sách (không có lớp ẩn riêng)
- Với tài khoản đủ quyền xem danh sách, kiểm tra có thấy tọa độ GPS của từng bản ghi không.
- **Kỳ vọng theo code hiện tại:** thấy đầy đủ tọa độ nếu đã qua được `checkins:list` + site-scope.

---

## Ghi chú
Đã hỏi ý kiến chủ dự án về việc thêm filter `source`/`invalid_reason` — **quyết định: không cần cả
2**, giữ nguyên bộ filter hiện tại. Không có thay đổi code cho tính năng này. Case 5 (bảo mật) và
case 8 (mô hình quyền) là 2 điểm quan trọng cần xác nhận qua test live, còn lại rủi ro fail thấp.
Đã đóng — ĐÃ KHÓA.
