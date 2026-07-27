# Tài liệu bàn giao UI: Web vs App và ẩn/hiện theo vai trò — Quản lý Công trình (Site) & Geofence

> Cập nhật theo code đang chạy ngày 26/07/2026. Đây **không phải** tài liệu API — chi tiết request/response đã có ở `docs/api/site-geofence-management-api.md`, tài liệu này chỉ trả lời 2 câu hỏi khi dựng giao diện:
> 1. Tính năng này thuộc **Web** hay **App**?
> 2. Với từng vai trò đăng nhập, phần tử nào **ẩn hẳn**, phần tử nào **hiện nhưng disable**, phần tử nào **hiện đầy đủ**?

Phạm vi: 7 tính năng bạn yêu cầu (tạo/danh sách/chi tiết/cập nhật công trình, tạo/sửa/xem lịch sử geofence) + 1 tính năng bổ sung (xóa công trình).

## 1. Kết luận nhanh: App hay Web?

**Toàn bộ 100% Web-only cho phần quản trị** — vẽ polygon trên bản đồ, quản lý danh sách công trình là công cụ HR/Admin/Giám sát, cần màn hình lớn và thao tác bản đồ chính xác, không phù hợp thao tác trên điện thoại. **Nhưng khác với Workspace, Site/Geofence CÓ ảnh hưởng gián tiếp tới trải nghiệm App** — nhân viên dùng App để chấm công, và geofence chính là thứ quyết định app có chấp nhận vị trí GPS hay không. Điều này không có nghĩa là cần dựng thêm màn hình Geofence trên App — chỉ cần biết để không nhầm "app không hiển thị bản đồ geofence cho nhân viên" là thiếu tính năng.

| Tính năng | Nền tảng |
|---|---|
| Tạo công trình | **Web only** |
| Danh sách công trình (tìm/lọc/sort/phân trang) | **Web only** |
| Xem chi tiết công trình (site+geofence+ca+nhân viên) | **Web only** (Admin/HR); **App gián tiếp** — xem mục 1.1 |
| Cập nhật công trình | **Web only** |
| Xóa công trình (mới) | **Web only** |
| Tạo geofence (vẽ polygon) | **Web only** — cần bản đồ tương tác, không phù hợp App |
| Sửa geofence | **Web only** |
| Xem lịch sử geofence (audit timeline) | **Web only** |

**Không có màn hình Site/Geofence nào cần dựng trên Mobile App.**

## 1.1 Vai trò gián tiếp của Site/Geofence trong App (không phải màn hình mới — chỉ để bạn hiểu đúng luồng)

- **Màn "Chọn công trình để chấm công"** (đã có, thuộc module Check-in — xem tài liệu check-in nếu có) dùng `GET /checkin/available-sites`, danh sách này được lọc theo `Assignment` (site + nhân viên + ca + `daysOfWeek`) — không gọi trực tiếp API Site/Geofence trong tài liệu này.
- **Khi nhân viên bấm "Check-in"**: App chỉ gửi tọa độ GPS hiện tại lên server; server (không phải app) tự so khớp với polygon+buffer của geofence đang active để quyết định check-in có hợp lệ hay bị đánh dấu "pending_review". **App không cần vẽ hay hiển thị polygon geofence cho nhân viên** — chỉ cần hiển thị kết quả trả về (hợp lệ / cần giám sát viên duyệt lại).
- Nếu sau này bạn muốn App hiện "bạn đang cách vùng chấm công bao xa" (UX nâng cao, chưa có trong 7 tính năng bạn yêu cầu), sẽ cần 1 API mới trả toạ độ tâm + bán kính cho riêng mục đích hiển thị — hiện tại chưa có, không nằm trong phạm vi review lần này.

## 2. Ma trận tổng hợp: Tính năng × Vai trò (Company Portal)

Ký hiệu: **Full** = thao tác đầy đủ · **View** = chỉ xem · **Ẩn** = không hiện nút/màn.

| # | Tính năng | Endpoint chính | TENANT_ADMIN | HR_MANAGER | SITE_SUPERVISOR |
|---|---|---|---|---|---|
| 1 | Tạo công trình | `POST /sites` | Full | Full | Ẩn |
| 2 | Danh sách công trình | `GET /sites` | Full | Full | **View** (chỉ thấy site được giao — site-scope) |
| 3 | Chi tiết công trình | `GET /sites/{id}` | Full | Full | **View** (chỉ site được giao) |
| 4 | Cập nhật công trình | `PUT /sites/{id}` | Full | Full | Ẩn |
| 5 | Xóa công trình (mới) | `DELETE /sites/{id}` | Full | Full | Ẩn |
| 6 | Tạo geofence | `POST .../geofences` | Full | Full | Ẩn |
| 7 | Sửa geofence | `PUT .../geofences/active` | Full | Full | Ẩn |
| 8 | Xem geofence hiện tại + lịch sử | `GET .../geofences/active`, `GET .../geofences` | Full | Full | **View** (chỉ site được giao — MỚI chặn đúng ở đợt này) |

**Khác biệt quan trọng với Workspace**: `SITE_SUPERVISOR` **không bị ẩn hoàn toàn** như ở Workspace — vai trò này vốn được seed sẵn `sites:read/list` và `geofences:read` (chỉ xem, không tạo/sửa/xóa), và bị giới hạn theo đúng site được giao khi gán role (`siteIds`). Trước đợt sửa lần này, phần giới hạn site cho geofence **chưa hoạt động** (xem mục 2.1 tài liệu API) — giờ đã đúng, nên FE cần đảm bảo danh sách/chi tiết geofence hiển thị cho SITE_SUPERVISOR tự động chỉ còn đúng site họ phụ trách, không cần tự lọc thêm ở FE.

## 3. Chi tiết ẩn/hiện và các trạng thái nút cần lưu ý

### 3.1 Nút "Xóa công trình"

- **Hiện nhưng disable + tooltip** khi `activeAssignmentCount > 0` (có sẵn trong `GET /sites/{id}` — không cần gọi thêm API): "Không thể xóa: còn N nhân viên đang được phân công. Hãy kết thúc hoặc chuyển phân công trước."
- **Enable + xác nhận** khi `activeAssignmentCount = 0`.
- Lưu ý: xóa công trình **không** xóa lịch sử geofence hay shift template đã tạo cho nó — nếu FE có màn xem lại lịch sử theo site đã xóa (hiếm khi cần), vẫn gọi được các API cũ bình thường vì site chỉ bị xóa mềm.

### 3.2 Màn "Vẽ geofence" (map picker)

- Bắt buộc khép polygon lại đúng điểm bắt đầu trước khi submit (đóng vòng) — **BE giờ đã validate thật điều này** (`400` nếu hở), nhưng nên tự đóng vòng ở FE trước khi gửi (hầu hết thư viện vẽ bản đồ như Leaflet Draw, Mapbox GL Draw đều tự đóng vòng polygon mặc định) để tránh người dùng thấy lỗi validate không cần thiết.
- Trường `bufferMeters` nên có input số riêng, mặc định 0, kèm giải thích ngắn: "Khoảng cách sai số GPS cho phép quanh ranh giới vẽ" — đây chính là dung sai giúp chấm công không bị từ chối oan khi GPS lệch nhẹ.
- Khi sửa geofence: cho phép người dùng CHỈ đổi `bufferMeters` mà giữ nguyên polygon cũ (không bắt buộc vẽ lại) — API đã hỗ trợ đúng kiểu partial update này (field nào không gửi thì giữ nguyên từ bản active hiện tại).

### 3.3 Màn "Lịch sử geofence" (audit timeline)

- Hiện dạng danh sách theo thời gian giảm dần, mỗi dòng: người tạo, thời điểm, trạng thái (`active`/`superseded`), và preview nhỏ polygon trên bản đồ mini nếu có thể.
- Dòng có `status="active"` nên có badge nổi bật (ví dụ viền xanh) để phân biệt với các phiên bản cũ.
- **Chưa có** diff chi tiết "đổi gì so với bản trước" — nếu cần, xem mục 6 tài liệu API (đề xuất, chưa làm) trước khi tự dựng UI diff phức tạp ở FE.

### 3.4 Site-scope cho SITE_SUPERVISOR (áp dụng cho mục 2, 3, 8 ở bảng trên)

- Danh sách công trình và geofence trả về cho SITE_SUPERVISOR đã tự động lọc đúng phạm vi — FE không cần tự so sánh `siteId` với danh sách site được giao.
- Nếu SITE_SUPERVISOR cố truy cập trực tiếp URL của 1 site ngoài phạm vi (gõ tay hoặc qua link cũ), API trả `403` — FE nên bắt lỗi này và hiện thông báo "Bạn không có quyền xem công trình này" thay vì màn trắng/lỗi kỹ thuật chung chung.

## 4. Sơ đồ nav đề xuất

```text
COMPANY PORTAL (fams-front-web-project) — TENANT_ADMIN / HR_MANAGER / SITE_SUPERVISOR
└── Công trình (Sites)
    ├── Danh sách — tìm/lọc/sort/phân trang (SITE_SUPERVISOR chỉ thấy site được giao)
    │   └── Nút "Tạo công trình" — ẩn với SITE_SUPERVISOR
    └── Click 1 dòng → Chi tiết
        ├── Thông tin công trình + nút "Sửa" — ẩn nút Sửa với SITE_SUPERVISOR
        ├── Tab "Geofence" — bản đồ hiện polygon đang active + nút "Vẽ lại"/"Sửa buffer" (ẩn với SITE_SUPERVISOR)
        ├── Tab "Lịch sử geofence" — timeline các phiên bản (SITE_SUPERVISOR xem được, không sửa)
        ├── Tab "Ca làm việc" (shift templates — đã có tài liệu riêng nếu cần)
        ├── Tab "Nhân viên đang phân công" (activeAssignmentCount + danh sách)
        └── Nút "Xóa công trình" — chỉ TENANT_ADMIN/HR_MANAGER, disable nếu còn assignment active

MOBILE APP — không có màn Site/Geofence nào; chỉ tiêu thụ gián tiếp qua "Chọn công trình chấm công"
(thuộc module Check-in, không thuộc phạm vi tài liệu này)
```

## 5. Checklist bàn giao frontend

- [ ] Route "Công trình" **ẩn hoàn toàn** với ai không có `sites:list` — SITE_SUPERVISOR vẫn thấy route này (họ có `sites:list`/`sites:read`), chỉ ẩn các nút tạo/sửa/xóa.
- [ ] Nút "Xóa công trình" disable + tooltip khi `activeAssignmentCount > 0`, dùng field có sẵn trong response, không gọi thêm API.
- [ ] Map picker vẽ geofence nên tự đóng vòng polygon trước khi submit — BE giờ validate thật việc này, tránh để người dùng thấy lỗi 400 vì thao tác vẽ tay không khép kín.
- [ ] SITE_SUPERVISOR xem danh sách/chi tiết geofence tự động giới hạn đúng site được giao — nếu FE trước đây tự lọc thêm ở client, có thể bỏ lớp lọc đó (BE đã xử lý đúng, đơn giản hóa code FE).
- [ ] Bắt lỗi `403` khi SITE_SUPERVISOR cố truy cập site ngoài phạm vi (qua URL trực tiếp) — hiện thông báo rõ ràng, không phải lỗi hệ thống chung chung.
- [ ] Không dựng màn Geofence trên Mobile App — nhân viên chỉ cần thấy kết quả chấm công (hợp lệ/cần duyệt), không cần thấy polygon.
