# Kịch bản test thủ công — #118 Cập nhật ảnh hưởng công

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "không refresh violation_count; không ghi audit". Đã xác
nhận lại qua code hiện tại — **1 điểm cần làm rõ lại (không hoàn toàn đúng/sai mà cần tách 2 ý), 1
điểm vẫn đúng, và phát hiện thêm 1 gap MỚI nghiêm trọng về điều kiện cho phép sửa:**

- **⚠️ LÀM RÕ: "refresh violation_count" — field `violation_count` KHÔNG TỒN TẠI trên
  `AttendanceSummary`** (chỉ có boolean `hasRandomCheckFailure`, không phải bộ đếm số) — nên yêu
  cầu literal của AC KHÔNG THỂ thực hiện được nếu không đổi schema. NHƯNG cơ chế refresh
  (`recomputeIfSummaryExists`) ĐÃ ĐƯỢC GỌI ĐÚNG (có comment ghi ngày vá 2026-08-07) — nghĩa là
  PHẦN "trigger tính lại" đã đúng, chỉ có "đếm số lượng vi phạm" là không tồn tại để mà refresh.
- **❌ GAP thật (xác nhận đúng, chung với #116/#117): KHÔNG ghi audit log.**
- **❌ GAP thật NGHIÊM TRỌNG, MỚI phát hiện, MÂU THUẪN TRỰC TIẾP với AC: "chỉ cập nhật khi
  confirmed/pending theo quyền" HOÀN TOÀN KHÔNG ĐƯỢC KIỂM TRA** — code KHÔNG có bất kỳ điều kiện
  nào chặn theo status, và Javadoc của chính controller còn ghi rõ ràng: *"Can be updated at any
  time (before or after resolution)"* — nghĩa là HR/hệ thống có thể đổi `affectsAttendance` của 1
  violation ĐÃ BỊ BỎ QUA (`dismissed`) hoặc ĐÃ ĐƯỢC XÁC NHẬN từ lâu, không có ràng buộc trạng thái
  nào cả. Đây KHÔNG PHẢI thiếu sót nhỏ mà là SỰ KHÁC BIỆT CÓ CHỦ ĐÍCH giữa code và AC (code viết
  ngược hẳn ý AC, không phải quên làm) — cần chủ dự án xác nhận đây là quyết định thiết kế đúng hay
  cần vá lại đúng theo AC.

---

## A. Test trên Backend

### 1. ✅ Cập nhật `affectsAttendance` cho violation đang `pending` (chưa xử lý)
- **Kỳ vọng:** cập nhật thành công, summary được tính lại.

### 2. ❌❌ (Case quan trọng nhất) Cập nhật `affectsAttendance` cho violation ĐÃ `dismissed`/
   `confirmed` từ trước
- Cập nhật violation đã xử lý xong từ lâu.
- **Kỳ vọng theo code hiện tại:** VẪN CẬP NHẬT ĐƯỢC bình thường, không bị chặn — xác nhận đúng gap
  mâu thuẫn AC, cần chủ dự án quyết định có nên chặn lại theo đúng AC hay giữ nguyên linh hoạt cho
  phép sửa bất kỳ lúc nào.

### 3. ⚠️ Xác nhận refresh trigger hoạt động (dù không có bộ đếm violation_count thật)
- Đổi `affectsAttendance` từ `true` → `false`, kiểm tra `attendance_summary` liên quan.
- **Kỳ vọng:** summary được tính lại (VD `hasRandomCheckFailure` cập nhật đúng), dù không có field
  đếm số lượng riêng như AC literal mô tả.

### 4. ❌ Xác nhận gap "không ghi audit log"
- Tương tự #116/#117.

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
**Trọng tâm khi test: case 2 — đây là điểm code VIẾT NGƯỢC HẲN với AC (có chủ đích, ghi rõ trong
Javadoc), không phải bug quên sửa. Bắt buộc phải hỏi chủ dự án: giữ nguyên "sửa được bất kỳ lúc nào"
(linh hoạt hơn, nhưng rủi ro HR vô tình đổi ảnh hưởng công của 1 vụ đã xử lý xong từ lâu) hay vá lại
đúng AC (chỉ sửa được khi `pending`/`confirmed`, khóa lại sau khi `dismissed`).** Case 4 (audit) xử
lý chung với #116/#117. Case 1, 3 rủi ro fail thấp, đã có `test_hr_attendance_impact.sh` phủ.
