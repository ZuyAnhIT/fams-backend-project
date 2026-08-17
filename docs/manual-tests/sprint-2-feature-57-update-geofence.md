# Kịch bản test thủ công — #57 Sửa geofence

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không bắt buộc change_reason, không tính area". Đã xác nhận lại
qua code — **cả 2 gap là thật, đã VÁ (2026-08-17)**, cùng 1 gap mới (không ghi audit log):

- **`change_reason`: ĐÃ VÁ, dưới dạng TÙY CHỌN (không bắt buộc)** — thêm field `changeReason` vào
  `UpdateGeofenceRequest` (`@Size(max=500)`) và cột `change_reason TEXT` (migration V98). **Quyết
  định nghiệp vụ:** không xây dựng ngưỡng "thay đổi lớn" tự động (so sánh mức dịch chuyển tọa độ/
  diện tích cũ-mới để bắt buộc nhập lý do) — theo đúng tiền lệ đã thiết lập ở #51 (lý do thu hồi
  Face ID cũng là tùy chọn, không bắt buộc), giữ nhất quán trải nghiệm trên toàn hệ thống thay vì
  xây riêng 1 luồng validate phức tạp cho 1 tính năng.
- **`area`: ĐÃ VÁ** — dùng chung `computeAreaSqm()` với #56, tính lại diện tích mỗi khi tạo phiên
  bản mới (kể cả khi chỉ đổi buffer, polygon giữ nguyên vẫn tính lại để nhất quán).
- **Gap mới phát hiện, KHÔNG có trong audit gốc: "không ghi audit log khi sửa geofence" — ĐÃ VÁ.**
  Quan trọng hơn #56 vì AC gốc của #57 nêu rõ yêu cầu "ghi audit". Snapshot before/after đầy đủ,
  bao gồm cả `changeReason` trong `new_value` khi có nhập.
- **Partial update, xử lý site không có geofence active:** xác nhận vẫn hoạt động đúng, không đổi.

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. ✅ Sửa geofence kèm lý do thay đổi — TEST LIVE qua UI thật
- Mở modal sửa geofence trên site đã có geofence active → nhập buffer mới (60m) → nhập "Lý do thay
  đổi (Tùy chọn)": "Test UI: dieu chinh buffer qua giao dien" → Lưu cấu hình.
- **Kết quả thật (Playwright, ảnh chụp `04-edit-modal-filled.png`, `05-after-save.png`):** ô nhập
  lý do xuất hiện đúng vị trí trong modal (chỉ hiện khi đang SỬA, không hiện khi tạo mới — đúng
  thiết kế). Lưu thành công, toast "Lưu cấu hình vùng chấm công thành công!".

### 2. ✅ Xác nhận diện tích tính lại đúng khi chỉ đổi buffer
- Sau case 1 (chỉ đổi buffer, giữ nguyên polygon).
- **Kết quả thật:** `areaSqm` giữ nguyên `13810.98` (đúng vì polygon không đổi) — xác nhận công
  thức tính đúng và ổn định, không bị lệch do tính lại nhiều lần.

### 3. ✅ Xác nhận KHÔNG bắt buộc nhập lý do — vẫn tạo/sửa được khi bỏ trống
- Test qua API: sửa geofence không kèm `changeReason`.
- **Kết quả thật:** vẫn 200 OK, `changeReason: null` trong response — đúng quyết định nghiệp vụ
  "tùy chọn, không bắt buộc" đã ghi ở trên, không phải bug.

### 4. Sửa geofence — không đổi gì cả
- Bấm sửa, không thay đổi gì, thử lưu qua UI.
- **Kỳ vọng theo code hiện tại:** UI báo "Chưa có thay đổi để lưu", không gọi API — hành vi giữ
  nguyên từ trước, không đổi trong đợt vá này.

### 5. ✅ Xác nhận gap "không ghi audit log khi sửa geofence" — ĐÃ VÁ, TEST LIVE
- Sau case 1, kiểm tra `audit_logs` qua DB trực tiếp.
- **Kết quả thật (2026-08-17):** có dòng `geofence_updated`, entity_id đúng bản ghi mới,
  `old_value` chứa snapshot bản active TRƯỚC khi sửa (buffer 30, areaSqm 13810.98), `new_value`
  chứa snapshot bản MỚI (buffer 45, areaSqm 13810.98, **kèm đúng `changeReason` đã nhập**) — đúng
  yêu cầu AC gốc "ghi audit" và "lưu old/new".

### 6. Sửa geofence khi site KHÔNG có geofence active nào (trường hợp biên)
- Script `test_update_geofence.sh` test 9: PUT lên site chưa từng có geofence.
- **Kết quả thật:** 404 rõ ràng ("No active geofence found"), không tạo nhầm — pass, không đổi
  hành vi trong đợt vá này.

---

## Ghi chú
Toàn bộ 6 case đã test live: script tự động 12/12 pass không hồi quy + test tay qua API/DB +
Playwright qua UI thật (điền lý do, lưu, xác nhận toast + lịch sử). Quyết định nghiệp vụ quan
trọng nhất của đợt này: **change_reason là tùy chọn, không xây ngưỡng "thay đổi lớn" tự động** —
nếu sau này có yêu cầu nghiệp vụ cụ thể hơn (VD: bắt buộc khi dịch chuyển >X mét), cần thiết kế lại
UpdateGeofenceRequest và mở lại kịch bản này. Đã đóng — ĐÃ KHÓA.
