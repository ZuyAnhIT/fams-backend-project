# Tài liệu Check-in/Check-out — Review, nâng cấp và API tham chiếu

> Cập nhật theo code đang chạy ngày 29/07/2026 (đã gộp phản hồi Web/App round 2 — mục 9). Base path: `/api/v1/tenants/{tenantId}/checkin`.

## 9. [MỚI] Phản hồi từ Web + App sau khi tích hợp — 5 gap đã sửa (V78)

Web (`18_BAO_CAO_CHECKIN_UI_2026-07-29.md`) và App (`19_BAO_CAO_APP_CHECKIN_POLICY_OFFLINE_2026-07-29.md`) báo lại sau khi tích hợp theo tài liệu round 1 — đã xác minh từng điểm với code thật, tất cả đều đúng, đã sửa cả 5 (migration `V78__checkin_audit_fields_and_policy_snapshot.sql`):

| # | Vấn đề | Team báo | Đã sửa |
|---|---|---|---|
| P0 | `CheckinDetailResponse` thiếu 6 field Face ID/liveness (list có, detail không) | Web | Thêm đủ 6 field + mapper — mục 9.1 |
| P0 | Offline sync tính `checkinDate` theo UTC thay vì timezone site — sai ngày ở khung 00:00-06:59 giờ VN | App | Sửa `OfflineSyncService`, dùng site timezone — mục 9.2 |
| P1 | Không phân biệt được `null` = "không áp dụng" (`gps_only`) hay "đang xác thực" (worker chưa xong) | Cả 2 team | Snapshot `effectiveCheckinPolicy` — mục 9.3 |
| P1 | Checkout re-resolve policy LIVE — HR đổi policy giữa ca có thể kẹt nhân viên đã check-in ở checkout | App | Checkout dùng snapshot tại check-in, không re-resolve — mục 9.3 |
| P1 | Thiếu dấu vết offline/override (`source`, `clientNonce`, `note`, `overriddenBy/At`) trên detail | Web | Thêm đủ 4 field audit — mục 9.4 |
| P1 | Danh sách chỉ có `employeeId`/`siteId`, không có tên | Web | Batch-resolve `employeeName`/`employeeCode`/`siteName`, không N+1 — mục 9.5 |
| P2 | List thiếu GPS check-out (`checkOutLat/Lon/Accuracy/InsideGeofence`) | Web | Thêm đủ vào `CheckinResponse` |

### 9.1 6 field Face ID/liveness trên `CheckinDetailResponse`

Bug thật: `CheckinRecord` luôn có đủ 6 cột (`faceVerified`/`livenessVerified`/`faceVerifyScore`/`checkoutFaceVerified`/`checkoutLivenessVerified`/`checkoutFaceVerifyScore`), `toCheckinResponse` (list) map đủ, nhưng `toCheckinDetailResponse` bỏ sót hoàn toàn — Web phải fallback dùng bản ghi list lúc mở modal, mất dữ liệu khi deep-link hoặc refresh riêng màn detail. Đã thêm đủ 6 field + mapper.

### 9.2 Offline sync tính sai ngày do dùng UTC thay vì site timezone

Bug thật, đúng như App phát hiện: `OfflineSyncService.processSingle` tính `checkinDate = req.getCheckinAt().atZoneSameInstant(ZoneId.of("UTC")).toLocalDate()` — **trước khi** site được tải, nên hard-code UTC thay vì dùng đúng timezone của site. Với site Việt Nam (UTC+7), 1 check-in offline lúc 00:00–06:59 giờ VN bị tính nhầm sang ngày hôm trước theo UTC — sai luôn cả kiểm tra `daysOfWeek` lẫn kiểm tra kỳ assignment (`startDate`/`endDate`) ở đúng biên ngày.

Đã sửa: chuyển việc tải `Site` lên TRƯỚC bước tính `checkinDate`, dùng `ZoneId.of(site.getTimezone())` — cùng 1 `siteZone` này được tái sử dụng cho cả kiểm tra ngày/thứ VÀ cửa sổ check-in sớm ngay bên dưới (trước đây 2 chỗ tính zone riêng biệt, giờ dùng chung 1 biến, tránh lệch nhau).

**Verify**: test sống với assignment giới hạn chỉ Thứ Tư (`daysOfWeek` bitmask), gửi `checkinAt=2026-07-28T20:00:00Z` (UTC là Thứ Ba, nhưng giờ VN là Thứ Tư 03:00) — trước khi sửa sẽ bị `reject` với lý do "does not cover WEDNESDAY"; sau khi sửa, hệ thống đúng đắn coi đây là Thứ Tư (chỉ bị `reject` vì lý do KHÁC — chưa tới cửa sổ check-in sớm của ca — chứng minh phần ngày/thứ đã tính đúng).

### 9.3 Snapshot `effectiveCheckinPolicy` tại check-in — checkout dùng lại, không re-resolve

Giải quyết đồng thời cả 2 vấn đề Web và App nêu bằng 1 field: cột mới `checkins.effective_checkin_policy` lưu policy đã resolve (site + shift override) **tại đúng thời điểm check-in**, không phải giá trị cấu hình hiện tại.

- **Web dùng field này để phân biệt `null` có ý nghĩa gì**: nếu `effectiveCheckinPolicy=gps_only`, các field `faceVerified`/... `null` là "không áp dụng" (bình thường). Nếu khác `gps_only` mà vẫn `null`, đó là "đang chờ worker AI xử lý" — 2 tình huống UI cần hiển thị khác nhau hoàn toàn.
- **`submitCheckout` giờ enforce theo snapshot này** (`record.getEffectiveCheckinPolicy()`), không re-resolve live từ site/shift nữa. Quyết định này KHÔNG làm yếu khả năng chống "buddy checkout" — lớp bảo vệ đó đến từ việc "checkout luôn phải xác thực cùng mức check-in đã yêu cầu", không phải từ việc luôn dùng giá trị cấu hình mới nhất. Ngược lại, re-resolve live có thể kẹt nhân viên: nếu HR đổi site từ `gps_only` sang `gps_face_liveness` giữa lúc nhân viên đang trong ca (đã check-in theo policy cũ, không hề chụp challenge), checkout sẽ đòi hỏi 1 thứ họ chưa từng được thông báo cần chuẩn bị.
- Record tạo trước migration V78 không có snapshot (`NULL`) — checkout tự động fallback về re-resolve live cho đúng những bản ghi cũ đó, hành vi không đổi với dữ liệu lịch sử.
- `effectiveCheckinPolicy` cũng được set trong `OfflineSyncService` (đã tự tính `policy` sẵn từ trước, chỉ cần lưu lại).
- Trả trong cả `CheckinResponse` (list) và `CheckinDetailResponse`.

### 9.4 Dấu vết audit: `source`, `clientNonce`, `note`, `overriddenBy`/`overriddenAt`

Cột mới trên `checkins`: `source` (`online`|`offline`, NOT NULL mặc định `online`), `overridden_by` (UUID), `overridden_at` (timestamp). `clientNonce` và `note` đã tồn tại sẵn trên entity từ trước nhưng chưa từng được trả ra detail — giờ trả đủ.

- `source`: set `"online"` trong `submitCheckin`, `"offline"` trong `OfflineSyncService`. Backfill cho dữ liệu cũ: `UPDATE checkins SET source='offline' WHERE client_nonce IS NOT NULL` — chính xác vì trước giờ chỉ `OfflineSyncService` set `client_nonce`.
- `overriddenBy`/`overriddenAt`: set trong `overrideCheckin` (`PATCH .../override`) mỗi lần HR đổi trạng thái — **ghi đè** (không phải lịch sử tích lũy nhiều lần override), chỉ giữ lần gần nhất. Nếu về sau cần đầy đủ lịch sử override (nhiều lần), cần bảng riêng — chưa làm ở đợt này vì chưa có nhu cầu cụ thể.
- `note`: đã là field lưu lý do override (`OverrideCheckinRequest.reason` → `record.note`) từ trước, giờ mới trả ra detail.

### 9.5 Batch-resolve tên hiển thị cho danh sách — không N+1

`CheckinResponse` thêm `employeeName`/`employeeCode`/`siteName`. Với `submitCheckin`/`submitCheckout`/`overrideCheckin`/`getCheckinResult` (luôn thao tác 1 bản ghi, employee/site đã có sẵn hoặc rẻ để tra thêm 1 lần) — set trực tiếp, không tốn thêm truy vấn đáng kể. Với `listCheckins`/`getCheckinHistory` (phân trang nhiều bản ghi) — thêm `toCheckinResponsesBatch`: gom `employeeId`/`siteId` duy nhất của cả trang, chỉ 2 câu `findAllById` (không phải N+1), rồi map tên vào từng dòng.

**Verify**: test sống `GET .../checkin?page=0&size=3` — mỗi dòng có đủ `employeeName`/`employeeCode`/`siteName` đúng dữ liệu thật, không còn phải để Web tự tra cứu/khớp UUID.

### 9.6 Đề xuất của App team KHÔNG triển khai ở đợt này — cần quyết định nghiệp vụ trước

App đề xuất thêm: giới hạn thời gian tối đa được phép đồng bộ trễ (ví dụ: từ chối check-in offline quá N ngày), giới hạn sai lệch tối đa giữa đồng hồ thiết bị và server, chính sách lưu/xóa ảnh bằng chứng, device attestation/mock-location signal, push notification khi 1 bản ghi bị escalate sang `pending_review`. Đây đều là **quyết định chính sách có ảnh hưởng tới bảng công/payroll thật** (ví dụ: "N ngày" là bao nhiêu sẽ chặn/chấp nhận bao nhiêu ca hợp lệ của công nhân vùng sóng yếu) — cố tình KHÔNG tự chọn 1 con số và áp đặt, cần bạn quyết định trước khi triển khai, giống nguyên tắc đã áp dụng suốt các đợt review trước.

Bạn đưa ra 7 tính năng check-in/check-out cần review, đặc biệt lưu ý **các mức xác thực (GPS cơ bản / Face ID / liveness) phải mix được với nhau theo Site/Shift do admin/HR tự chọn**, không phải bật/tắt toàn hệ thống. Đối chiếu với hệ thống chấm công thực tế (TSheets/QuickBooks Time, When I Work, BuddyPunch, Deputy — đều cho cấu hình xác thực theo địa điểm, và đều xác thực CẢ check-in lẫn check-out để chặn "buddy punching"), đã:

| # | Tính năng | Trạng thái trước | Việc đã làm |
|---|---|---|---|
| 1 | Check-in GPS cơ bản | Đã có | Giữ nguyên, xác nhận đúng |
| 2 | Check-in Face ID / 3 | Chỉ có boolean bật/tắt toàn site, không mix được | Nâng cấp thành **3 tầng cấu hình theo Site, override theo Shift** — mục 1 |
| 4 | Kiểm tra check-in sớm | Đã có (Task 71) | Giữ nguyên, xác nhận đúng |
| 5 | Check-out GPS + Face ID | Check-out KHÔNG có Face ID, chỉ có GPS | **Bổ sung mới hoàn toàn** — mục 2 |
| 6 | Kiểm tra check-out muộn | Đã có (Task 73, late-checkout cap) | Giữ nguyên, xác nhận đúng |
| 7 | Tính work_minutes | Đã có (Task 73/74) | Giữ nguyên, xác nhận đúng |
| 8 | Check-in offline + đồng bộ | Có endpoint nhưng **thiếu 5 rule** so với check-in online (mục 3) | Vá đầy đủ — mục 3 |
| 9 | Hiển thị kết quả check-in/out | Đã có | Bổ sung field checkout mới |

Và phát hiện thêm ngoài phạm vi ban đầu, đã sửa:
- **1 lỗi nghiêm trọng (race condition mất dữ liệu)** phát hiện qua test sống — mục 4.
- Sweep sạch mọi tham chiếu tới field `requireFaceIdCheckin` cũ (đã bị xóa hoàn toàn khỏi entity, DTO, Javadoc).

**Kết quả test**: build lại `fams-api`, test sống qua API thật (tạo site/shift/assignment thật, không mock) cho: 3 tầng chính sách + override theo shift, checkout Face ID bắt buộc ngang check-in, escalate + tạo violation khi face fail, race condition đã sửa (xác nhận `check_out_at` không còn bị revert), cơ chế active-liveness challenge (start → nộp frame sai hành động → `failed` đúng, thử checkin/checkout với challenge failed → bị từ chối đúng). Chạy lại `tests/checkin/*.sh` (10 file, đã sửa 2 lỗi kịch bản test có từ trước không liên quan tới đợt này — mục 6) — tất cả pass. Reset + reseed sạch dữ liệu demo (15 tenant, 50 site) sau khi test xong.

## 1. Chính sách xác thực 3 tầng, cấu hình theo Site + override theo Shift

### 1.1 Vì sao 3 tầng thay vì boolean

Site cũ chỉ có `requireFaceIdCheckin` (boolean) — không thể diễn tả "site này cần ảnh mặt nhưng không cần liveness chủ động" (nhiều công trường không cần mức bảo mật cao nhất, chỉ cần ảnh xác nhận có mặt). Đã hỏi và bạn xác nhận chọn phương án: **3 tầng, cấu hình theo Site, Shift có thể override**.

```
gps_only          — chỉ cần GPS trong geofence, không cần ảnh
gps_face          — cần Face ID: ảnh tĩnh (employeePhotoBase64) HOẶC challenge liveness đã pass đều được chấp nhận
gps_face_liveness — cần Face ID CHỦ ĐỘNG: bắt buộc challenge liveness đã pass, ảnh tĩnh không đủ
```

`Site.checkinPolicy` (mặc định `gps_only`) là chính sách nền cho toàn site. `Shift.checkinPolicyOverride` (nullable, mặc định `null` = kế thừa site) cho phép ghi đè theo TỪNG ca — đúng nhu cầu bạn nêu: "công trường này có thể có GPS cơ bản, Face ID, liveness — chọn một hoặc tích hợp nhiều cái" (ví dụ: site kho vật tư mặc định `gps_face_liveness`, nhưng ca hành chính ban ngày có bảo vệ trực tiếp giám sát có thể override về `gps_only`).

**Cách resolve** (`resolveEffectiveCheckinPolicy`, dùng chung ở `CheckinService`/`OfflineSyncService`): override của Shift thắng nếu có set, ngược lại dùng policy của Site. Được test sống: site set `gps_face_liveness`, shift override `gps_only` → `effectiveCheckinPolicy` trả về đúng `gps_only`.

### 1.2 API cấu hình

- `POST`/`PUT /sites` — field `checkinPolicy` (string, 1 trong 3 giá trị trên, validate ở `SiteService.validateCheckinPolicy`).
- `POST`/`PUT /shifts` — field `checkinPolicyOverride` (nullable). `PUT` hỗ trợ thêm `clearCheckinPolicyOverride: true` để quay về kế thừa site (cùng pattern với `clearCode`/`clearEndDate` đã dùng ở module Shift).
- `GET .../checkin/available-sites` — mỗi item trả `effectiveCheckinPolicy` (đã resolve sẵn cho đúng site+shift của occurrence đó) — **App phải dựa vào field này để quyết định mở camera trước khi gọi checkin**, không tự suy luận từ `site.checkinPolicy`.

### 1.3 Enforcement (dùng chung cho cả check-in lẫn check-out — `CheckinService.enforceCheckinPolicy`)

| Policy | Yêu cầu | Chấp nhận |
|---|---|---|
| `gps_only` | Không có | — |
| `gps_face` | Đã enroll Face ID (đã được HR duyệt) | Ảnh tĩnh (`employeePhotoBase64`) **HOẶC** challenge liveness đã pass (challenge là bằng chứng mạnh hơn nên vẫn được chấp nhận) |
| `gps_face_liveness` | Đã enroll Face ID | **CHỈ** challenge liveness đã pass — ảnh tĩnh bị từ chối |

Challenge dùng cho checkin/checkout phải thỏa cả 4 điều kiện, kiểm tra tại thời điểm tiêu thụ (không chỉ lúc pass):
1. `purpose` khớp đúng (`checkin` hoặc `checkout`) — challenge check-in không dùng được cho check-out và ngược lại.
2. `siteId` khớp đúng site đang chấm công — không mang challenge pass ở site A sang dùng ở site B.
3. Còn mới — `completedAt` trong vòng **2 phút** trước khi tiêu thụ (`CHECKIN_CHALLENGE_FRESHNESS_MINUTES`).
4. Tiêu thụ atomic (`consumeIfPassed`, 1 câu UPDATE) — 2 request cùng dùng 1 challenge gần như đồng thời, chỉ 1 thắng, request thua nhận `409 DUPLICATE_RESOURCE` rõ ràng.

## 2. Check-out GPS + Face ID (tính năng mới)

### 2.1 Vì sao — tham khảo thực tế

Trước đây check-out chỉ có GPS, không xác thực gì thêm — một lỗ hổng "buddy checkout" kinh điển: đồng nghiệp có thể bấm check-out hộ ai đó đang về sớm để không bị trừ giờ công. Các hệ thống chấm công doanh nghiệp thực tế (TSheets/QuickBooks Time, When I Work, BuddyPunch) đều xác thực khuôn mặt ở **cả hai đầu** phiên làm việc, không chỉ lúc vào — chặn đúng kiểu gian lận này ở đầu ra.

### 2.2 Thiết kế đã chọn: bắt buộc ngang check-in

Bạn xác nhận qua câu hỏi lựa chọn: **check-out phải xác thực cùng mức độ nghiêm ngặt với check-in** (không được yếu hơn). `submitCheckout` re-derive `effectivePolicy` ngay tại thời điểm check-out (không tái sử dụng policy đã áp lúc check-in — vì policy không phải số liệu ảnh hưởng lương như giờ ca, nên phản ánh chính sách hiện tại là hợp lý và đúng hơn), rồi gọi đúng `enforceCheckinPolicy` dùng chung với check-in, chỉ khác `purpose="checkout"`.

`SubmitCheckoutRequest` bổ sung 3 field mới, đối xứng với check-in: `employeePhotoBase64`, `requiresLiveness`, `livenessChallengeId`.

### 2.3 Lưu trữ tách biệt với kết quả check-in

`CheckinRecord` có 2 bộ cột độc lập trên cùng 1 dòng: `faceVerified/livenessVerified/faceVerifyScore` (check-in) và `checkoutFaceVerified/checkoutLivenessVerified/checkoutFaceVerifyScore` (check-out) — vì 1 phiên làm việc có 2 lượt xác thực riêng biệt, không được ghi đè lẫn nhau. `FaceResultCallbackController` định tuyến kết quả AI trả về đúng bộ cột dựa trên `sourceType` (`checkin`/`checkout`).

### 2.4 Escalate + violation khi thất bại — đối xứng cả 2 chiều

Face fail lúc check-in **hoặc** check-out (tại site/shift yêu cầu Face ID) đều: hạ `status` xuống `pending_review` (nếu đang `valid`) + tạo `Violation` (`violation_type = face_fail` hoặc `liveness_fail`, `checkin_id` trỏ về đúng dòng). Đã test sống và xác nhận qua DB: 1 dòng `CheckinRecord` có thể có **2 violation riêng biệt** (1 từ check-in, 1 từ check-out) với `description` phân biệt rõ "during check-in" / "during check-out".

## 3. Check-in offline + đồng bộ — vá 5 lỗ hổng so với luồng online

`OfflineSyncService` (endpoint `POST .../checkin/sync`, nhận mảng `OfflineCheckinRequest`) được viết TRƯỚC nhiều đợt hardening của `submitCheckin` và không được cập nhật theo — review phát hiện **5 rule mà luồng online có nhưng offline sync thiếu hoàn toàn**, đã vá đủ cả 5:

| # | Thiếu | Rủi ro | Đã vá |
|---|---|---|---|
| 1 | Không kiểm tra `employee.status`/`site.status` phải `active` | Nhân viên đã nghỉ việc/site đã đóng vẫn đồng bộ được check-in cũ | Reject nếu không active, cùng message với luồng online |
| 2 | Không kiểm tra ngày-trong-tuần của assignment (`daysOfWeek`) | Chấm công vào ngày assignment không phủ vẫn được chấp nhận | Dùng lại `DayOfWeekBitmask.fromBitmask`, cùng logic `findActiveAssignmentsForEmployeeOnDate` |
| 3 | Không kiểm tra cửa sổ check-in sớm (Task 71) cho mốc thời gian lịch sử | Thiết bị offline có thể "đồng bộ" 1 check-in với timestamp giả sớm hơn nhiều so với ca | Re-derive `shiftStart - earlyCheckinMinutes` cho đúng `checkinDate` lịch sử, theo timezone của site |
| 4 | Không snapshot field ca làm (`shiftStartTime`/`shiftEndTime`/...) lên `CheckinRecord` | `work_minutes` lúc checkout sau này rơi về "không có ca" (raw duration, không cap) cho MỌI phiên đồng bộ offline | Snapshot giống hệt luồng online (nguyên tắc "snapshot tại thời điểm hành động" từ V72) |
| 5 | Exception `DataIntegrityViolationException` (từ index unique 1-phiên-mở/nhân viên, V73) không được bắt — 1 record lỗi làm sập TOÀN BỘ batch còn lại | 1 bản ghi xung đột khiến các bản ghi hợp lệ khác trong cùng lượt sync cũng mất | Bắt exception, trả `SyncResultItem.status="conflict"` cho đúng record đó, các record khác trong batch vẫn xử lý bình thường |

### 3.1 Vì sao Face ID offline PHẢI xử lý khác — không phải bug, là thiết kế cố ý

Active-liveness challenge về bản chất là **thời gian thực** (quay đầu/nháy mắt theo lệnh ngay lúc đó) — không thể chứng minh lại cho một mốc thời gian đã qua khi thiết bị mất mạng. Vì vậy offline sync xử lý Face ID theo kiểu **best-effort**, khác hẳn (không phải kém hơn 1 cách tùy tiện) luồng online:

- `gps_face` — chấp nhận ảnh tĩnh (`facePhotoBase64`) làm bằng chứng đủ, đúng như nhánh fallback ảnh tĩnh của luồng online.
- `gps_face_liveness` — **không bao giờ coi là thỏa mãn được khi offline** — dù geofence đúng và có ảnh, vẫn luôn hạ xuống `pending_review` để HR xem lại thủ công, thay vì âm thầm chấp nhận bằng chứng yếu hơn (chỉ ảnh) như thể đó là mức mạnh nhất.

### 3.2 Idempotency và conflict — hành vi giữ nguyên, xác nhận đúng

`clientNonce` trùng → trả lại `status="accepted"` của lần trước (không tạo lại). Có `CheckinRecord` khác đè lên cùng khung giờ của assignment (`findOverlappingSession`) → `status="conflict"`, không tạo mới, không lỗi.

## 4. [Lỗi nghiêm trọng phát hiện qua test sống] Race condition mất dữ liệu check-out

### 4.1 Phát hiện

Trong lúc test sống tính năng check-out Face ID (mục 2), một response API check-out trả về `checkOutAt` hợp lệ (`200 OK`), nhưng truy vấn DB ngay sau đó cho thấy `check_out_at` là **NULL**. Không phải lỗi ngẫu nhiên — tái hiện được ổn định.

### 4.2 Nguyên nhân gốc

`submitCheckout` VÀ callback xác thực khuôn mặt bất đồng bộ (`FaceResultCallbackController`) đều thao tác trên **cùng 1 dòng** `CheckinRecord` theo cùng 1 pattern: load full entity → sửa vài field → `checkinRepository.save(record)`. `save()` của Hibernate ghi **TOÀN BỘ** cột đã map từ bản sao trong bộ nhớ của transaction đó — nên nếu 2 transaction chạy gần như đồng thời (worker AI trong môi trường dev không có độ trễ AI thật nên phản hồi rất nhanh, dễ trùng với lúc transaction check-out vừa commit), transaction nào commit SAU sẽ âm thầm ghi đè mất field mà transaction kia vừa ghi — kể cả field nó không hề chủ đích đụng tới, vì nó ghi lại từ bản sao cũ trong bộ nhớ.

Đây là lỗi **"lost update"** kinh điển — không có `@Version` (optimistic locking) trên `CheckinRecord`, và cả 2 writer đều dùng full-entity save thay vì update theo đúng cột.

### 4.3 Cách sửa

Chuyển toàn bộ ghi dữ liệu có nguy cơ tranh chấp sang **UPDATE chỉ động vào đúng cột mỗi bên sở hữu** (`@Modifying @Query` trong `CheckinRepository`), để 2 transaction ghi vào tập cột KHÔNG giao nhau không thể ghi đè lẫn nhau bất kể thứ tự commit:

```java
applyCheckout(id, checkOutAt, lat, lon, accuracy, insideGeofence, workMinutes)      // submitCheckout sở hữu
applyCheckinFaceResult(id, faceVerified, livenessVerified, score)                   // callback (check-in) sở hữu
applyCheckoutFaceResult(id, faceVerified, livenessVerified, score)                  // callback (check-out) sở hữu
escalateToPendingReviewIfValid(id)   // UPDATE ... WHERE status='valid' — idempotent, không thể "un-escalate" nhau
```

`escalateToPendingReviewIfValid` xứng đáng nói riêng: đây KHÔNG chỉ là 1 update theo cột, mà còn có `WHERE status='valid'` — nên 2 caller cùng gọi hàm này (ví dụ geofence fail VÀ face fail xảy ra gần như đồng thời) không bao giờ "giẫm chân" nhau theo hướng nguy hiểm (khôi phục nhầm về `valid`); chỉ có chiều chuyển 1 lần `valid → pending_review` là hợp lệ.

### 4.4 Verify

Test lại đúng kịch bản đã phát hiện lỗi: check-out kèm ảnh tại site `gps_face`, đợi callback AI chạy xong (bất đồng bộ), truy vấn DB 2 lần cách nhau vài giây — `check_out_at` giữ nguyên giá trị đã ghi, không bị revert. Log xác nhận callback đã chạy (`Face result recorded (checkout)`) đúng lúc, không lỗi.

## 5. Active-liveness challenge cho check-in/check-out — giới hạn test trung thực

Cơ chế active-liveness (quay đầu/nháy mắt theo lệnh ngẫu nhiên, xem chi tiết ở `docs/api/face-id-management-api.md` §0.1) đã được mở rộng để hỗ trợ `purpose=checkout` (trước chỉ có `enroll`/`checkin`) — cùng cơ chế `center` + 2 hành động ngẫu nhiên, cùng pipeline kiểm tra góc đầu (`solvePnP`)/nháy mắt (EAR)/cùng-1-người/anti-spoof.

**Đã test sống được**: start challenge `purpose=checkin` và `purpose=checkout` (cả 2 đều nhận đúng `siteId`, trả về 3 hành động ngẫu nhiên); nộp cùng 1 ảnh tĩnh cho cả 3 hành động → hệ thống đúng đắn trả `status=failed` kèm chi tiết từng bước (chỉ `center` đạt, `blink`/`look_up`/`look_down`/`turn_left` đều fail vì ảnh tĩnh không thể "thực hiện" các hành động đó); dùng challenge `failed` đó để gọi checkin/checkout → cả 2 đều bị từ chối đúng `422 FACE_ID_REQUIRED` với message rõ ràng ("not a passed, unconsumed, fresh challenge").

**Giới hạn — chưa test được (và lý do)**: môi trường test này (agent chạy trong container, không có camera) chỉ có 1 ảnh khuôn mặt tĩnh làm fixture — không có ảnh mặt thật quay các góc khác nhau hoặc nháy mắt thật, nên **không tạo ra được 1 challenge `status=passed` thật** để test tiếp luồng sau đó (checkin/checkout thành công với challenge đã pass, worker AI khớp embedding, `faceVerified=true`). Đây là giới hạn giống hệt đã ghi nhận trong `face-id-management-api.md` §0.1 khi tính năng active-liveness được xây dựng lần đầu. Khuyến nghị đội QA có camera thật validate lại luồng "passed" đầy đủ trước khi golive rộng — phần cơ chế/enforcement (mục trên) đã verify chắc chắn, chỉ riêng khâu "tạo dữ liệu test thật" là giới hạn của môi trường agent này.

## 6. Sửa 2 lỗi kịch bản test có sẵn (không liên quan tới thay đổi module này)

Khi chạy lại `tests/checkin/*.sh` để hồi quy, phát hiện và sửa 2 lỗi độc lập, có từ trước, không phải do đợt sửa này gây ra:

1. **`test_available_sites.sh`, `test_checkout.sh`, `test_basic_checkin.sh`**: bước login nhân viên dùng sai tên field body (`{"email": ...}` thay vì `{"identifier": ...}` — API `/auth/login` chỉ nhận `identifier`). Có lẽ kịch bản test được viết trước khi field đổi tên, không cập nhật theo. Đã sửa cả 5 chỗ (2 script có 2 nhân viên/kịch bản mỗi file).
2. **`test_basic_checkin.sh`**: tenant test mới tạo mặc định ở gói `trial` (giới hạn 1 site), trong khi kịch bản test cần tạo site thứ 2 → `422 PLAN_LIMIT_EXCEEDED`. Các bộ test khác trong `tests/site/*.sh` đã có sẵn pattern xử lý (bump tenant lên gói `enterprise` ngay sau khi tạo) nhưng `test_basic_checkin.sh` chưa áp dụng. Đã thêm đúng pattern đó.

Sau khi sửa: `test_available_sites.sh` (6/6 pass), `test_basic_checkin.sh` (11/11 pass), `test_checkout.sh` (9/9 pass).

## 7. Mã lỗi liên quan tới check-in/check-out cần FE xử lý

| HTTP | errorCode | Khi nào |
|---|---|---|
| 422 | `FACE_ID_REQUIRED` | Chính sách site/shift yêu cầu Face ID/liveness nhưng thiếu ảnh, thiếu challenge, hoặc challenge không hợp lệ (sai purpose/site/hết hạn/chưa pass) — áp dụng cho CẢ check-in lẫn check-out |
| 422 | `FACE_ID_NOT_ENROLLED` | Chính sách yêu cầu Face ID nhưng nhân viên chưa `enrolled` (hoặc chưa được HR duyệt) |
| 422 | `CHECKIN_TOO_EARLY` / `CHECKIN_TOO_LATE` | Ngoài cửa sổ cho phép của ca làm (không đổi so với trước) |
| 422 | `SITE_INACTIVE` | Site không còn `active` |
| 403 | `EMPLOYEE_NOT_ACTIVE` | Nhân viên không còn `active`, không thể chấm công |
| 409 | (message: "This active-liveness challenge was already used...") | 2 request cùng dùng 1 challenge gần như đồng thời — request thua |
| 409 | (message: "Already checked out at...") | Check-out lần 2 trên cùng 1 phiên |
| 409 | (message: "Employee already has an open check-in...") | Check-in khi đang có phiên mở khác (kể cả site khác) |
| 200 (không phải lỗi) | `status: "conflict"` trong `SyncResultItem` | Offline sync: bản ghi đã tồn tại/trùng phiên — không phải lỗi HTTP, kiểm tra field `status` từng item |

## 8. Xác nhận thêm — không cần sửa

- **`work_minutes`** (mục Tính năng 7 trong yêu cầu ban đầu): logic cap theo late-checkout/overtime (Task 73/74) đã đúng, dùng snapshot giờ ca tại thời điểm check-in (không re-fetch live) — xác nhận đúng, không đổi.
- **Kiểm tra check-in sớm/check-out muộn** (Task 71/73): đã đúng từ trước, không đổi.
- **Hiển thị kết quả check-in/out**: `CheckinResponse`/`CheckinDetailResponse` đã đủ field cần thiết; bổ sung 3 field checkout Face ID mới (`checkoutFaceVerified`/`checkoutLivenessVerified`/`checkoutFaceVerifyScore`) đối xứng với 3 field check-in đã có sẵn.
