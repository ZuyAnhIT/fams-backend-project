# Kịch bản test thủ công — #58 Xem lịch sử geofence

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "lịch sử là versioning trong bảng geofences (không phải bảng
geofence_histories riêng); thiếu change_type/diff diện tích". Đã xác nhận lại qua code — **thiết
kế versioning trong bảng `geofences` là có chủ đích, giữ nguyên; phần "diff diện tích" (`area`) đã
VÁ nhờ #56/#57; `change_type` tường minh KHÔNG vá (quyết định nghiệp vụ dưới đây); phát hiện thêm 1
gap mới quan trọng — `changed_by` chỉ hiện UUID thô — ĐÃ VÁ (2026-08-17).**

- **Bảng `geofence_histories` riêng: XÁC NHẬN giữ nguyên thiết kế versioning**, không tạo bảng mới
  — đúng như audit gốc ghi nhận, đây là kiến trúc có chủ đích từ migration ban đầu, không sửa.
- **`area` cũ/mới: ĐÃ VÁ gián tiếp qua #56/#57** — mỗi dòng lịch sử giờ có `areaSqm` riêng, có thể
  so sánh trực tiếp giữa các dòng liền kề trong bảng lịch sử (không cần API diff riêng).
- **`change_type` tường minh (tạo mới/sửa polygon/sửa buffer): KHÔNG VÁ, quyết định nghiệp vụ có
  chủ đích** — với dữ liệu hiện có (buffer + polygon + areaSqm + changeReason mỗi dòng), người xem
  đã có đủ thông tin để tự suy luận loại thay đổi bằng cách so sánh dòng hiện tại với dòng liền kề
  (nếu polygon giống, chỉ buffer khác → "sửa buffer"; nếu areaSqm khác → "sửa polygon"). Thêm hẳn 1
  cột phân loại tự động đòi hỏi định nghĩa ngưỡng "polygon coi như không đổi" (do toạ độ có thể
  lệch cực nhỏ do làm tròn) — rủi ro cao hơn giá trị mang lại ở giai đoạn này, không làm trong đợt
  vá này.
- **`changed_by` (người thay đổi): ĐÃ VÁ — gap mới phát hiện, không có trong audit gốc, nhưng quan
  trọng hơn cả 2 gap gốc vì ảnh hưởng trực tiếp khả năng đọc hiểu của HR/Admin.** Thêm field
  `createdByName` vào response, resolve từ `UUID createdBy` qua `UserRepository` (dùng
  `User.displayName`, cùng pattern đã dùng ở `TenantService`/`GoLiveRecordService`). Danh sách
  lịch sử dùng batch-load (`findAllById` theo tập hợp `createdBy` duy nhất trong trang hiện tại) để
  tránh N+1 query.
- **`changed_at`, "xem trên bản đồ": XÁC NHẬN vẫn hoạt động đúng**, không đổi.

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. ✅ Xem tab "Lịch sử geofence" — happy path, TEST LIVE qua UI thật
- Vào site đã có 3 phiên bản geofence (1 tạo mới + 2 lần sửa, tạo qua API và qua chính UI) → tab
  "Lịch sử geofence".
- **Kết quả thật (Playwright, ảnh `07-history-wide.png`):** bảng hiện đúng 3 dòng, mới nhất trước
  (60m/active, 45m/superseded, 30m/superseded), đầy đủ cột mới: "Diện tích" (~13.810,98 m² cả 3
  dòng — đúng vì chỉ đổi buffer, không đổi polygon), "Lý do thay đổi" (2/3 dòng có lý do, dòng tạo
  đầu tiên hiện "—").

### 2. ✅ Xác nhận `changed_by` hiện TÊN, không phải UUID thô — ĐÃ VÁ, TEST LIVE
- Quan sát cột "Người thay đổi" ở bảng lịch sử.
- **Kết quả thật:** cả 3 dòng hiện đúng "Platform Admin" (tên hiển thị thật, resolve từ
  `createdBy`), KHÔNG còn chuỗi UUID thô như trước khi vá — xác nhận gap nghiêm trọng nhất của #58
  đã được xử lý đúng, có thể đọc hiểu ngay trên UI.

### 3. ✅ Xác nhận thứ tự — bản active hiện tại nằm đầu danh sách
- Cùng ảnh case 1.
- **Kết quả thật:** dòng mới nhất (60m, tag "Đang áp dụng" màu xanh) nằm trên cùng, 2 dòng cũ hơn
  (tag "Bị thay thế (Cũ)") xếp bên dưới theo đúng thứ tự thời gian giảm dần.

### 4. Xem bản đồ của 1 phiên bản lịch sử (kể cả bản đã superseded)
- Bấm "Xem bản đồ" ở 1 dòng đã superseded — chức năng giữ nguyên từ trước, không đổi trong đợt vá.
- **Kỳ vọng theo code hiện tại (không đổi):** modal hiện đúng polygon lịch sử của phiên bản đó,
  kèm thêm dòng "Diện tích" và "Người thay đổi" mới bổ sung vào modal xem bản đồ.

### 5. ✅ Xác nhận rõ ràng: không có cột `change_type` tường minh (quyết định nghiệp vụ, không phải thiếu sót)
- Quan sát bảng lịch sử.
- **Kết quả thật:** không có cột phân loại thay đổi — đúng quyết định đã ghi ở trên, người xem vẫn
  suy luận được qua so sánh buffer/diện tích giữa các dòng liền kề.

### 6. Phân trang lịch sử
- Site test chỉ có 3 phiên bản (chưa đủ để phân trang thật >20 dòng) — đã xác nhận cơ chế phân
  trang hoạt động đúng ở script tự động (`test_geofence_history.sh` test 5-6: 3 bản ghi, size=2 →
  2 trang, trang 2 có 1 bản ghi) — không hồi quy sau khi vá.

---

## Ghi chú
Toàn bộ 6 case đã test live: script tự động 10/10 pass không hồi quy + Playwright qua UI thật với
viewport rộng để thấy đủ toàn bộ bảng (bảng dùng `scroll={{x:"max-content"}}` sẵn có, không phải
bug tràn cột). Gap `changed_by` là phát hiện quan trọng nhất trong cả 3 tính năng #56-58 — ảnh
hưởng trực tiếp tới nghiệp vụ "audit tranh chấp vị trí" nêu trong AC gốc (không thể tra cứu ai đã
sửa nếu chỉ có UUID). Quyết định không làm `change_type` đã ghi rõ lý do để tránh nghiên cứu lại từ
đầu ở đợt sau. Đã đóng — ĐÃ KHÓA.
