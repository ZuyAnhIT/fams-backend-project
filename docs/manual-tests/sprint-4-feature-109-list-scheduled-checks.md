# Kịch bản test thủ công — #109 HR xem danh sách scheduled checks

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG — "ScheduledCheckController.list". Đã xác nhận lại qua code hiện
tại — **CẦN HẠ TRẠNG THÁI xuống 🟡 LÀM MỘT PHẦN, có 1 gap thật audit gốc đã bỏ sót:**

- **✅ Lọc đúng: `siteId`, `employeeId`, `status`, `dateFrom`, `dateTo` — đủ 5/6 tiêu chí AC yêu
  cầu**, có sort theo `scheduledAt`, phân trang, hiển thị response nếu có.
- **❌ GAP thật, audit gốc CHƯA TỪNG GHI NHẬN: KHÔNG lọc được theo `trigger_type`** — vì bản thân
  field này không tồn tại (đã xác nhận ở #108, chỉ suy luận ngầm qua `checkIndex`), nên endpoint
  danh sách ĐƯƠNG NHIÊN không thể có tham số lọc theo nó. HR muốn xem riêng "các check do tôi chủ
  động kích hoạt" so với "check hệ thống tự sinh" không có cách lọc trực tiếp qua UI/API.
- **Web Admin (`ScheduledChecksPage.tsx`) khớp đúng khả năng backend** — có đủ filter site/nhân
  viên/status/khoảng ngày, KHÔNG có filter trigger_type — đúng, không phải lỗi riêng của FE.

---

## A. Test trên Backend

### 1. ✅ Lọc theo từng tiêu chí: site, employee, status, khoảng ngày
- Test lần lượt từng filter + kết hợp nhiều filter cùng lúc.
- **Kỳ vọng:** kết quả đúng theo điều kiện lọc (AND).

### 2. ✅ Sort theo `scheduledAt`, phân trang đúng
- **Kỳ vọng:** thứ tự đúng, `page`/`size`/tổng số đúng.

### 3. ✅ Hiển thị response kèm theo nếu check đã được phản hồi
- **Kỳ vọng:** mỗi dòng có đủ thông tin phản hồi (nếu có) mà không cần gọi API riêng.

### 4. ❌ Xác nhận gap "không lọc được theo trigger_type"
- Thử truyền tham số lọc phân biệt manual/auto vào API danh sách.
- **Kỳ vọng theo code hiện tại:** không có tham số nào tồn tại cho việc này — xác nhận đúng gap MỚI
  phát hiện.

---

## B. Test trên Web Admin
- Trang danh sách scheduled checks: xác nhận đủ 4 filter (site/nhân viên/status/khoảng ngày) hoạt
  động đúng qua UI thật, phân trang, hiển thị response inline.

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
**Hạ trạng thái từ "✅ đã xong" (audit gốc) xuống "🟡 làm một phần"** do phát hiện gap MỚI không có
trong audit gốc — nguyên nhân gốc rễ chung với #108 (thiếu field `trigger_type` thật). Nếu quyết
định vá #108 (thêm field trigger_type rõ ràng), nên vá luôn filter cho #109 trong cùng đợt vì cùng
1 thay đổi schema. Case 1-3 rủi ro fail thấp, đã có `test_list_scheduled_checks.sh` phủ.
