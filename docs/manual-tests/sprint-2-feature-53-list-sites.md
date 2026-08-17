# Kịch bản test thủ công — #53 Danh sách công trình

**Nền tảng: Backend, Web Admin, Mobile App.**

ℹ️ Audit gốc (07-22) ghi thiếu: "thiếu filter province/workspace (trường không tồn tại)". Đã xác
nhận lại qua code hiện tại:

- **Filter `province`/`workspace`: KHÔNG THỂ có** — cùng lý do kiến trúc với #52, các trường này
  không tồn tại trên `Site`. Không phải thiếu triển khai filter, mà là không có dữ liệu để lọc.
- **Sort theo `start_date`: KHÔNG THỂ có** — `Site` không có trường `start_date`/ngày khởi công.
  Danh sách trường sort hợp lệ hiện tại: `name, code, status, timezone, createdAt, updatedAt`. Đây
  là AC lỗi thời, không phải gap.
- **Tìm kiếm theo code/name/address + lọc status: HOẠT ĐỘNG ĐÚNG.**
- **Phát hiện quan trọng ngoài AC gốc: có giới hạn theo site-scope (RBAC) chưa được nhắc tới** —
  tài khoản role site-scoped (VD: SITE_SUPERVISOR) chỉ thấy đúng site mình được gán trong danh
  sách, kể cả khi họ có quyền `sites:list` chung. Có cả trường hợp biên: nếu người này chưa được
  gán site nào, danh sách trả về rỗng thay vì lỗi hay hiện tất cả.

---

## A. Test trên Web Admin

### 1. ✅ Xem danh sách công trình — happy path, ĐÃ TEST LIVE
- Vào Công trình, quan sát danh sách.
- **Kết quả thật (2026-08-16):** test live qua API với tài khoản không bị giới hạn site-scope —
  trả về đúng toàn bộ 2 site của tenant test, đúng thông tin.

### 2. Tìm kiếm theo tên/mã/địa chỉ
- Nhập từ khóa khớp 1 phần tên, mã, hoặc địa chỉ của 1 site.
- **Kỳ vọng:** kết quả lọc đúng theo từ khóa.

### 3. Lọc theo trạng thái active/inactive
- Áp filter trạng thái, quan sát kết quả.
- **Kỳ vọng:** chỉ hiện đúng site khớp trạng thái đã chọn.

### 4. Phân trang
- Nếu tenant có đủ nhiều site để phân trang, chuyển trang, đổi kích thước trang.
- **Kỳ vọng:** hoạt động đúng, không trùng/thiếu dữ liệu giữa các trang.

### 5. ✅ Xác nhận giới hạn theo site-scope — tính năng quan trọng ngoài AC gốc
- Đăng nhập tài khoản có role site-scoped (VD: SITE_SUPERVISOR chỉ được gán 1-2 site cụ thể), vào
  danh sách công trình.
- **Kỳ vọng theo code hiện tại:** CHỈ thấy đúng site mình được gán, dù có quyền `sites:list` — xác
  nhận đúng cơ chế cô lập dữ liệu theo site, quan trọng cho bảo mật vận hành đa công trình.

### 6. Tài khoản site-scoped nhưng chưa được gán site nào
- Nếu có tài khoản role site-scoped nhưng chưa gán vào site nào, đăng nhập và xem danh sách.
- **Kỳ vọng theo code hiện tại:** danh sách rỗng (không lỗi, không hiện toàn bộ site).

### 7. Xác nhận không có filter province/workspace, không sort được theo start_date
- Quan sát các tùy chọn filter và sort trên UI danh sách.
- **Kỳ vọng theo code hiện tại:** KHÔNG có filter tỉnh/thành hay workspace, KHÔNG có tùy chọn sort
  theo ngày khởi công — khớp đúng gap kiến trúc, không phải thiếu UI.

---

## Ghi chú
Case 5-6 là trọng tâm mới, quan trọng hơn các case gốc — đây là kiểm soát bảo mật dữ liệu giữa các
site/supervisor khác nhau trong cùng tenant, ảnh hưởng trực tiếp tới việc lộ thông tin công trình
không thuộc phạm vi quản lý. Case 7 xác nhận đúng gap kiến trúc, không chặn khóa. Case 1-4 rủi ro
fail thấp.
