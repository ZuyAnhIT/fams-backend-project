# Kịch bản test thủ công — #50 HR xem trạng thái Face ID

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi "ĐÃ XONG". Đã xác nhận lại qua code hiện tại — **về cơ bản đúng, nhưng có
1 điểm AC không thực hiện được (kiến trúc không hỗ trợ) và 1 tính năng lớn không có trong AC:**

- **Danh sách + lọc theo `face_registered`/trạng thái: ĐÚNG** — endpoint
  `GET /tenants/{id}/reports/face-id/enrollment` hỗ trợ filter theo trạng thái
  (enrolled/pending/not_enrolled/revoked), phòng ban, tìm kiếm, phân trang; có thẻ thống kê tổng
  quan (tổng/đã đăng ký/chờ duyệt/chưa đăng ký/đã thu hồi).
- **`enrolledAt`: CÓ**, hiển thị đúng trong bảng báo cáo.
- **`quality_score`: KHÔNG THỂ có** — trường này không tồn tại trong toàn bộ hệ thống (xem thêm
  #49), không phải do quên implement mà do khác kiến trúc AI hoàn toàn (InsightFace local, không
  phải AWS Rekognition có điểm chất lượng riêng). Cần loại bỏ khỏi AC hoặc thay bằng chỉ số khác
  nếu muốn có thật.
- **"Không hiển thị ảnh nếu thiếu quyền": ĐÚNG nhưng nằm ở endpoint khác** — báo cáo
  `/reports/face-id/enrollment` vốn không bao giờ trả ảnh (nên "không hiển thị nếu thiếu quyền" là
  hiển nhiên đúng). Ảnh thật nằm ở endpoint riêng
  `GET .../employees/{id}/face-id/pending-review/photo`, gate bằng quyền `face_id:manage` VÀ
  site-scope (HR bị giới hạn theo site chỉ xem được ảnh nhân viên thuộc site họ quản lý).
- **Phát hiện lớn ngoài AC gốc**: Web Admin có hẳn 1 tab "Chờ duyệt (N)" riêng
  (`FaceIdPendingReviewTab.tsx`) cho phép HR xem ảnh tham chiếu + duyệt/từ chối trực tiếp — đây là
  phần việc thật sự quan trọng của "xem trạng thái Face ID" trong thực tế vận hành, nhiều hơn hẳn
  so với 1 bảng báo cáo tĩnh mà AC gốc mô tả.
- **Không cần sửa code cho #50 riêng** — đây là tính năng đọc dữ liệu (report), không có mutation
  nào để thêm audit log. Gap chung "không ghi audit" của epic Face ID nằm ở #48/#49/#51 (nơi có
  hành động ghi dữ liệu), đã vá ở các kịch bản đó.

---

## A. Test trên Web Admin

### 1. Xem báo cáo tổng quan Face ID — happy path
- Vào Báo cáo → Đăng ký Face ID.
- **Kỳ vọng:** hiện đúng các thẻ thống kê (tổng/đã đăng ký/chờ duyệt/chưa đăng ký/đã thu hồi), bảng
  danh sách nhân viên kèm trạng thái.

### 2. Lọc theo trạng thái Face ID
- Áp filter "Chưa đăng ký" → quan sát kết quả; đổi sang "Đã đăng ký" → quan sát lại.
- **Kỳ vọng:** kết quả đúng theo từng trạng thái, khớp với số liệu ở thẻ thống kê.

### 3. Lọc theo phòng ban + tìm kiếm
- Áp thêm filter phòng ban và ô tìm kiếm tên/mã NV.
- **Kỳ vọng:** kết quả thu hẹp đúng theo cả 2 điều kiện kết hợp.

### 4. Xem `enrolledAt` của nhân viên đã đăng ký
- Với 1 nhân viên trạng thái "Đã đăng ký", xem cột/chi tiết ngày đăng ký.
- **Kỳ vọng:** hiển thị đúng thời điểm được duyệt (enrolledAt), không phải thời điểm nộp ảnh lần
  đầu (submittedAt, nếu 2 mốc này khác nhau do có luồng duyệt ở giữa).

### 5. ✅ Xác nhận luồng duyệt tại tab nhân viên — tính năng lớn ngoài AC gốc — ĐÃ TEST LIVE
- Chuyển sang tab/view "Chờ duyệt (N)" (dạng danh sách tổng, chưa test lại lần này), hoặc — như đã
  test live 2026-08-16 — vào thẳng tab "Sinh trắc học" của 1 nhân viên có hồ sơ đang pending.
- **Kết quả thật (Playwright, giao diện thật):** ảnh tham chiếu thật (không phải placeholder) hiện
  đúng kèm dòng "Bấm vào ảnh để xem lớn và xác minh đúng nhân viên trước khi quyết định"; nút "Duyệt
  hồ sơ" hoạt động, chuyển trạng thái "Đã đăng ký" ngay sau khi bấm — xác nhận đúng thiết kế chống
  duyệt mù.

### 6. ✅ Xác nhận chặn xem ảnh khi thiếu quyền/ngoài site-scope
- Đăng nhập tài khoản HR bị giới hạn theo site (site-scoped), thử xem ảnh tham chiếu của 1 nhân
  viên KHÔNG thuộc site được quản lý.
- **Kỳ vọng theo code hiện tại:** bị chặn (403/không tải được ảnh) — xác nhận đúng AC "không hiển
  thị ảnh nếu thiếu quyền", cụ thể hóa bằng site-scope chứ không chỉ role permission chung chung.

### 7. ⚠️ Xác nhận gap "không có quality_score"
- Quan sát toàn bộ báo cáo và chi tiết từng nhân viên.
- **Kỳ vọng theo code hiện tại:** KHÔNG có điểm chất lượng nào hiển thị ở đâu cả — xác nhận đúng
  gap kiến trúc, không phải lỗi thao tác.

### 8. Xuất/tải báo cáo (nếu UI có hỗ trợ)
- Nếu có nút xuất Excel/CSV cho báo cáo này, thử xuất.
- **Kỳ vọng:** file tải về khớp đúng dữ liệu đang lọc trên UI (nếu tính năng này không tồn tại, ghi
  lại "không có" — không phải gap, chỉ ghi nhận).

---

## Ghi chú
Case 5-6 là trọng tâm mới, quan trọng hơn cả các case gốc — đây là kiểm soát chất lượng dữ liệu
sinh trắc học thật trước khi đưa vào chấm công. Case 7 xác nhận gap kiến trúc không chặn khóa
(quality_score không tồn tại, không phải thiếu sót triển khai). Case 1-4 rủi ro fail thấp, chỉ xác
nhận lại tính năng đã "ĐÃ XONG" từ audit gốc vẫn đúng qua UI thật.
