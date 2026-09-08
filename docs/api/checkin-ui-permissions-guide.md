# Tài liệu bàn giao UI: Web vs App và ẩn/hiện theo vai trò — Check-in/Check-out

## 0. [MỚI] Phản hồi round 2 từ Web + App — field mới cần dùng

Sau khi Web/App tích hợp round 1 và báo cáo lại, backend đã sửa 5 gap (chi tiết ở `checkin-management-api.md` §9). Field mới cần FE khai thác ngay:

- **`CheckinResponse` (list + response của mọi API check-in/checkout)** giờ có thêm: `employeeName`/`employeeCode`/`siteName` (Web không cần tự tra cứu/khớp UUID nữa), `checkOutLat`/`checkOutLon`/`checkOutAccuracy`/`checkOutInsideGeofence` (trước chỉ có ở detail), `effectiveCheckinPolicy` (snapshot tại check-in — dùng để biết `faceVerified=null` là "không áp dụng" hay "đang xác thực"), `source` (`online`|`offline`).
- **`CheckinDetailResponse`** giờ có thêm đủ 6 field Face ID/liveness (trước bị thiếu — bug thật, Web phải fallback dùng bản ghi list), cộng `effectiveCheckinPolicy`, `source`, `clientNonce`, `note`, `overriddenBy`, `overriddenAt` — đủ dữ liệu để hiện rõ nguồn gốc bản ghi (online/offline) và ai đã override khi nào, không cần suy đoán nữa.
- **Checkout giờ enforce theo policy đã snapshot tại check-in**, không phải cấu hình site/shift hiện tại — nếu HR vừa đổi policy site giữa lúc có nhân viên đang trong ca, những nhân viên ĐÃ check-in trước đó vẫn checkout theo policy lúc họ check-in (không bị kẹt vì yêu cầu mới họ chưa biết).

> Cập nhật theo code đang chạy ngày 29/07/2026. Đây **không phải** tài liệu API — chi tiết request/response đã có ở `docs/api/checkin-management-api.md` (business logic, race-condition fix, offline-sync hardening) và `docs/api/face-id-management-api.md` (active liveness). Tài liệu này chỉ trả lời:
> 1. Tính năng này thuộc **Web** hay **App**?
> 2. Với từng vai trò đăng nhập, phần tử nào **ẩn hẳn**, phần tử nào **hiện nhưng disable**, phần tử nào **hiện đầy đủ**?
> 3. App cần đổi/thêm gì trong luồng chấm công hiện tại để dùng đúng tính năng mới (chính sách 3 tầng, checkout Face ID, offline sync đã hardening).

## 1. Kết luận nhanh: App hay Web?

| Tính năng | Web (Company Portal) | App (nhân viên) |
|---|---|---|
| Chấm công vào ca (check-in) | — | ✅ Luồng chính |
| Chấm công ra ca (check-out) | — | ✅ Luồng chính |
| Xem danh sách site có thể chấm công hôm nay | — | ✅ (`GET .../checkin/available-sites`) |
| Xem lịch sử chấm công của bản thân | — | ✅ (`GET .../checkin/history`) |
| Xem 1 kết quả chấm công cụ thể | — | ✅ (`GET .../checkin/{id}`) |
| Giải trình 1 lượt chấm công (note/ảnh) | — | ✅ (`POST .../checkin/{id}/explain`) |
| Chấm công offline + đồng bộ khi có mạng lại | — | ✅ (`POST .../checkin/sync`) |
| HR xem danh sách chấm công toàn site được giao | ✅ | — |
| HR xem chi tiết 1 lượt chấm công (kèm employee/site/shift) | ✅ | — |
| HR override trạng thái 1 lượt chấm công (duyệt lại) | ✅ | — |
| Cấu hình chính sách xác thực theo Site (`checkinPolicy`) | ✅ (form Site) | — |
| Cấu hình override chính sách theo Shift (`checkinPolicyOverride`) | ✅ (form Shift) | — |

**Lưu ý quan trọng**: mọi endpoint chấm công (check-in/check-out/available-sites/history/sync) đều tự resolve nhân viên từ token đăng nhập — không có cách nào 1 tài khoản chấm công hộ tài khoản khác qua các endpoint này (khác với module Face ID, nơi HR có thể hỗ trợ enroll tại kiosk).

## 2. [MỚI] Chính sách xác thực 3 tầng — App phải đổi cách quyết định mở camera

### 2.1 Đọc đúng field, không tự suy luận

`GET .../checkin/available-sites` mỗi item giờ trả **`effectiveCheckinPolicy`** (1 trong 3 giá trị `gps_only`/`gps_face`/`gps_face_liveness`) — đã resolve sẵn override theo shift, App **không được tự cộng/suy luận** từ site + shift riêng lẻ, chỉ đọc đúng field này:

```json
{
  "assignmentId": "...",
  "site": { "id": "...", "name": "Kho vật tư A", ... },
  "shift": { "id": "...", "name": "Ca đêm", ... },
  "effectiveCheckinPolicy": "gps_face_liveness"
}
```

### 2.2 UI theo từng tầng — bảng quyết định cho màn chấm công

| `effectiveCheckinPolicy` | Trước khi bấm "Chấm công" | Field gửi kèm `submitCheckin`/`submitCheckout` |
|---|---|---|
| `gps_only` | Không cần mở camera | Không gửi field ảnh nào |
| `gps_face` | Mở camera chụp 1 ảnh tĩnh (đơn giản, nhanh) — **hoặc** cho phép chạy luồng active-liveness đầy đủ nếu muốn (mạnh hơn, được chấp nhận) | `employeePhotoBase64` **HOẶC** `livenessChallengeId` |
| `gps_face_liveness` | **Bắt buộc** chạy luồng active-liveness đầy đủ (xem `face-id-ui-permissions-guide.md` §0 cho chi tiết UI quay theo lệnh) — ảnh tĩnh đơn thuần sẽ bị từ chối | **Chỉ** `livenessChallengeId` (bắt buộc `siteId` đúng site đang chấm công khi gọi `POST .../liveness-challenge?purpose=checkin`) |

**Quan trọng**: mở camera NGAY khi hiện danh sách site dựa trên `effectiveCheckinPolicy`, đừng đợi bấm "Chấm công" rồi mới bắt lỗi `422 FACE_ID_REQUIRED` mới mở camera — trải nghiệm mượt hơn nhiều, đặc biệt với `gps_face_liveness` (luồng quay đầu/nháy mắt mất vài giây, không nên để người dùng bấm xong mới biết cần làm thêm bước).

### 2.3 Chưa enroll Face ID mà site yêu cầu

Nếu nhân viên chưa có Face ID `enrolled` (hoặc đang chờ duyệt) tại 1 site có `effectiveCheckinPolicy != gps_only`, server trả `422 FACE_ID_NOT_ENROLLED`. App nên **chủ động kiểm tra trước** (gọi `GET .../face-id` xem `status`) khi thấy site có policy khác `gps_only` trong danh sách available-sites, hiện banner "Site này yêu cầu Face ID — vui lòng đăng ký trước" kèm CTA đi tới màn đăng ký, thay vì để nhân viên đứng tại site rồi mới biết không chấm công được.

## 3. [MỚI] Check-out giờ cũng yêu cầu Face ID — đối xứng với check-in

**Thay đổi hành vi lớn nhất App cần cập nhật**: trước đây check-out chỉ cần GPS. Giờ check-out áp **đúng `effectiveCheckinPolicy` như check-in** (không yếu hơn) — chống "buddy checkout" (đồng nghiệp bấm ra ca hộ). Màn check-out cần đúng logic mở camera như bảng ở mục 2.2, không phải chỉ màn check-in.

`SubmitCheckoutRequest` giờ có thêm 3 field đối xứng với check-in: `employeePhotoBase64`, `requiresLiveness`, `livenessChallengeId` — App cần thêm field tương ứng vào request body khi gọi `POST .../checkin/{checkinId}/checkout`.

Response check-out (`CheckinResponse`) có thêm 3 field mới để hiển thị kết quả xác thực (điền dần sau khi worker AI xử lý xong, ban đầu `null`):
```json
{ "checkoutFaceVerified": null, "checkoutLivenessVerified": null, "checkoutFaceVerifyScore": null }
```
Tương tự 3 field check-in đã có sẵn (`faceVerified`/`livenessVerified`/`faceVerifyScore`) — cùng cách hiển thị: nếu `null`, hiện "Đang xác thực..."; `true` → dấu tick xanh; `false` → dấu cảnh báo (nhưng KHÔNG chặn nhân viên tiếp tục — xác thực chạy bất đồng bộ sau khi check-out/check-in đã được ghi nhận).

## 4. [MỚI] Check-in offline đã được hardening — không đổi API, nhưng App cần biết các `status` mới có thể gặp

`POST .../checkin/sync` (request/response không đổi format) giờ trả về nhiều trường hợp `status="rejected"`/`"conflict"` hơn trước — App **không cần đổi code gọi API**, nhưng nên cập nhật copy hiển thị lỗi để không còn message chung chung:

| `SyncResultItem.status` | `reason` mẫu | Gợi ý hiển thị |
|---|---|---|
| `rejected` | "Employee status is 'terminated', not active" | "Tài khoản không còn hoạt động — liên hệ HR" |
| `rejected` | "Assignment does not cover SATURDAY (checkin date ...)" | "Ca làm không phủ ngày này — bản ghi bị từ chối" |
| `rejected` | "Check-in at ... is earlier than the shift's allowed window" | "Giờ chấm công offline sớm hơn cho phép — bản ghi bị từ chối" |
| `rejected` | "This site/shift requires Face ID verification — a photo must be included even for an offline check-in" | "Site này yêu cầu ảnh khuôn mặt — kể cả khi offline" (App nên vẫn chụp ảnh lúc offline nếu biết trước site yêu cầu, để không mất bản ghi khi đồng bộ) |
| `conflict` | "Employee already has another open check-in session" / "A check-in record already exists..." | "Đã có bản ghi trùng — không tạo thêm, không phải lỗi của bạn" (không phải lỗi HTTP, chỉ khác `status`) |
| `accepted`, `reason` chứa "duplicate nonce" | — | Không hiện gì thêm — đây là kết quả của việc đồng bộ lại đúng bản ghi đã gửi trước đó (idempotent), coi như thành công bình thường |

**Lưu ý quan trọng cho App**: nếu App biết trước (lúc còn có mạng, từ `available-sites`) rằng site có `effectiveCheckinPolicy=gps_face_liveness`, thì **khi mất mạng vẫn nên chụp ảnh tĩnh làm bằng chứng dự phòng** (gửi qua `facePhotoBase64` trong `OfflineCheckinRequest` lúc đồng bộ) dù biết chắc bản ghi sẽ bị đưa vào `pending_review` khi đồng bộ — vì active-liveness không thể chứng minh lại cho quá khứ, nhưng có ảnh vẫn tốt hơn không có gì cho HR review thủ công.

## 5. Ma trận tổng hợp: Tính năng × Vai trò (Company Portal)

| Tính năng | TENANT_ADMIN / HR_MANAGER | SITE_SUPERVISOR | Nhân viên thường |
|---|---|---|---|
| Xem danh sách chấm công toàn tenant | ✅ (`checkins:list`) | ✅ (chỉ site được giao — tự động lọc theo site-scope) | ❌ |
| Xem chi tiết 1 lượt chấm công | ✅ (`checkins:list`) | ✅ (chỉ site được giao) | ❌ (chỉ xem của chính mình qua `checkins:read`) |
| Override trạng thái 1 lượt chấm công | ✅ (`checkins:review`) | ✅ (cần `checkins:review`, chỉ site được giao) | ❌ |
| Cấu hình `checkinPolicy` của Site | ✅ | ❌ (không có quyền sửa site) | ❌ |
| Cấu hình `checkinPolicyOverride` của Shift | ✅ | ❌ | ❌ |
| Chấm công (check-in/check-out) của chính mình | ✅ (nếu có hồ sơ nhân viên) | ✅ | ✅ |

**Lưu ý về multi-site supervisor**: nếu 1 SITE_SUPERVISOR được giao NHIỀU site, `GET .../checkin` (danh sách HR) yêu cầu truyền `siteId` cụ thể trong query — không trả gộp nhiều site cùng lúc (giới hạn hiện tại của `CheckinSpecification`). Web nên hiện dropdown chọn site trước khi load danh sách, không giả định luôn xem được toàn bộ.

## 6. Chi tiết ẩn/hiện và các trạng thái nút cần lưu ý

### 6.1 Form tạo/sửa Site (Web) — thêm dropdown "Chính sách chấm công"

- Field `checkinPolicy` — dropdown 3 giá trị (`gps_only`/`gps_face`/`gps_face_liveness`), mặc định `gps_only` khi tạo mới.
- Tooltip gợi ý theo từng mức, ví dụ: "GPS cơ bản: chỉ cần vị trí trong khu vực" / "Face ID: cần ảnh khuôn mặt hoặc quay động" / "Face ID chủ động: bắt buộc quay đầu/nháy mắt theo lệnh — mức bảo mật cao nhất, phù hợp kho vật tư/khu vực hạn chế".
- Khi chọn `gps_face`/`gps_face_liveness`, nên hiện cảnh báo nhẹ: "Đảm bảo nhân viên tại site này đã đăng ký và được duyệt Face ID trước khi áp dụng, tránh trường hợp không ai chấm công được".

### 6.2 Form tạo/sửa Shift (Web) — thêm override tùy chọn

- Field `checkinPolicyOverride` — dropdown 4 lựa chọn: "Theo site (mặc định)" (= không set, gửi `null`/không gửi field) + 3 giá trị cụ thể để ghi đè.
- Khi sửa (`PUT`), có checkbox riêng "Quay về theo site" → gửi `clearCheckinPolicyOverride: true` để reset về `null` (không phải chọn lại đúng giá trị hiện tại của site, vì 2 cái có thể khác nhau nếu site đổi policy sau).
- Hiện rõ trong danh sách/chi tiết shift: nếu có override, hiện badge "Ghi đè: {policy}" cạnh tên shift để HR dễ nhận biết ca nào khác site.

### 6.3 Màn "Chấm công" (App) — cả check-in lẫn check-out

- Đọc `effectiveCheckinPolicy` từ `available-sites` (check-in) hoặc từ chi tiết `CheckinDetailResponse.site`/`shift` (check-out, nếu cần tự resolve lại — khuyến nghị App tự cache `effectiveCheckinPolicy` từ lúc check-in để dùng lại cho check-out, tránh gọi thêm API) để quyết định UI theo bảng mục 2.2/3.
- Mã lỗi mới cần xử lý mượt (áp dụng cho CẢ check-in và check-out):

| errorCode | Hiển thị gợi ý |
|---|---|
| `FACE_ID_REQUIRED` | "Cần xác thực khuôn mặt để tiếp tục" — mở đúng luồng (ảnh tĩnh hoặc active-liveness) theo `effectiveCheckinPolicy`, đừng chỉ hiện text lỗi |
| `FACE_ID_NOT_ENROLLED` | "Bạn chưa đăng ký Face ID (hoặc chưa được duyệt)" + nút đi tới màn đăng ký Face ID |
| `CHECKIN_TOO_EARLY` / `CHECKIN_TOO_LATE` | Hiện đúng khung giờ cho phép lấy từ `checkinAllowedFrom`/`checkinAllowedUntil` trong `available-sites` |
| `SITE_INACTIVE` | "Công trình này hiện không hoạt động" — nên ẩn hẳn khỏi danh sách chấm công thay vì để bấm rồi báo lỗi (nếu site inactive vẫn xuất hiện trong `available-sites` do lỗi đồng bộ dữ liệu, báo cho backend) |
| 409 (message chứa "already used by another request") | Challenge liveness bị dùng trùng — hiếm gặp (race 2 request), hiện "Vui lòng thực hiện lại xác thực khuôn mặt" và bắt đầu challenge mới, đừng retry với cùng `livenessChallengeId` |
| 409 (message chứa "Already checked out at") | "Bạn đã check-out lượt này rồi" — refresh lại trạng thái, đừng cho bấm check-out lần nữa |

### 6.4 Màn "Lịch sử chấm công" (App)

- Mỗi dòng hiện đủ cả `checkInAt`/`checkOutAt` VÀ trạng thái xác thực khuôn mặt của cả 2 đầu nếu có (`faceVerified`/`checkoutFaceVerified`) — không chỉ hiện của check-in.
- `status=pending_review` — message hiển thị giờ chung chung hơn trước ("cần HR xem lại vị trí hoặc xác thực khuôn mặt") vì có thể đến từ nhiều nguyên nhân (geofence HOẶC face fail HOẶC offline-sync best-effort) — không hardcode message cũ chỉ nói về vị trí.

### 6.5 Màn "Danh sách chấm công" + "Chi tiết" (Web, HR)

- Cột trạng thái nên phân biệt được nguyên nhân `pending_review` nếu có thể (dựa vào `faceVerified`/`checkoutFaceVerified`/`checkInInsideGeofence`/`checkOutInsideGeofence` — nếu geofence đều `true` mà vẫn `pending_review`, khả năng cao là do face fail, nên hiện gợi ý "Có thể do xác thực khuôn mặt thất bại — xem chi tiết").
- Nút "Override trạng thái" (`PATCH .../override`) — ẩn nếu `record.status` đã đúng bằng trạng thái muốn set (backend trả `400` "already in status X — no change needed", nên FE disable trước khi bấm nếu trạng thái không đổi).
- Nếu record đến từ offline sync và bị escalate do `gps_face_liveness` không thể chứng minh offline, HR detail nên hiện rõ ghi chú "Chấm công offline — không thể xác thực chủ động, cần xem thủ công" thay vì để HR nhầm tưởng là gian lận.
- Hiện `employeeNote` và ảnh giải trình. Nếu `employeePhotoUrl` là API URL FAMS, tải dưới dạng Blob bằng API client có Bearer token; không gắn URL trực tiếp vào `<img>` vì endpoint evidence không public.

## 7. Sơ đồ nav đề xuất

```text
COMPANY PORTAL (fams-front-web-project) — TENANT_ADMIN / HR_MANAGER / SITE_SUPERVISOR
└── Công trình → Chi tiết site → form Sửa → dropdown "Chính sách chấm công" (MỚI, mục 6.1)
└── Ca làm → Chi tiết shift → form Sửa → dropdown "Ghi đè chính sách" (MỚI, mục 6.2)
└── Chấm công → Danh sách (đã có, lọc theo site) → Chi tiết → nút Override (đã có, cập nhật hiển thị mục 6.5)

MOBILE APP (fams-front-app-project)
└── Màn "Chấm công hôm nay" (available-sites)
    ├── Đọc effectiveCheckinPolicy mỗi site → quyết định camera trước khi bấm (mục 2.2)
    └── Banner cảnh báo nếu site yêu cầu Face ID mà chưa enrolled (mục 2.3)
└── Màn Check-in — bảng mã lỗi mục 6.3
└── Màn Check-out (MỚI: giờ cũng cần camera nếu site yêu cầu — mục 3)
└── Màn "Lịch sử chấm công" — hiện đủ trạng thái xác thực 2 đầu (mục 6.4)
└── Đồng bộ offline — cập nhật copy hiển thị theo bảng mục 4 (API không đổi)
```

## 8. Checklist bàn giao frontend

- [ ] **App — quan trọng nhất**: đổi logic mở camera từ "site có yêu cầu Face ID (boolean)" sang đọc `effectiveCheckinPolicy` (3 giá trị) — quyết định đúng loại camera (ảnh tĩnh hay active-liveness đầy đủ) theo bảng mục 2.2.
- [ ] **App**: thêm luồng camera vào màn **check-out** (trước đây check-out không cần camera) — áp cùng bảng quyết định như check-in.
- [ ] **App**: cache `effectiveCheckinPolicy` từ lúc check-in để tái sử dụng cho check-out cùng phiên, tránh phải gọi lại API resolve site/shift.
- [ ] **App**: cập nhật copy hiển thị cho các `status`/`reason` mới trong kết quả đồng bộ offline (mục 4) — không đổi code gọi API.
- [ ] **App**: hiện đủ 3 field xác thực check-out mới (`checkoutFaceVerified`/`checkoutLivenessVerified`/`checkoutFaceVerifyScore`) ở màn lịch sử/chi tiết, cùng cách với 3 field check-in đã có.
- [ ] **Web**: thêm dropdown `checkinPolicy` vào form Site (mục 6.1).
- [ ] **Web**: thêm dropdown `checkinPolicyOverride` + checkbox "quay về theo site" vào form Shift (mục 6.2).
- [ ] **Web**: hiện badge "Ghi đè chính sách" trên danh sách/chi tiết Shift khi có override.
- [ ] **Web**: cập nhật message `pending_review` ở chi tiết chấm công để không giả định chỉ do vị trí (mục 6.5).
- [ ] Không giả định site chỉ có 1 mức "yêu cầu Face ID hay không" (boolean cũ) ở bất kỳ đâu còn sót — field `requireFaceIdCheckin` đã bị xóa hoàn toàn khỏi API.
