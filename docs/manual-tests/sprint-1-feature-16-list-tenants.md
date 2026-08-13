# Kịch bản test thủ công — #16 Xem danh sách tenant

**Nền tảng: Backend, Web Admin** (Platform Admin only — user thường không quản trị được danh
sách tenant của người khác).

⚠️ **Gap đã biết** (xác nhận lại khi viết kịch bản này — vẫn còn nguyên): API `GET /tenants`
(danh sách) **không** trả kèm thông tin plan/subscription hiện tại — phải gọi riêng
`GET /tenants/{id}/detail` hoặc `GET /tenants/{id}/subscription` cho từng dòng mới biết. Case 5
dưới đây xác nhận lại gap này.

---

## A. Test trên Web Admin (đăng nhập Platform Admin)

### 1. Xem danh sách — happy path
- Vào màn quản trị Platform → "Danh sách công ty/tenant".
- **Kỳ vọng:** hiện danh sách các tenant (kể cả tenant seed sẵn), có phân trang.

### 2. Tìm kiếm theo tên/slug
- Gõ 1 phần tên công ty vào ô tìm kiếm.
- **Kỳ vọng:** danh sách lọc đúng theo từ khóa (không phân biệt hoa/thường).

### 3. Lọc theo trạng thái
- Lọc theo `active`/`trial`/`suspended`.
- **Kỳ vọng:** chỉ hiện đúng tenant thuộc trạng thái đã chọn.

### 4. Sắp xếp theo ngày tạo
- Đổi thứ tự sắp xếp (mới nhất trước / cũ nhất trước).
- **Kỳ vọng:** thứ tự danh sách đổi đúng.

### 5. Kiểm tra hiển thị plan/subscription (xác nhận gap đã nêu ở đầu file)
- Quan sát bảng danh sách xem có cột "Gói dịch vụ"/"Plan" không.
- **Kỳ vọng theo code hiện tại:** cột danh sách chính (`GET /tenants`) **không** có sẵn field
  plan/subscription — nếu UI vẫn hiển thị được plan ở bảng danh sách, khả năng là UI đang tự gọi
  thêm N request `/detail` cho từng dòng (không tối ưu, nhưng vẫn đúng dữ liệu) — quan sát xem có
  bị chậm/giật khi danh sách nhiều dòng không. Nếu UI **hoàn toàn không hiển thị** plan ở danh
  sách (phải bấm vào chi tiết mới thấy), đó là đúng theo gap đã biết — không phải bug mới, chỉ
  cần xác nhận và báo lại đúng thực tế bạn thấy.

### 6. Phân trang
- Nếu có ≥ 2 trang dữ liệu, chuyển trang.
- **Kỳ vọng:** dữ liệu đổi đúng, không lặp/lẫn dòng giữa các trang.

---

## Ghi chú
Toàn bộ case chưa được tôi tự test qua Playwright. Case 5 là trọng tâm để quyết định có cần tối
ưu API `GET /tenants` (gộp thêm plan/subscription vào 1 lần gọi) hay không.
