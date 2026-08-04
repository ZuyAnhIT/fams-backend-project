# Tài liệu bàn giao UI: Web vs App — Random Check (Kiểm tra ngẫu nhiên nhân viên hiện trường)

> Cập nhật theo code đang chạy ngày 01/08/2026 (bản vá lần 2 — phản hồi trực tiếp báo cáo audit code của team Web/App gửi 31/07/2026: `22_BAO_CAO_RANDOM_CHECK_WEB_2026-07-31.md`, `26_BAO_CAO_APP_RANDOM_CHECK_2026-07-31.md`). Base path cấu hình: `/api/v1/tenants/{tenantId}/random-check-configs`. Base path vận hành/dispatch: `/api/v1/tenants/{tenantId}/scheduled-checks`. Chi tiết business-logic/lỗi đã sửa nằm ở `docs/api/random-check-config-review.md` — tài liệu này chỉ tập trung vào **những gì FE cần dựng trên giao diện**.

## 0.b Trả lời trực tiếp 5 điểm team Web/App đã báo cáo (31/07/2026) — bản vá lần 2

| # | Team báo cáo gì | Đã xác minh đúng? | Backend đã làm gì | FE cần làm gì |
|---|---|---|---|---|
| 1 (Web) | `faceVerifyScore` không có trong response chi tiết dù đã enroll DTO | ✅ Đúng | Đã map field còn thiếu | Không cần đổi gì — field giờ có giá trị thật thay vì `null` |
| 2 (Web) | Chi tiết 1 check thiếu `manualReason`/`triggeredBy` | ✅ Đúng | Đã thêm vào `GET /{checkId}` | Có thể bỏ code "merge dữ liệu từ row đã chọn" — response chi tiết giờ tự đủ |
| 3 (Web) | List thiếu tên nhân viên/site + outcome, phải gọi riêng | ✅ Đúng | Đã hydrate hàng loạt (không N+1) vào `GET /scheduled-checks` | Có thể bỏ cache directory riêng cho màn này nếu muốn, dùng thẳng field mới |
| 4 (App) | Không có endpoint cho nhân viên tự lấy kết quả cuối (chỉ HR gọi được `GET /{checkId}`) | ✅ Đúng, nghiêm trọng | **Mới**: `GET /{checkId}/my-result` — không cần quyền HR, tự bảo mật theo JWT | **BẮT BUỘC đổi**: đổi polling sang endpoint mới — xem mục 4.5 |
| 5 (App) | Thông báo `RANDOM_CHECK_SENT` không có `checkId` để deep-link | ✅ Đúng | Đã thêm `metadata` (checkId/siteId/expiresAt) vào notification trong `GET /notifications` | Đọc `metadata.checkId` từ notification thay vì mở danh sách chung — xem mục 4.6. Lưu ý: chưa áp dụng cho payload push FCM thô, xem giới hạn ở mục 4.6 |

Toàn bộ đã test sống qua API thật. Chi tiết kỹ thuật đầy đủ (kể cả 2 giới hạn đã biết, chưa làm) xem `random-check-config-review.md` mục 10.

## 0.c Bản vá lần 3 (01/08/2026) — 2 điểm thống nhất Backend/tài liệu (không chặn Web)

| # | Vấn đề | Kết luận | FE cần làm gì |
|---|---|---|---|
| 1 | Guide yêu cầu HR xem ảnh selfie, nhưng chưa có URL/endpoint nào trả ảnh | Xác nhận đúng — `fams-ai` đã lưu ảnh sẵn, chỉ thiếu route lấy lại | **Mới**: `GET /scheduled-checks/{checkId}/photo` + field `hasPhotoEvidence` — xem mục 3.8 |
| 2 | Guide nói `Assignment.role` tuỳ chỉnh được, Backend validate cứng `worker`/`supervisor` | Xác nhận Backend đúng, guide sai — đã sửa lại mô tả | Không đổi gì — Web giữ nguyên UI chỉ 2 lựa chọn như đang làm là đúng |

Chi tiết kỹ thuật đầy đủ xem `random-check-config-review.md` mục 11. Riêng ca qua đêm và thống kê Face ID theo site vẫn là giới hạn P2 đã biết, không đổi.

## 0.d Bản vá lần 4 (01/08/2026) — 3 điểm ưu tiên trước production (Web + App)

| # | Team yêu cầu gì | Backend đã làm gì | FE/App cần làm gì |
|---|---|---|---|
| 1 (P0, App) | `GET /my-pending` không nên trả check `pending` có `scheduledAt` xa trong tương lai — App có ẩn trên UI nhưng API vẫn lộ qua network | Đã giới hạn: check `pending` chỉ trả về khi còn ≤ 60 giây nữa mới tới giờ (cả mặc định lẫn `?status=pending`) | **Không cần đổi gì phía App** — response giờ tự nhỏ lại đúng ý UI hiện tại; nếu App có logic tự lọc thêm theo `scheduledAt` phía client thì có thể bỏ, backend đã đảm bảo |
| 2 (P1, App) | Gói push FCM chỉ có `title`/`body` — khi app bị tắt hoàn toàn không deep-link được, chỉ mở được danh sách chung | Đã bổ sung `data` payload thật trong gói FCM (không chỉ trong `GET /notifications` như bản vá lần 2 mục 4.6) — luôn có `eventType`, kèm `checkId`/`siteId`/`expiresAt` cho `RANDOM_CHECK_SENT` | **Cập nhật khuyến nghị**: đọc `remoteMessage.data` (Android) / `userInfo` (iOS) ngay cả khi app đang tắt hoàn toàn để deep-link thẳng vào check, không cần chờ app mở mới đồng bộ `GET /notifications` nữa — gỡ bỏ giới hạn đã ghi ở mục 4.6 |
| 3 (P1, Web+App) | Tắt "hiện trong inbox app" (in-app) đang làm mất luôn push cho loại thông báo đó — lỗi logic backend, không phải thiết kế | Đã sửa: 2 cờ `inAppEnabled`/`pushEnabled` (màn cài đặt thông báo) giờ xét độc lập hoàn toàn | Không cần đổi UI cài đặt — 2 toggle đã tách sẵn từ trước, chỉ là backend trước đây không tôn trọng đúng; giờ hoạt động đúng như tên gọi của từng toggle |

Chi tiết kỹ thuật đầy đủ (kể cả quyết định retention ảnh sinh trắc học, không phải việc FE cần biết) xem `random-check-config-review.md` mục 13.

## 0.e Bản vá lần 5 (2026-08-04) — Vi phạm (Violation) tách sang tài liệu riêng + tín hiệu mềm cho kích hoạt thủ công

**Vi phạm (Violation) giờ có tài liệu API riêng**: `docs/api/violation-management-api.md` — bao gồm danh sách/chi tiết/confirm/dismiss (HR), tự xem + gửi giải trình (nhân viên), và inbox gộp mới `GET /me/exceptions`. Tài liệu này (`random-check-ui-guide.md`) chỉ còn phạm vi Cấu hình + vòng đời `ScheduledCheck` — không lặp lại nội dung violation nữa.

Thay đổi duy nhất trong phạm vi tài liệu này: `POST /scheduled-checks/manual` (mục 3.9) giờ trả thêm **`manualTriggerCountToday`** (số nguyên) — số lần kiểm tra thủ công đã gửi cho đúng nhân viên đó trong ngày, **bao gồm cả lần vừa gửi**. Đây **không phải giới hạn cứng** (chủ đích không rate-limit, xem mục 5 bên dưới) — chỉ để FE hiện tín hiệu mềm kiểu "Đã gửi 3 lần hôm nay" cạnh nút "Kiểm tra ngay", giúp HR tự cân nhắc trước khi gửi thêm, không chặn thao tác.

## 0. Trả lời câu hỏi: 5 tính năng bạn nêu đã có trên giao diện chưa?

**Chưa — đây là các API backend đã có sẵn (và vừa được kiểm tra/sửa lỗi), nhưng chưa có màn hình Web/App nào gọi tới chúng.** Cả 5 tính năng dưới đây đều thuộc **Web (Company Portal)**, dành cho Company Admin/HR — không có phần nào thuộc App (nhân viên chỉ là đối tượng BỊ kiểm tra, không cấu hình).

| # | Tính năng bạn nêu | Vai trò | API đã có | Đã đúng nghiệp vụ chưa (sau khi sửa) | Mục trong tài liệu này |
|---|---|---|---|---|---|
| 1 | Tạo cấu hình random check mặc định tenant | Company Admin | `POST/GET .../tenant-default` | ✅ Đúng | 3.1 |
| 2 | Tạo cấu hình override theo site | HR/Admin | `POST/GET .../sites/{siteId}` | ✅ Đúng | 3.2 |
| 3 | Cấu hình số lần và khung giờ check | HR/Admin | trong `PUT .../{configId}` | ✅ Đúng, **mới ràng buộc thêm theo giờ ca thực tế** | 3.4 |
| 4 | Cấu hình mode kiểm tra | HR/Admin | `PUT .../{configId}/check-mode` | ⚠️ Trước đây liveness không hoạt động thật — **đã sửa** | 3.5 |
| 5 | Cấu hình áp dụng theo vai trò | HR/Admin | `PUT .../{configId}/applicable-roles` | ✅ Đúng | 3.6 |

**Việc BẮT BUỘC Web phải làm**: dựng toàn bộ màn hình cấu hình (mục 3) — hiện chưa tồn tại trên giao diện. **Việc App cần làm**: dựng/rà soát màn hình nhân viên nhận và phản hồi kiểm tra ngẫu nhiên (mục 4) — API đã có, cần xác nhận App đã dùng đúng field mới (`employeePhotoBase64`, không phải `faceImageUrl`) chưa.

## 1. Khái niệm nền tảng — đọc trước khi dựng UI

### 1.1 Tenant-default vs Site-override — 2 tầng cấu hình, 1 bảng dữ liệu

- **Tenant-default**: 1 cấu hình duy nhất/tenant (`siteId = null`), áp dụng cho MỌI site không có override riêng. Company Admin tạo 1 lần khi mới dùng hệ thống.
- **Site-override**: cấu hình riêng cho 1 site cụ thể (`siteId` khác null), **ghi đè hoàn toàn** (không merge từng field) cấu hình mặc định cho site đó. Dùng cho site rủi ro cao (công trình xa, nhiều vi phạm trước đây...) cần kiểm tra dày hơn hoặc mode nghiêm ngặt hơn.
- **Thứ tự phân giải khi hệ thống thực sự áp dụng** (job đêm sinh lịch kiểm tra, hoặc kiểm tra thủ công): site-override trước → không có thì tenant-default → không có config nào thì **không kiểm tra** site/nhân viên đó (im lặng bỏ qua, không lỗi).
- **Mỗi tenant tối đa 1 tenant-default, mỗi site tối đa 1 override** — tạo lần 2 sẽ bị chặn `409 Conflict`, phải dùng API sửa (`PUT`) thay vì tạo lại.

### 1.2 API "effective config" — luôn dùng cái này để hiển thị "config đang áp dụng cho site X"

`GET .../sites/{siteId}/effective` — trả về đúng config sẽ thực sự áp dụng (không 404 chỉ vì site chưa có override riêng — tự động rơi về tenant-default). Field `resolvedFrom` cho biết nguồn: `"site_override"` hoặc `"tenant_default"`. **Không tự dựng logic "gọi site trước, 404 thì gọi tenant-default" ở FE** — dùng thẳng endpoint này.

### 1.3 Vòng đời 1 lượt kiểm tra (`ScheduledCheck.status`)

```
pending → sent → responded (nhân viên đã trả lời, outcome pass/fail)
                → no_response (hết hạn không trả lời)
        → cancelled (HR huỷ tay)
```

- `pending`: đã lên lịch, chưa tới giờ gửi thông báo.
- `sent`: đã gửi thông báo, nhân viên có `responseWindowSeconds` để phản hồi.
- `responded`: nhân viên đã gửi vị trí (+ ảnh nếu mode yêu cầu) — xem `outcome` (`pass`/`fail`) trong object `response` lồng bên trong.
- `no_response`: hết hạn, tự động tạo violation `no_response`.
- `cancelled`: HR huỷ tay (ví dụ nhân viên nghỉ phép hôm đó).

### 1.4 3 mode kiểm tra — mức độ kiểm soát tăng dần

| Mode | Yêu cầu nhân viên | Điều kiện PASS |
|---|---|---|
| `location_only` | Gửi GPS | Vị trí nằm trong geofence của site |
| `location_face` | GPS + ảnh selfie | Vị trí đúng **và** khuôn mặt khớp hồ sơ Face ID đã đăng ký |
| `location_face_liveness` | GPS + ảnh selfie | Vị trí đúng, khuôn mặt khớp, **và** AI xác nhận là người thật (không phải ảnh/video giả) — mức kiểm soát chặt nhất |

**Quan trọng — đã xác minh và sửa (31/07/2026)**: trước đây `location_face_liveness` chỉ kiểm tra khớp khuôn mặt, bỏ qua kết quả liveness — nay đã sửa, thực sự fail nếu liveness không đạt dù mặt khớp.

## 2. Ma trận quyền theo vai trò

| Hành động | TENANT_ADMIN / HR_MANAGER | SITE_SUPERVISOR | Nhân viên thường |
|---|---|---|---|
| Tạo/sửa/xoá cấu hình (tenant-default, site-override) | ✅ (`randomchecks:configure`) | ✅ nếu site trong phạm vi được giao | ❌ |
| Xem danh sách/chi tiết lượt kiểm tra | ✅ (`randomchecks:list` hoặc `:configure`) | ✅ (site trong phạm vi) | ❌ |
| Kích hoạt kiểm tra thủ công (targeted) | ✅ (`randomchecks:configure`) | ✅ nếu site trong phạm vi | ❌ |
| Huỷ / dispatch tay 1 lượt kiểm tra | ✅ (`randomchecks:configure`) | ✅ nếu site trong phạm vi | ❌ |
| Xem lượt kiểm tra của chính mình + phản hồi | — | — | ✅ (tự động theo JWT, không cần quyền đặc biệt) |

**Site-scope**: `SITE_SUPERVISOR` chỉ thao tác được trên site được giao — API tự chặn (`403`) nếu truyền `siteId` ngoài phạm vi, giống cơ chế đã áp dụng cho attendance/reports.

## 3. Màn hình phía Web (Company Admin / HR)

### 3.1 Tạo cấu hình mặc định tenant

**User story**: *Company Admin muốn tạo cấu hình random check mặc định cho công ty để kiểm tra nhân viên hiện trường theo policy.*

`POST /random-check-configs/tenant-default` — quyền `randomchecks:configure`. Chỉ tạo được **1 lần**; gọi lần 2 → `409` (dùng `PUT` để sửa).

Request body:
```json
{
  "checksPerShift": 2,
  "minIntervalMinutes": 60,
  "allowedStartTime": "08:00",
  "allowedEndTime": "17:00",
  "checkMode": "location_only",
  "applicableRoles": ["worker", "supervisor"],
  "responseWindowSeconds": 300,
  "failureEscalationThreshold": 3
}
```
- `applicableRoles: []` (mảng rỗng) = áp dụng cho **mọi vai trò**, không giới hạn.
- `failureEscalationThreshold` — optional, mặc định `3` nếu bỏ trống (xem mục 3.12).

`GET /random-check-configs/tenant-default` — lấy cấu hình hiện tại (404 nếu chưa tạo — FE nên hiện empty-state "Chưa cấu hình, bấm để tạo" thay vì lỗi).

**UI đề xuất**: 1 form đơn trong màn "Cài đặt công ty" → "Kiểm tra ngẫu nhiên", hiện trạng thái "Chưa cấu hình" hoặc form đã điền sẵn giá trị hiện tại (dùng chung form với sửa — gọi `PUT` nếu đã tồn tại).

### 3.2 Tạo cấu hình override theo site

**User story**: *HR/Admin muốn tạo cấu hình riêng cho công trình để kiểm soát site rủi ro cao.*

`POST /random-check-configs/sites/{siteId}` — cùng payload như 3.1, cùng quyền, nhưng gắn theo 1 site cụ thể. `409` nếu site đã có override (dùng `PUT` để sửa).

`GET /random-check-configs/sites/{siteId}` — lấy override CỦA RIÊNG site này. **404 nếu site không có override riêng** (khác với "effective config" ở mục 1.2 — dùng đúng endpoint theo đúng mục đích: endpoint này để hiển thị "site này có đang override hay không", endpoint effective để hiển thị "config nào đang thực sự áp dụng").

**UI đề xuất**: trong màn chi tiết Site (Site Detail), thêm tab/section "Cấu hình kiểm tra ngẫu nhiên" — mặc định hiện config **effective** (mục 1.2) kèm badge "Đang dùng mặc định công ty" (`resolvedFrom=tenant_default`) hoặc "Đã tuỳ chỉnh riêng cho site này" (`resolvedFrom=site_override`); nút "Tuỳ chỉnh riêng cho site này" mở form tạo override (chỉ hiện nếu chưa có override).

### 3.3 Xem cấu hình đang thực sự áp dụng (effective config)

`GET /random-check-configs/sites/{siteId}/effective` — dùng cho MỌI nơi cần trả lời câu hỏi "site này đang bị kiểm tra theo quy tắc nào" (màn Site Detail, màn xem trước khi kích hoạt kiểm tra thủ công...). Trả `resolvedFrom` để phân biệt nguồn. 404 chỉ khi tenant KHÔNG có default VÀ site KHÔNG có override (nghĩa là site này hiện không bị kiểm tra ngẫu nhiên gì cả).

### 3.4 Cấu hình số lần và khung giờ check

**User story**: *HR/Admin muốn thiết lập số lần check, khoảng cách và khung giờ cho phép để tránh kiểm tra quá dày hoặc sai giờ.*

Các field trong cùng 1 config (không phải API riêng — nằm trong `POST`/`PUT` ở mục 3.1/3.2):

| Field | Ý nghĩa | Ràng buộc |
|---|---|---|
| `checksPerShift` | Số lần kiểm tra mỗi ca/ngày | 1–10 |
| `minIntervalMinutes` | Khoảng cách tối thiểu giữa 2 lần kiểm tra | ≥ 0 |
| `allowedStartTime` / `allowedEndTime` | Khung giờ được phép gửi kiểm tra | `end` phải sau `start` |

**Validate phía server (FE nên validate trước để UX mượt, nhưng server luôn là nguồn sự thật cuối)**: khung giờ `[allowedStartTime, allowedEndTime]` phải đủ rộng để chứa `checksPerShift` lần kiểm tra cách nhau `minIntervalMinutes` — nếu không đủ, server trả `400` với thông báo cụ thể số phút còn thiếu. Ví dụ: 3 lần, cách nhau 60 phút, cần tối thiểu 120 phút khung giờ (`(3-1) × 60`).

**Quan trọng — mới bổ sung (31/07/2026), FE cần hiểu để giải thích đúng cho HR**: khung giờ cấu hình ở đây là **giới hạn TRÊN** — hệ thống tự động lấy **giao (∩)** giữa khung giờ này và giờ ca làm việc thực tế của từng nhân viên (`Shift.startTime`/`endTime`). Ví dụ: config đặt `07:00–22:00`, nhưng nhân viên làm ca chiều `15:00–23:00` → hệ thống chỉ kiểm tra trong khoảng `15:00–22:00` (phần giao), **không kiểm tra ngoài giờ làm thực tế của họ**. Nếu khung config hoàn toàn không giao với ca của nhân viên nào đó (ví dụ config `00:00–04:00` nhưng không ai làm ca đêm), nhân viên đó đơn giản không bị sinh lịch kiểm tra ngày đó — không phải lỗi. **UI nên hiện ghi chú nhỏ dưới 2 ô giờ**: *"Giờ thực tế áp dụng cho từng nhân viên sẽ là phần giao giữa khung này và giờ ca làm việc của họ."*

`responseWindowSeconds` cũng thuộc nhóm này về mặt UX (thời gian nhân viên có để phản hồi trước khi bị tính `no_response`) — tối thiểu 30 giây.

### 3.5 Cấu hình mode kiểm tra

**User story**: *HR/Admin muốn chọn mode location_only/location_face/location_face_liveness để linh hoạt theo mức độ kiểm soát.*

`PUT /random-check-configs/{configId}/check-mode` — cập nhật riêng field này (không cần gửi lại toàn bộ config).
```json
{ "checkMode": "location_face_liveness" }
```

**UI đề xuất**: dropdown/radio 3 lựa chọn với mô tả ngắn (dùng đúng nội dung ở bảng mục 1.4) — không dùng dạng text tự do, cả 3 giá trị hợp lệ đã cố định.

**Cảnh báo quan trọng cần hiện cho HR khi chọn `location_face`/`location_face_liveness`**: các mode này chỉ hoạt động với nhân viên **đã đăng ký Face ID** (`status='enrolled'`, xem tính năng Face ID enrollment). Nhân viên chưa đăng ký sẽ **tự động fail ngay lập tức** mọi lần kiểm tra thuộc mode này (không kịp gửi ảnh) — nên UI cấu hình mode nên hiện gợi ý: *"X/Y nhân viên tại site này chưa đăng ký Face ID — họ sẽ tự động fail nếu áp dụng mode này. Nhắc họ đăng ký trước."* (nếu FE có sẵn dữ liệu enrollment count; nếu chưa có, đây là gợi ý cải tiến, không bắt buộc ngay).

### 3.6 Cấu hình áp dụng theo vai trò

**User story**: *HR/Admin muốn chọn role_at_site được áp dụng random check để không làm phiền nhóm không cần kiểm tra.*

`PUT /random-check-configs/{configId}/applicable-roles`
```json
{ "applicableRoles": ["worker"] }
```
- **Xác nhận qua code (01/08/2026)**: giá trị hợp lệ **chỉ có đúng 2**: `"worker"` và `"supervisor"` — `Assignment.role` (không phải role RBAC hệ thống như `TENANT_ADMIN`) bị khoá cứng ở cả validation lẫn DB `CHECK` constraint, không phải free-text. Field `applicableRoles` trên config tuy lưu chuỗi tự do (không validate ở tầng này), nhưng bất kỳ giá trị nào khác `worker`/`supervisor` **sẽ không bao giờ khớp được nhân viên nào** — không lỗi, chỉ lặng lẽ vô tác dụng. **Web đúng khi giữ cứng UI chỉ 2 lựa chọn này** (dropdown/checkbox cố định, không phải free-text input) — tiếp tục giữ nguyên, không cần đổi.
- Mảng rỗng `[]` = áp dụng cho tất cả — UI nên có switch "Áp dụng cho tất cả vai trò" thay vì bắt HR tự xoá hết tag.

**Lưu ý tên field JSON**: field kích hoạt/tắt config trong response JSON tên là **`active`** (không phải `isActive`) — do quy ước serialize boolean getter của Java (`isActive()` → JSON key `active`). Request `PUT` vẫn dùng `isActive` (field trong `UpdateRandomCheckConfigRequest`). Hai chiều khác tên nhau — FE cần map đúng khi đọc response vs. khi gửi request, đã kiểm tra thực tế qua API call để xác nhận.

### 3.7 Sửa toàn bộ / xoá cấu hình

- `PUT /random-check-configs/{configId}` — sửa từng phần (chỉ gửi field muốn đổi, field bỏ qua giữ nguyên giá trị cũ). Dùng cho form "Sửa cấu hình" đầy đủ.
- `DELETE /random-check-configs/{configId}` — xoá mềm. Sau khi xoá tenant-default, site vẫn dùng theo site-override riêng nếu có; sau khi xoá site-override, site tự động rơi về tenant-default (nếu có) — **không mất khả năng bị kiểm tra hoàn toàn trừ khi xoá cả 2**.
- `GET /random-check-configs` — liệt kê toàn bộ config trong tenant (tenant-default + mọi site-override), tự lọc theo site-scope nếu caller bị giới hạn site. Dùng cho màn tổng quan "Danh sách cấu hình kiểm tra ngẫu nhiên".
- `GET /random-check-configs/{configId}` — chi tiết 1 config theo ID.

### 3.8 Danh sách / chi tiết lượt kiểm tra đã lên lịch (theo dõi vận hành)

| Endpoint | Dùng cho |
|---|---|
| `GET /scheduled-checks` | Bảng danh sách toàn bộ lượt kiểm tra (lọc theo site/nhân viên/status/khoảng ngày, phân trang) |
| `GET /scheduled-checks/summary` | Số liệu tổng quan (đếm theo status) cho dashboard |
| `GET /scheduled-checks/{checkId}` | Chi tiết 1 lượt — bao gồm object `response` lồng bên trong nếu đã có phản hồi (toạ độ, ảnh, outcome, lý do fail) |
| `GET /scheduled-checks/{checkId}/photo` | **Mới (01/08/2026)** — ảnh selfie bằng chứng (JPEG), chỉ khi `response.hasPhotoEvidence=true` |
| `GET /scheduled-checks/dispatch-queue` | Trạng thái hàng đợi Redis (số lượng check đang chờ dispatch) — chủ yếu cho màn giám sát vận hành/debug, không cần thiết cho HR thường |

**Mới (01/08/2026)** — theo yêu cầu từ báo cáo audit Web: `GET /scheduled-checks` giờ trả kèm `employeeName`, `siteName`, `outcome`, `failureReason` ngay trên mỗi dòng (hydrate hàng loạt ở backend cho cả trang, không phải gọi N+1) — **không cần FE tự resolve tên qua API riêng hay gọi detail để lấy outcome nữa**, dùng thẳng field có sẵn. `GET /{checkId}` (chi tiết) giờ cũng trả kèm `manualReason`/`triggeredBy` ngay ở cấp response gốc (trước đây chỉ có ở response tạo/list) — không cần tự "nhớ" dữ liệu từ dòng đã chọn khi mở modal; và `response.faceVerifyScore` giờ có giá trị thật (trước đây luôn `null` do lỗi map thiếu ở backend).

**Mới (01/08/2026) — xem ảnh selfie bằng chứng**: `response.hasPhotoEvidence` (boolean, trong `CheckResponseDto`) cho biết lượt kiểm tra này có ảnh lưu trên server hay không (mode `location_only`, hoặc mode face nhưng nhân viên chưa enrolled/không gửi ảnh → luôn `false`, không có ảnh để xem). Khi `true`, gọi `GET /scheduled-checks/{checkId}/photo` (kèm Bearer token, cùng quyền `randomchecks:list`/`:configure` như detail) để lấy bytes JPEG — **không phải URL công khai/presigned**, phải gọi authenticated giống mọi API khác rồi tự dựng blob URL để hiện `<img>` (không thể dùng thẳng `<img src="...">` với URL này vì cần header Authorization). UI đề xuất: nút "Xem ảnh bằng chứng" trong modal chi tiết, chỉ hiện khi `hasPhotoEvidence=true`, mở lightbox/modal ảnh riêng khi bấm (gọi ảnh lazy, không tải trước cho mọi dòng trong danh sách).

**UI đề xuất màn "Lịch sử kiểm tra ngẫu nhiên"**: bảng có cột Nhân viên, Site, Ngày, Giờ dự kiến, Trạng thái (badge màu theo status ở mục 1.3), Kết quả (pass/fail/—), Lý do fail (nếu có) — dùng thẳng field mới trên chính response list, không cần gọi thêm API nào khác cho bảng chính. Click vào 1 dòng → modal chi tiết dùng `GET /{checkId}` (hiện toạ độ GPS trên bản đồ mini nếu có, hiện ảnh selfie qua nút riêng + `faceVerifyScore` nếu mode yêu cầu face).

### 3.9 Kích hoạt kiểm tra thủ công (targeted check)

`POST /scheduled-checks/manual` — HR chỉ định đích danh 1 nhân viên tại 1 site, gửi kiểm tra NGAY (không chờ lịch tự động), bỏ qua bộ lọc `applicableRoles` của config (chủ đích — xem mục 5.4).

```json
{
  "siteId": "...",
  "employeeId": "...",
  "reason": "Nghi ngờ có gian lận chấm công, kiểm tra trực tiếp",
  "checkMode": "location_face"
}
```
- **`reason` bắt buộc** (mới, 31/07/2026) — `400` nếu thiếu. UI **bắt buộc** có ô nhập lý do trước khi cho bấm "Gửi kiểm tra ngay", không được để trống/mặc định.
- `checkMode` optional — nếu bỏ trống, dùng mode của config effective tại site đó; có thể override riêng cho lần kiểm tra thủ công này.
- Nhân viên phải có assignment active tại site đó hôm nay, và tenant phải có config (site-override hoặc tenant-default) — nếu không, `400`/`404` tương ứng.
- Response trả về `manualReason` và `triggeredBy` — **nên hiện lại 2 field này** trên UI danh sách/chi tiết (mục 3.8) để phân biệt kiểm tra thủ công với kiểm tra tự động, và biết ai đã yêu cầu.
- **Mới (bản vá lần 5, 2026-08-04)**: response còn trả `manualTriggerCountToday` — xem mục 0.e. Mỗi lần gọi endpoint này cũng tự động ghi 1 dòng audit log (`action=manual_random_check_triggered`) — không cần FE tự log riêng gì thêm, chỉ cần biết là đã có audit trail nếu HR hỏi "ai đã gửi kiểm tra cho tôi hôm nay".

**UI đề xuất**: nút "Kiểm tra ngay" trên trang chi tiết nhân viên hoặc trang danh sách nhân viên tại site → modal bắt buộc nhập lý do → xác nhận → hiện kết quả tạo thành công + đếm ngược thời gian phản hồi còn lại + dòng nhỏ "Đã gửi {manualTriggerCountToday} lần hôm nay cho nhân viên này" (không phải cảnh báo màu đỏ, chỉ là thông tin).

### 3.10 Huỷ / dispatch tay / xử lý hết hạn (vận hành nâng cao)

- `POST /scheduled-checks/{checkId}/cancel` — huỷ 1 lượt đang `pending`/`sent` (ví dụ nhân viên xin nghỉ đột xuất hôm đó).
- `POST /scheduled-checks/{checkId}/dispatch` — gửi thông báo tay ngay lập tức cho 1 check đang `pending` (bỏ qua hàng đợi Redis) — chủ yếu công cụ vận hành/debug.
- `POST /scheduled-checks/process-expired` — quét thủ công các check `sent` đã hết hạn, chuyển `no_response` + tạo violation (bình thường job tự động chạy, endpoint này để chạy tay khi cần).

Các endpoint này **không bắt buộc** phải có UI riêng ngay — có thể để dạng thao tác admin nâng cao/ẩn sau quyền, hoặc bỏ qua giai đoạn đầu nếu không phải nhu cầu cấp thiết.

### 3.11 Liên kết với Bảng công / Báo cáo lương — chỉ cảnh báo, không tự trừ lương

**Mới bổ sung 31/07/2026** — theo quyết định nghiệp vụ: random check là công cụ audit, không tự động ảnh hưởng lương. Nhưng FE bảng công/báo cáo (đã có sẵn từ đợt trước, xem `docs/api/attendance-ui-permissions-guide.md`) giờ có thêm field liên quan tới random check:

| Field | Ở đâu | Ý nghĩa |
|---|---|---|
| `hasRandomCheckFailure` | `AttendanceSummaryResponse` (từng ngày) | Ngày đó có ≥1 lượt kiểm tra ngẫu nhiên fail/không phản hồi |
| `daysWithRandomCheckFailure` | `AttendanceHrMonthlyResponse`, `AttendanceMonthlyResponse` (bảng công tháng) | Số ngày trong tháng có vấn đề |
| `exceedsRandomCheckFailureThreshold` | `AttendanceHrMonthlyResponse` | `true` nếu `daysWithRandomCheckFailure` ≥ `failureEscalationThreshold` đã cấu hình (mục 3.1) |
| `totalRowsWithRandomCheckFailure` | `MonthlyAttendanceReportResponse` (báo cáo tháng toàn tenant) | Tổng số dòng nhân viên+site có vấn đề trong tháng |

**Việc Web cần làm** (nếu đã dựng màn bảng công theo `attendance-ui-permissions-guide.md` mục 4.3/4.4): thêm badge/cột tương tự badge `hasPendingReviewSession` đã có — ví dụ icon 🎯 màu cam kèm tooltip *"Có kiểm tra ngẫu nhiên thất bại/không phản hồi trong ngày — không tự trừ giờ công, HR nên xem lại"*. Guard chặn xuất Excel (`409 ATTENDANCE_NOT_READY`) đã tự động mở rộng bao gồm điều kiện này — không cần FE làm gì thêm ngoài xử lý đúng message lỗi mới (đã cập nhật nội dung tiếng Việt, xem mục 6).

## 4. Màn hình phía App (nhân viên)

### 4.1 Xem các lượt kiểm tra đang chờ phản hồi

`GET /scheduled-checks/my-pending` — không cần quyền đặc biệt, tự lọc theo nhân viên đang đăng nhập (JWT). Trả về danh sách check `pending`/`sent`, kèm `secondsRemaining` (đếm ngược, âm nếu đã hết hạn — App nên tự ẩn/không cho phản hồi nếu âm, dù server cũng chặn).

**[MỚI, 01/08/2026, bản vá lần 4]** Check `pending` (chưa thực sự được gửi) chỉ xuất hiện trong response khi còn ≤ 60 giây nữa mới tới `scheduledAt` — trước đó server cố tình **không trả về**, kể cả khi lịch đã được sinh sẵn từ đầu ngày. Đây là chặn rò rỉ bảo mật (mục 0.d #1), không phải bug: App **không nên** cache/hiển thị "lịch kiểm tra hôm nay" dựa trên response này — mỗi lần gọi chỉ thấy đúng những gì sắp/đang xảy ra ngay lúc đó. Check `sent` (đã thực sự gửi) không bị giới hạn này, vẫn trả về như cũ.

**UI đề xuất**: banner/notification nổi bật khi có check `sent` đang chờ (giống "cuộc gọi đến"), đếm ngược trực quan (progress bar hoặc số giây) dựa trên `secondsRemaining`.

### 4.2 Phản hồi 1 lượt kiểm tra

`POST /scheduled-checks/{checkId}/respond`

```json
{
  "latitude": 21.0285,
  "longitude": 105.8542,
  "accuracyMeters": 10,
  "employeePhotoBase64": "<base64 JPEG, chỉ cần khi mode yêu cầu face>"
}
```

**QUAN TRỌNG — field đúng cần dùng**: gửi ảnh selfie qua **`employeePhotoBase64`** (base64 string). **`faceImageUrl`** là field khác (chỉ lưu URL ảnh đã upload sẵn ở nơi khác, KHÔNG được dùng để xác thực khuôn mặt) — nếu App hiện đang gửi `faceImageUrl`, đó là field sai, cần đổi sang `employeePhotoBase64` để tính năng face-mode hoạt động đúng. `livenessScore` KHÔNG được server đọc để quyết định pass/fail — liveness luôn được xác định bởi AI xử lý bất đồng bộ phía server, App không cần (và không nên) tự tính/gửi điểm này.

**Luồng xử lý** (App cần hiểu để hiện đúng trạng thái):
1. Vị trí được kiểm tra **ngay lập tức** (đồng bộ) — biết kết quả `locationVerified` ngay trong response.
2. Nếu mode yêu cầu face và có gửi ảnh: `faceVerified`/`livenessVerified` trả về **`null`** trong response ngay lúc đó (đang xử lý bất đồng bộ qua AI, thường vài giây) — App nên hiện trạng thái "Đang xác thực..." rồi **poll lại** `GET /scheduled-checks/{checkId}` sau vài giây để lấy kết quả cuối (`response.outcome`, `response.faceVerified`, `response.livenessVerified`).
3. Nếu mode yêu cầu face nhưng **không gửi ảnh**, hoặc **chưa đăng ký Face ID** → fail ngay lập tức, không cần đợi (`outcome="fail"` có ngay trong response đầu tiên).

**Giới hạn thời gian**: nếu phản hồi sau `expiresAt`, server trả lỗi riêng (xem mục 6) — App nên tự vô hiệu hoá nút "Gửi" khi đếm ngược về 0, tránh gửi request chắc chắn bị từ chối.

### 4.3 Yêu cầu đăng ký Face ID trước khi dùng mode face

**Mới xác nhận/sửa (31/07/2026)**: nếu tenant/site cấu hình mode `location_face`/`location_face_liveness`, nhân viên **bắt buộc phải đã đăng ký Face ID thành công** (`status='enrolled'`) — nếu chưa, mọi lượt kiểm tra thuộc mode này sẽ **luôn fail ngay lập tức** dù gửi ảnh gì đi nữa. App nên:
- Kiểm tra trạng thái Face ID của nhân viên (API đã có ở tính năng Face ID enrollment, không thuộc phạm vi tài liệu này) khi hiện banner "có lượt kiểm tra đang chờ" — nếu site áp dụng mode face và nhân viên chưa enrolled, hiện cảnh báo rõ ràng thay vì để họ gửi ảnh vô ích: *"Site này yêu cầu xác thực khuôn mặt — vui lòng đăng ký Face ID trước trong mục Hồ sơ."*

### 4.4 Hiển thị kết quả sau khi phản hồi

Dùng `outcome` (`pass`/`fail`) + `failureReason` (chuỗi các lý do cách nhau dấu phẩy, ví dụ `"location_mismatch,face_fail"`) từ response để hiện thông báo phù hợp — tương tự cách màn "Kết quả chấm công" (check-in/out) đã làm, giữ nhất quán trải nghiệm giữa 2 luồng.

### 4.5 [MỚI — bắt buộc đổi, 01/08/2026] Poll kết quả cuối bằng endpoint dành riêng cho nhân viên

**Đây là điểm quan trọng nhất trong bản vá lần 2 cho App**: tài liệu trước hướng dẫn poll `GET /scheduled-checks/{checkId}` để lấy kết quả AI xử lý bất đồng bộ — nhưng endpoint đó **chỉ dành cho HR** (yêu cầu quyền `randomchecks:list`/`:configure`), App gọi sẽ nhận `403`. Đây là lỗi thiết kế API thực sự (đã xác nhận qua code), không phải App hiểu sai.

**Đã sửa — dùng endpoint mới**: `GET /scheduled-checks/{checkId}/my-result`
- Không cần quyền đặc biệt — chỉ cần đăng nhập, tự xác định nhân viên qua JWT.
- Chỉ trả kết quả nếu check đó thuộc đúng nhân viên gọi — check của người khác trả `404` (không phải `403`).

```json
{
  "checkId": "uuid",
  "status": "responded",
  "processingStatus": "pending",
  "outcome": "pass",
  "failureReason": null,
  "locationVerified": true,
  "faceVerified": null,
  "livenessVerified": null,
  "faceVerifyScore": null,
  "respondedAt": "2026-08-01T01:48:21Z"
}
```

**Luồng poll đề xuất** (khớp với checklist App đã tự đề ra): sau khi `respond()` trả về (mode yêu cầu face, `faceVerified=null`) → gọi `my-result` mỗi 3–5 giây, dừng khi `processingStatus="completed"` hoặc quá 60 giây (timeout, hiện thông báo chung "Không thể xác minh lúc này, liên hệ quản lý"). `processingStatus="pending"` nghĩa là AI vẫn đang xử lý — **không coi `faceVerified=null` là fail**, giữ nguyên trạng thái "Đang xác minh...".

**Giới hạn đã biết so với đề xuất ban đầu của App**: response KHÔNG có field `processedAt` riêng (thời điểm AI xử lý xong, tách biệt `respondedAt`) và KHÔNG phân biệt `processingStatus="failed"` (lỗi hạ tầng AI) với `"pending"` (đang chờ bình thường) — cả 2 đều cần thêm cột DB mới, chưa làm trong đợt này. Với App, ảnh hưởng thực tế: nếu AI thực sự lỗi (hiếm), `processingStatus` sẽ giữ `"pending"` mãi thay vì báo `"failed"` sớm hơn — App vẫn xử lý đúng nhờ cơ chế timeout 60 giây đã có sẵn, chỉ là chờ đủ 60 giây thay vì phát hiện sớm hơn.

### 4.6 [MỚI, 01/08/2026] Deep-link từ thông báo bằng `metadata`

Thông báo `RANDOM_CHECK_SENT` (lấy qua `GET /notifications`) giờ có thêm field `metadata`:
```json
{
  "eventType": "RANDOM_CHECK_SENT",
  "title": "Kiểm tra ngẫu nhiên",
  "body": "...",
  "metadata": { "checkId": "uuid", "siteId": "uuid", "expiresAt": "2026-08-01T01:52:00Z" }
}
```
App nên đọc `metadata.checkId` để mở thẳng đúng màn phản hồi của lượt kiểm tra đó, thay vì mở danh sách chung rồi để nhân viên tự tìm.

**[ĐÃ GỠ 01/08/2026, bản vá lần 4]** Giới hạn "chưa có trong payload thô của gói push FCM" đã được giải quyết — xem mục 0.d #2. Gói push FCM giờ tự mang `data: { eventType, checkId, siteId, expiresAt }` (không chỉ khi App đã mở và gọi `GET /notifications`), nên App có thể đọc thẳng từ `remoteMessage.data`/`userInfo` để deep-link **ngay cả khi app đang tắt hoàn toàn**, không cần fallback "mở danh sách chung" nữa cho trường hợp này.

## 5. Business rules quan trọng — không hiện trên UI nhưng FE cần biết để giải thích hành vi hệ thống

1. **Nhân viên đã nghỉ việc (`terminated`) tự động không còn bị lên lịch kiểm tra** — không cần thao tác gì thêm khi đổi trạng thái nhân viên (đã sửa 31/07/2026, trước đây có bug khiến họ vẫn bị kiểm tra vô thời hạn).
2. **Xoá 1 site sẽ tự động xoá luôn site-override config của site đó** — không để lại cấu hình mồ côi.
3. **Giới hạn gói dịch vụ (plan)**: số lượt kiểm tra ngẫu nhiên/tháng bị giới hạn theo gói tenant đang dùng (`checksPerShift` request tự động bị cắt bớt nếu vượt hạn mức còn lại) — nếu HR thấy số lượt sinh ra ít hơn cấu hình, kiểm tra hạn mức gói trước khi báo lỗi.
4. **`ManualCheckService` (kiểm tra thủ công) không áp dụng bộ lọc `applicableRoles`** — chủ đích, vì HR chỉ định đích danh 1 người thì role-filter không còn ý nghĩa. Nếu nhân viên đó KHÔNG có assignment active tại site → vẫn bị chặn `400`.
5. **Ca làm việc qua đêm (`allowOvernight=true`)** — hiện KHÔNG được tính giao khung giờ như mục 3.4 mô tả (do phần giao 2 khung giờ vắt qua nửa đêm phức tạp hơn, chưa xử lý) — với site có ca đêm, khung giờ config được dùng nguyên vẹn, không bị bó hẹp theo giờ ca. Đây là điểm biết trước, chưa phải bug.

## 6. Mã lỗi cần xử lý

| HTTP | Khi nào | Web/App nên làm gì |
|---|---|---|
| 400 | Thiếu field bắt buộc, `reason` trống khi kiểm tra thủ công, khung giờ không đủ chứa số lần kiểm tra × khoảng cách, nhân viên không có assignment tại site | Validate phía client trước khi submit theo đúng bảng ràng buộc ở mục 3.4/3.9 |
| 401 | Chưa đăng nhập / token hết hạn | Redirect đăng nhập lại |
| 403 | Thiếu quyền `randomchecks:configure`/`:list`, hoặc site ngoài phạm vi site-scope | Ẩn hẳn menu/nút nếu biết trước không có quyền (dựa vào `GET /roles/me`) |
| 404 | Config không tồn tại (đã xoá hoặc chưa tạo — phân biệt bằng effective config trước, mục 1.2), site/check không tồn tại | Với config: hiện empty-state "Chưa cấu hình", không hiện lỗi đỏ |
| 409 | Tạo tenant-default/site-override lần 2 khi đã tồn tại | Chuyển hướng UI sang form "Sửa" (`PUT`) thay vì báo lỗi cho người dùng |
| 410 (`errorCode: CHECK_EXPIRED`) | Phản hồi (`respond`) sau khi hết hạn (`expiresAt` đã qua) | App tự vô hiệu hoá nút gửi khi đếm ngược về 0 (mục 4.2), không chỉ dựa vào lỗi server |
| 400 (`errorCode: VALIDATION_ERROR`) | Trả lời 1 check đã `responded`/`no_response`/`cancelled`/`pending` (chưa `sent`) | App chỉ hiện nút phản hồi khi `status='sent'` |
| 409 (`ATTENDANCE_NOT_READY`, ở API bảng công, không phải random-check) | Xuất Excel bảng công còn ngày có `hasRandomCheckFailure` chưa xử lý (mục 3.11) | Xem `attendance-ui-permissions-guide.md` mục 5 — đã có sẵn cơ chế `confirmDespiteWarnings=true` |

## 7. Checklist bàn giao frontend

- [ ] **Web — bắt buộc, chưa có màn hình**: dựng màn "Cài đặt công ty → Kiểm tra ngẫu nhiên" cho tenant-default (mục 3.1).
- [ ] **Web — bắt buộc, chưa có màn hình**: dựng tab "Kiểm tra ngẫu nhiên" trong chi tiết Site cho site-override, dùng effective-config để hiện trạng thái hiện tại (mục 3.2, 3.3).
- [ ] **Web**: form cấu hình dùng chung 1 bộ field (checksPerShift, minIntervalMinutes, allowedStartTime/EndTime, checkMode, applicableRoles, responseWindowSeconds, failureEscalationThreshold) cho cả create và update.
- [ ] **Web**: hiện ghi chú "khung giờ sẽ giao với giờ ca thực tế" dưới ô chọn giờ (mục 3.4).
- [ ] **Web**: cảnh báo nhân viên chưa đăng ký Face ID khi chọn mode face (mục 3.5), nếu có dữ liệu enrollment sẵn.
- [ ] **Web — bắt buộc**: dựng màn "Kích hoạt kiểm tra thủ công" với ô `reason` bắt buộc (mục 3.9) — không được bỏ qua, backend từ chối nếu thiếu.
- [ ] **Web**: dựng màn danh sách/chi tiết lượt kiểm tra (mục 3.8), hiện `manualReason`/`triggeredBy` khi có.
- [ ] **Web**: thêm badge/cột `hasRandomCheckFailure` vào màn bảng công đã có (mục 3.11), đồng bộ với checklist ở `attendance-ui-permissions-guide.md`.
- [ ] **App — kiểm tra lại**: đang gửi ảnh selfie qua field nào? Phải là `employeePhotoBase64`, KHÔNG phải `faceImageUrl` (mục 4.2) — nếu đang sai field, sửa ngay vì tính năng face hiện không hoạt động với field sai.
- [ ] **App**: xử lý trạng thái "đang xác thực" (faceVerified/livenessVerified = null) bằng polling, không coi null là fail (mục 4.2).
- [ ] **App**: tự vô hiệu hoá nút phản hồi khi hết `secondsRemaining` (mục 4.1, 4.2).
- [ ] **App**: cảnh báo nhân viên chưa đăng ký Face ID trước khi họ cố phản hồi 1 check mode face (mục 4.3).
- [ ] **Web (bản vá lần 2)**: có thể bỏ code tự resolve tên nhân viên/site riêng và gọi detail để lấy outcome cho màn danh sách — dùng thẳng field mới trên `GET /scheduled-checks` (mục 3.8).
- [ ] **App — bắt buộc (bản vá lần 2)**: đổi polling kết quả AI từ `GET /{checkId}` (403 với nhân viên) sang `GET /{checkId}/my-result` (mục 4.5).
- [ ] **App (bản vá lần 2)**: đọc `metadata.checkId` từ notification để deep-link, thay vì mở danh sách chung (mục 4.6) — lưu ý giới hạn chưa áp dụng cho push payload thô.
- [ ] **Web — mới (bản vá lần 3, 01/08/2026)**: thêm nút "Xem ảnh bằng chứng" trong modal chi tiết, chỉ hiện khi `response.hasPhotoEvidence=true`, gọi `GET /{checkId}/photo` (authenticated, tự dựng blob URL) — mục 3.8.
- [ ] **Web — xác nhận (bản vá lần 3)**: giữ nguyên UI chỉ cho chọn `worker`/`supervisor` ở mục cấu hình vai trò áp dụng — đã xác nhận đúng qua code, không cần đổi gì (mục 3.6).
- [ ] **Web — mới (bản vá lần 5, 2026-08-04)**: hiện `manualTriggerCountToday` cạnh nút "Kiểm tra ngay" sau khi gửi thành công (mục 3.9, 0.e) — tín hiệu mềm, không chặn.
- [ ] **Web + App — chuyển sang tài liệu riêng (bản vá lần 5)**: mọi màn hình liên quan tới Vi phạm (danh sách, chi tiết, confirm/dismiss, giải trình, inbox gộp `/me/exceptions`) nay theo dõi ở `docs/api/violation-management-api.md` — xem checklist riêng trong tài liệu đó, không lặp lại ở đây.
