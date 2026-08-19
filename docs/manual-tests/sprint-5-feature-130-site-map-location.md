# Kịch bản test thủ công — #130 Bản đồ site và vị trí hiện tại

**Nền tảng: Backend, Web Admin, Mobile App.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — bằng chứng: `GeofenceController`/`GeofenceService`
(polygon+buffer CRUD); thiếu: không có logic cảnh báo accuracy thấp hay ẩn polygon theo policy.
Audit lại code hiện tại (2026-08-18) — **audit gốc lỗi thời một phần**: bản đồ hiển thị vị trí/
site center/geofence trên Mobile App **đã tồn tại đầy đủ** (`CheckinLocationMap.tsx`, dùng
`react-native-maps`) từ trước, audit gốc chỉ nhắc tới CRUD backend nên bỏ sót phần UI đã có. 2 gap
còn lại (accuracy thấp, ẩn polygon theo policy) xác nhận **đúng và vẫn còn tới trước lần vá này**.

- **✅ Hiển thị vị trí/site center/geofence trên map: ĐÃ CÓ ĐẦY ĐỦ**, không phải gap.
- **❌ GAP thật #1: không cảnh báo GPS accuracy thấp** trước khi chấm công.
- **❌ GAP thật #2: không có khái niệm "ẩn polygon theo policy" ở BẤT KỲ đâu trong data model** —
  cần cột DB mới + migration, không phải chỉnh logic có sẵn.

## ✅ ĐÃ VÁ (2026-08-18) — theo quyết định người dùng (chọn "làm ngay", per-site, mặc định HIỆN)

- Migration `V106`: thêm `sites.hide_polygon_from_employee` (boolean, mặc định `false` — không
  đổi hành vi hiện có cho site nào chưa bật).
- `CheckinService.getAvailableSites` giờ truyền `hidePolygonFromEmployee` xuống
  `GeofenceInfo.coordinates` — khi bật, trả `coordinates=null` nhưng **vẫn giữ `bufferMeters`**
  (nhân viên vẫn biết phạm vi buffer gần đúng, chỉ không thấy hình dạng polygon chính xác).
- Web Admin: thêm toggle "Ẩn vùng geofence khỏi nhân viên" trong form sửa công trình.
- Mobile App: `CheckinLocationMap.tsx` thêm cảnh báo GPS accuracy thấp (ngưỡng 50m, khớp với
  ngưỡng "medium risk" backend đã dùng sẵn trong `CheckinService` GPS risk scoring — để cảnh báo
  hiện đúng lúc dữ liệu thật sự có nguy cơ bị đánh dấu `pending_review`).

---

## A. Test trên Backend

### 1. ✅✅ (Case quan trọng nhất) `hidePolygonFromEmployee=true` ẩn đúng polygon, giữ nguyên bufferMeters
- Setup: tạo site với `hidePolygonFromEmployee=true`, thêm geofence polygon + bufferMeters=30.
- **Kỳ vọng — xác nhận đúng qua live API call:** `GET /checkin/available-sites` (gọi bởi nhân
  viên) trả `geofence.coordinates=null`, `geofence.bufferMeters=30` (vẫn còn).

### 2. ✅ Site không bật cờ vẫn hiện polygon như cũ (không đổi hành vi mặc định)
- **Kỳ vọng — xác nhận qua live API call:** site thường (`hidePolygonFromEmployee=false`, mặc
  định) → `coordinates` vẫn đầy đủ như trước khi vá.

### 3. ✅ Tạo site với `hidePolygonFromEmployee=true` ngay từ lúc tạo, phản ánh đúng trong response

## B. Test trên Web Admin

### 4. ✅✅ ĐÃ TEST LIVE qua Playwright thật — toggle "Ẩn vùng geofence khỏi nhân viên"
- Mở form sửa công trình qua UI thật, xác nhận toggle hiện đúng vị trí (dưới "Chính sách xác thực
  chấm công") với đúng helper text, bật toggle (`aria-checked` false→true), lưu, nhận toast "Cập
  nhật công trình thành công!", không lỗi.

## C. Test trên Mobile App

### 5. ⏳ CẦN BẠN TEST THỦ CÔNG trên thiết bị/simulator — cảnh báo GPS accuracy thấp
- Ở nơi tín hiệu GPS yếu (trong nhà, tầng hầm) hoặc giả lập accuracy > 50m, xác nhận banner cảnh
  báo màu vàng hiện đúng trên bản đồ check-in, biến mất khi accuracy tốt hơn.
- Mở màn check-in tại 1 site đã bật `hidePolygonFromEmployee` (cần HR bật trước qua Web Admin) →
  xác nhận map KHÔNG hiện hình polygon nhưng vẫn hiện marker vị trí + tâm site.

---

## Ghi chú
Migration `V106` dùng chung cho cả #127 (quality score) và #130 (hide polygon) — 1 migration, 2
mục đích liên quan tới cùng đợt audit. `tsc --noEmit` sạch cả Web Admin lẫn Mobile App.
