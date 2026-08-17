# Kịch bản test thủ công — #56 Tạo geofence cho công trình

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không tính area_sqm". Đã xác nhận lại qua code hiện tại — **gap
`area_sqm` là thật, đã VÁ (2026-08-17)**, cùng đợt với 1 gap mới phát hiện (không ghi audit log).

- **`area_sqm`: ĐÃ VÁ** — thêm cột `area_sqm` (migration `V98__geofence_area_change_reason.sql`,
  kiểu `DOUBLE PRECISION`), `GeofenceService.computeAreaSqm()` tính diện tích polygon bằng công
  thức shoelace trên hệ tọa độ phẳng quy chiếu equirectangular tại vĩ độ trung bình của polygon
  (độ chính xác đủ cho quy mô công trình, sai số dưới 1% với diện tích vài km² trở xuống — không
  cần tính geodesic đầy đủ ở quy mô này). Tính khi tạo, trả về trong response và hiển thị UI.
- **Bảng `geofence_histories` riêng: XÁC NHẬN vẫn không tồn tại, giữ nguyên thiết kế versioning
  trong bảng `geofences`** — đây là kiến trúc có chủ đích (comment trong migration gốc), không sửa.
- **"Chỉ một geofence active": XÁC NHẬN vẫn hoạt động đúng**, có 2 lớp bảo vệ (service +
  unique index từng phần ở DB) — không đổi trong đợt vá này.
- **Gap mới phát hiện, KHÔNG có trong audit gốc: "không ghi audit log khi tạo geofence" — ĐÃ VÁ.**
  Thêm `AuditLogService.record(...)` action `geofence_created`, snapshot gồm siteId/bufferMeters/
  areaSqm/pointCount/status.

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. ✅ Tạo geofence — happy path, TEST LIVE
- API: `POST .../sites/{id}/geofences` với polygon ~100m×130m thực tế gần Hồ Hoàn Kiếm, buffer 30m.
- **Kết quả thật:** tạo thành công (201), `areaSqm: 13810.98` — khớp tính tay (chiều rộng
  ~124.5m × chiều cao ~111.3m ≈ 13857 m², sai lệch <1% do polygon không phải hình chữ nhật lý
  tưởng). Test qua UI thật (Playwright): thẻ "Vùng chấm công" hiện đúng
  "Sai số GPS cho phép: 45 mét · Diện tích: ~13.810,98 m²".

### 2. ✅ Xác nhận diện tích hiển thị đúng trên UI — ĐÃ VÁ, TEST LIVE
- Trước đây gap này khiến UI không có số liệu diện tích. Sau khi vá: `ActiveGeofenceCard.tsx` hiện
  diện tích ngay cạnh buffer, `GeofenceHistoryTab.tsx` có thêm cột "Diện tích" riêng.
- **Kết quả thật:** cả 2 nơi hiển thị đúng `~13.810,98 m²` (định dạng số Việt Nam qua
  `toLocaleString("vi-VN")`).

### 3. Vẽ polygon không hợp lệ (ít hơn 3 điểm phân biệt, hoặc chưa đóng ring)
- Test qua script `tests/site/test_create_geofence.sh` (test 6-7): thiếu coordinates, polygon <4
  điểm.
- **Kết quả thật:** 400 rõ ràng, không tạo được geofence rác. Pass.

### 4. Nhập buffer âm
- Test qua script (test 8): `bufferMeters: -10`.
- **Kết quả thật:** 400 (`@Min(0)` chặn đúng). Pass.

### 5. ✅ Tạo geofence mới khi site đã có geofence active — TEST LIVE
- Script test 4-5: tạo geofence thứ 2 trên cùng site.
- **Kết quả thật:** geofence mới trở thành active, geofence cũ chuyển "superseded" — xác nhận đúng
  cơ chế versioning, không mất dữ liệu (vẫn xem được ở tab Lịch sử — #58).

### 6. ✅ Xác nhận gap "không ghi audit log khi tạo geofence" — ĐÃ VÁ, TEST LIVE
- Sau case 1, kiểm tra bảng `audit_logs` trực tiếp qua DB.
- **Kết quả thật (2026-08-17):** có đúng 1 dòng `geofence_created`, entity `Geofence`, đúng
  entity_id, `new_value` chứa `{"siteId":..., "status":"active", "areaSqm":13810.98,
  "pointCount":5, "bufferMeters":30}` — khớp chính xác dữ liệu vừa tạo.

---

## Ghi chú
Toàn bộ 6 case đã test live (script tự động 13/13 pass không hồi quy + test tay qua API/DB +
Playwright qua UI thật). Cả 2 gap (area_sqm, audit log) đã vá và xác nhận qua UI thật, không chỉ
qua DB. Backend đã rebuild + chạy migration `V98` thành công trên môi trường Docker thật
(`fams-api`/`fams-postgres`), không phải mock. Đã đóng — ĐÃ KHÓA.
