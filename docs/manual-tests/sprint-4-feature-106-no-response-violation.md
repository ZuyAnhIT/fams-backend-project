# Kịch bản test thủ công — #106 Tạo violation khi không phản hồi

**Nền tảng: Backend, Web Admin, Mobile App, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "không gửi notification cho HR/nhân viên". Đã xác nhận lại
qua code hiện tại — **ĐÚNG, KHÔNG lỗi thời:**

- **`NoResponseViolationService.processChecks()`** — quét đúng các check `status=sent` đã quá
  `expiresAt`, set `status=no_response`, tạo violation `type=no_response`, có guard chống trùng lặp
  (idempotent, chạy lại job không tạo violation thừa).
- **❌ GAP thật: KHÔNG gửi notification cho HR/nhân viên** — xác nhận qua toàn bộ
  `NoResponseViolationService`/`NoResponseViolationJob`: không có lệnh gọi tạo notification nào.
  Trong CẢ module Random Check, CHỈ `RandomCheckDispatchService` gửi notification (lúc gửi yêu cầu
  kiểm tra ban đầu) — không có notification nào cho các bước SAU đó (vi phạm, kết quả).

---

## A. Test trên Backend

### 1. ✅ Tạo đúng violation `no_response` khi check hết hạn không phản hồi
- Để 1 check `status=sent` quá `expiresAt` không ai phản hồi, chờ job quét (mỗi 2 phút).
- **Kỳ vọng:** `status` chuyển `no_response`, có violation `type=no_response`.

### 2. ✅ Idempotent — job chạy lại không tạo trùng violation
- Chạy job quét 2 lần liên tiếp cho cùng 1 check đã xử lý.
- **Kỳ vọng:** vẫn chỉ 1 violation duy nhất.

### 3. ❌ Xác nhận gap "không gửi notification"
- Sau khi violation `no_response` được tạo, kiểm tra hộp thư HR lẫn nhân viên liên quan.
- **Kỳ vọng theo code hiện tại:** KHÔNG có notification nào được gửi — xác nhận đúng gap, cả 2 phía
  đều phải tự vào Web Admin/App tra cứu mới biết có vi phạm không phản hồi.

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
Trọng tâm khi test: case 3 (gap notification — **chung gốc rễ với #107**, cả 2 tính năng đều thiếu
notification khi tạo violation, nên xử lý đồng thời 1 lần cho cả 2 nếu quyết định vá, tránh làm 2
lần riêng lẻ). Case 1-2 rủi ro fail thấp, đã có `test_no_response_violation.sh` phủ.
