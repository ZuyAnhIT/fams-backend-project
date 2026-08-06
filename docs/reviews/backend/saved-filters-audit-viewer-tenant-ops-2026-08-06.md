# Báo cáo audit nghiệp vụ: Bộ lọc dùng chung, Xuất dữ liệu, Tenant Operations, Usage Limits, Audit Viewer

**Ngày:** 2026-08-06
**Phạm vi:** 8 user story — lưu bộ lọc thường dùng, xuất danh sách vi phạm, khóa/mở tenant, xem chi tiết tenant vận hành, enforce giới hạn gói, xem danh sách audit log, xem diff old/new value, trace theo request_id.
**Tham chiếu thực tế:** Jira/Linear/Gmail (saved filters/views — cá nhân, không chia sẻ), Stripe/Auth0 (tenant suspend giữ nguyên dữ liệu, chỉ chặn truy cập), GitHub/AWS (usage limit chặn ở điểm tạo tài nguyên, không chỉ cảnh báo), Datadog/mọi hệ thống production đa khách hàng (audit log viewer PHẢI tự giới hạn theo tenant của người xem, không dựa vào permission chung chung).

---

## 1. Tóm tắt kết quả

| # | Hạng mục | Trước audit | Sau audit |
|---|---|---|---|
| 1 | **Audit Log Viewer rò rỉ dữ liệu xuyên tenant** | 🔴 TENANT_ADMIN/HR_MANAGER có quyền `audit:list` xem được audit log (email actor, IP, diff old/new value) của **MỌI tenant khác**, chỉ cần đổi query param `tenantId` hoặc bỏ trống | ✅ Ép phạm vi theo tenant thật của người gọi; sai tenant → 403; tenant lạ qua `/{id}` → 404; trace theo `request_id` lạ → danh sách rỗng |
| 2 | **Giới hạn gói (nhân viên) có thể bị lách qua luồng mời** | 🟡 `assertEmployeeLimit` đã tồn tại nhưng chỉ được gọi ở nơi tạo nhân viên trực tiếp, không được gọi khi **chấp nhận lời mời** — tenant có thể mời tràn lan trước, hạ gói sau, rồi accept vẫn thành công | ✅ Thêm check sớm (feedback) lúc gửi lời mời + check authoritative lúc accept (đúng thời điểm ghế thực sự bị tiêu) |
| 3 | **Lưu bộ lọc thường dùng — chưa tồn tại** | 🔴 Story yêu cầu rõ, không có entity/API nào | ✅ Xây mới module `saved-filters`, cá nhân theo user (không chia sẻ), tối đa 1 mặc định/resourceType |
| 4 | Xuất danh sách vi phạm | ✅ Đã đúng từ trước (đợt audit trước) | Không đổi |
| 5 | Khóa/mở tenant, xem chi tiết tenant vận hành | ✅ Đã đúng từ trước (đợt audit trước) | Không đổi |

Phát hiện quan trọng nhất đợt này là **mục 1** — lỗ hổng rò rỉ dữ liệu xuyên tenant nghiêm trọng trong tính năng vốn được thiết kế để phục vụ chính việc điều tra bảo mật/tuân thủ.

---

## 2. Đối chiếu từng story

### Story: Lưu bộ lọc thường dùng

**Chưa tồn tại — đã xây mới.** Tham khảo Jira/Linear/Gmail: "saved views"/"saved searches" luôn là tài sản **cá nhân** của người dùng, không mặc định chia sẻ cho cả team (khớp đúng cách user story diễn đạt "tôi muốn lưu filter", không phải "chúng tôi"). Thiết kế:

- Bảng `saved_filters`: `tenant_id`, `user_id`, `resource_type` (chuỗi tự do — "violations", "checkins", "employees"...), `name`, `filter_params` (JSONB lưu nguyên vẹn, backend không diễn giải — FE tự áp lại y hệt vào query params của endpoint danh sách tương ứng), `is_default`.
- Tối đa 1 filter mặc định cho mỗi `user + resourceType` — set default mới tự động bỏ default cũ (transaction, có unique index một phần làm lớp bảo vệ thứ 2 chống race).
- Tên trùng (không phân biệt hoa/thường) trong cùng `user + resourceType` → 409, tránh danh sách filter rối do trùng tên.
- Soft delete, không ảnh hưởng filter đã tạo trước nếu FE cache lại theo id.

**API:** `GET/POST/PATCH/DELETE /api/v1/tenants/{tenantId}/saved-filters` — xem chi tiết tại `docs/api/saved-filters-api.md`.

Live-test trực tiếp: tạo filter mặc định → tạo filter mặc định thứ 2 → xác nhận filter 1 tự động bị bỏ mặc định; PATCH đặt lại filter 1 làm mặc định → xác nhận filter 2 bị bỏ; tạo trùng tên → 409; xoá → 204; kiểm tra cách ly giữa 2 tenant khác nhau (0 kết quả xuyên tenant).

### Story: Xuất danh sách vi phạm

**Đã đúng từ trước** (xây dựng ở đợt audit `report-search-audit-2026-08-05.md`/violation dashboard trước đó) — không cần sửa gì thêm đợt này.

### Story: Khóa/mở tenant (Tenant Operations)

**Đã đúng từ trước.** Suspend giữ nguyên toàn bộ dữ liệu tenant (nhân viên, chấm công, vi phạm...), chỉ chặn đăng nhập/API — đúng mô hình Stripe/Auth0 (suspend ≠ xoá), reactivate khôi phục truy cập ngay không mất dữ liệu. Không cần sửa.

### Story: Xem chi tiết tenant vận hành

**Đã đúng từ trước.** Trả đủ subscription hiện tại, usage (số nhân viên/site đang dùng), và limit theo gói để Platform Admin đối chiếu. Không cần sửa.

### Story: Enforce giới hạn gói (Usage Limits)

`PlanLimitEnforcementService` đã có sẵn `assertEmployeeLimit`/`assertSiteLimit`/`assertRandomCheckLimit` — nhưng khi rà theo TOÀN BỘ luồng tạo nhân viên thực tế, phát hiện **1 đường tạo nhân viên hợp lệ hoàn toàn bỏ qua check**: luồng mời (`EmployeeInvitationService`).

- `sendInvitation()` **không hề gọi** `assertEmployeeLimit` — tenant có thể gửi hàng trăm lời mời dù đã đầy ghế theo gói.
- `acceptInvitation()` — nhánh tạo **Employee row mới** (khác nhánh liên kết vào record chưa gắn tài khoản có sẵn, nhánh đó không tiêu ghế mới) **cũng không gọi check** — nghĩa là dù `assertEmployeeLimit` tồn tại và được gọi đúng ở API tạo nhân viên trực tiếp, một tenant vẫn có thể **lách hoàn toàn giới hạn gói** bằng cách đi qua đường mời thay vì tạo trực tiếp.

Kịch bản khai thác thật đã dựng để verify: tenant ở gói cho phép 500 nhân viên → gửi lời mời (thành công, đúng) → **hạ gói tenant xuống trial (giới hạn 5, tenant đã có 40 nhân viên)** ngay trước khi ứng viên bấm accept → accept **vẫn thành công trước khi sửa** (bug xác nhận có thật) → sau khi sửa, accept bị chặn đúng `403 PLAN_LIMIT_EXCEEDED` → khôi phục gói → accept thành công lại.

**Đã sửa** theo đúng pattern GitHub/AWS (limit luôn được enforce tại **điểm tài nguyên thực sự được tạo**, không chỉ tại điểm khởi tạo request):
- Thêm check sớm (feedback, không authoritative) tại `sendInvitation()` — báo lỗi sớm cho HR biết ngay lúc gửi lời mời thay vì để ứng viên nhận lời mời rồi mới bị từ chối lúc accept.
- Thêm check authoritative tại đúng nhánh của `acceptInvitation()` tạo Employee row mới — đây là thời điểm ghế THỰC SỰ bị tiêu, race-safe vì diễn ra trong cùng transaction với việc tạo Employee.

### Story: Xem danh sách audit log

Endpoint `GET /api/v1/audit-logs` đã hỗ trợ đủ filter theo story (actor, entity, action, khoảng thời gian, `tenantId`) — nhưng khi kiểm tra kỹ **ai được xem gì**, phát hiện lỗ hổng nghiêm trọng nhất đợt audit này.

**Phát hiện: rò rỉ dữ liệu xuyên tenant hoàn toàn.** `@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('audit:list')")` chỉ kiểm tra caller có quyền `audit:list` **nói chung**, không hề đối chiếu với **tenant nào** caller thuộc về — trong khi `audit:list` là quyền được cấp thường quy cho `TENANT_ADMIN`/`HR_MANAGER` (vai trò tồn tại trong phạm vi MỘT tenant). `FamsUserDetails` (JWT principal) **không mang `tenantId`**, và service tầng dưới trước đây **không nhận bất kỳ tham số nào về danh tính người gọi** — nghĩa là:
- Một TENANT_ADMIN của tenant A gọi `GET /api/v1/audit-logs?tenantId=<tenant B>` → xem được toàn bộ audit log của tenant B: email actor, địa chỉ IP, và **nội dung diff old/new value** (có thể chứa dữ liệu cá nhân nhân viên, lương, thông tin nhạy cảm khác tuỳ hành động).
- Không truyền `tenantId` → xem được audit log gộp của **tất cả tenant trong hệ thống** trong cùng 1 lệnh gọi.

Đây đúng loại lỗi mà mọi hệ thống SaaS đa khách hàng production (Datadog, mọi nền tảng có audit trail) coi là nghiêm trọng nhất có thể có trong chính tính năng dùng để điều tra bảo mật/tuân thủ — vì nó biến công cụ giám sát thành công cụ do thám xuyên khách hàng.

**Đã sửa** — thêm tầng ép phạm vi (`enforceScope`) vào `AuditLogService`, áp dụng cho cả 3 đường đọc dữ liệu:
- **List** (`GET /api/v1/audit-logs`): Platform Admin — không đổi, xem được mọi tenant hoặc lọc theo `tenantId` bất kỳ. Non-platform-admin: nếu truyền `tenantId` không thuộc các tenant caller thực sự có vai trò active → `403`; nếu không truyền và caller chỉ thuộc đúng 1 tenant → tự động ép về tenant đó; nếu caller thuộc nhiều tenant cùng lúc (trường hợp thật đã gặp trong seed data — 1 user có role ở 2 tenant khác nhau) mà không truyền `tenantId` → `403` yêu cầu chỉ định rõ, tránh gộp nhầm dữ liệu 2 tenant.
- **Get by ID** (`GET /{id}`): nếu bản ghi tìm thấy thuộc tenant ngoài phạm vi caller → trả **404** (không phải 403) — đúng nguyên tắc bảo mật "không xác nhận sự tồn tại của tài nguyên ngoài phạm vi truy cập" (cùng nguyên tắc GitHub dùng cho private repo không có quyền: 404, không phải 403).
- **Trace theo request_id** (`GET ?requestId=...`): lọc kết quả trả về chỉ còn các entry trong phạm vi tenant caller — một request_id có thể xuất hiện ở nhiều tenant khác nhau nếu trùng lặp ngẫu nhiên (dù hiếm), không được để lộ entry ngoài phạm vi.

Live-test trực tiếp bằng dữ liệu thật (không chỉ đọc code):
- Platform Admin gọi không filter → thấy 10 tenant khác nhau trong 1 lệnh (không đổi, đúng như trước).
- User đa tenant thật trong seed data (`dung.pham.hr@gmail.com` — HR_MANAGER ở Hoàng Long + SITE_SUPERVISOR ở Bình Minh) gọi không truyền `tenantId` → đúng 403 "thuộc nhiều tenant, phải chỉ định".
- Cùng user truyền đúng `tenantId` của mình → kết quả đúng, chỉ 1 tenant duy nhất xuất hiện.
- Cùng user truyền `tenantId` của tenant khác (không thuộc về mình) → đúng 403.
- Lấy 1 bản ghi audit log thật của tenant khác qua `GET /{id}` → đúng 404 (không phải 403).
- Trace theo `request_id` thật của tenant khác → trả về danh sách rỗng (không lộ entry).

### Story: Xem diff old/new value

**Đã đúng từ trước ở tầng dữ liệu** (`AuditLogResponse.oldValue`/`newValue` — JSONB đầy đủ) — nhưng **thừa hưởng nguyên lỗ hổng ở mục trên** vì đi qua cùng endpoint `GET /{id}`. Đã được bảo vệ tự động sau khi sửa `getById` ở trên, không cần thay đổi thêm.

### Story: Trace theo request_id

Cột `request_id` và filter theo `requestId` đã tồn tại sẵn (đợt audit trước đã sửa để field này thực sự được ghi thay vì luôn `null` — xem `security-notifications-audit-2026-08-05.md`). Đợt này chỉ bổ sung đúng phần thiếu: **kết quả trace cũng phải được ép phạm vi theo tenant** giống list — đã sửa cùng lúc với `enforceScope` ở trên.

---

## 3. Danh sách thay đổi kỹ thuật

| File | Thay đổi |
|---|---|
| `AuditLogService.java` | Thêm `enforceScope` — ép `tenantId` theo caller; `listAuditLogs`/`getById`/`findByRequestId` nhận thêm `callerUserId`/`callerIsPlatformAdmin` |
| `AuditLogController.java` | Truyền `userDetails.getUserId()`/`isPlatformAdmin()` vào cả 3 lệnh gọi service; cập nhật mô tả Swagger |
| `EmployeeInvitationService.java` | Thêm `assertEmployeeLimit` ở `sendInvitation()` (feedback sớm) và ở nhánh tạo Employee mới trong `acceptInvitation()` (authoritative) |
| `savedfilter/` (module mới) | `SavedFilter` entity, repository, `CreateSavedFilterRequest`/`UpdateSavedFilterRequest`/`SavedFilterResponse` DTO, `SavedFilterService`, `SavedFilterController` |
| `V85__create_saved_filters.sql` (mới) | Bảng `saved_filters`, index theo `user+resourceType`, unique một phần cho tên (không phân biệt hoa/thường) và cho `is_default` |

**2 lỗi kỹ thuật gặp và sửa trong lúc xây Saved Filters** (chi tiết kỹ thuật, không ảnh hưởng nghiệp vụ cuối cùng):
1. Field `isDefault` trên DTO khiến Lombok sinh getter/setter lệch tên theo quy ước Jackson (`isDefault()`/`setDefault()` — 2 property JSON khác nhau) → request `{"isDefault":true}` bị bỏ qua âm thầm. Sửa: đổi tên field Java thành `defaultFilter`, dùng `@JsonProperty("isDefault")` để cố định tên JSON đúng như thiết kế.
2. Đặt 1 filter thứ 2 làm mặc định gây lỗi `duplicate key value violates unique constraint` — do thứ tự flush của Hibernate luôn chạy hết mọi INSERT đang chờ trước khi chạy UPDATE, bất kể thứ tự gọi `save()` trong code Java, khiến INSERT filter mới (is_default=true) chạm unique index TRƯỚC KHI UPDATE hạ filter cũ xuống false kịp tới DB. Sửa: dùng `saveAndFlush` cho bước hạ filter cũ, ép UPDATE chạy ngay lập tức trước khi INSERT mới được xếp hàng.

Build lại, compile sạch trên môi trường reseed hoàn toàn mới (18 tenant sạch, migration tới V85, RBAC seed nguyên vẹn — HR_MANAGER 52 quyền, SITE_SUPERVISOR 24 quyền). Live-test toàn bộ bằng dữ liệu thật như mô tả ở từng mục trên, dọn sạch dữ liệu test sau mỗi lần verify.

---

## 4. Giới hạn đã biết

- Saved Filters hiện là tài sản cá nhân, không chia sẻ giữa các user trong cùng tenant. Nếu sau này có nhu cầu thật "filter chung cho cả team", đó là tính năng khác (thêm cờ `shared` + đường đọc theo tenant), không phải sửa tính năng hiện tại.
- Audit log tenant-scoping đợt này chỉ sửa đúng 3 đường đọc hiện có (list, get-by-id, trace-by-request-id) — không mở rộng phạm vi audit logging sang các hành động khác trong hệ thống (đã nêu rõ giới hạn này ở báo cáo trước `security-notifications-audit-2026-08-05.md`, vẫn giữ nguyên quyết định phạm vi đó).
- `PlanLimitEnforcementService` mới được rà theo đúng 2 đường tạo nhân viên (trực tiếp + qua lời mời). Nếu có đường tạo nhân viên nào khác trong tương lai (ví dụ import hàng loạt), cần audit riêng đường đó có gọi đúng check không.

---

## 5. Kết luận

8 story đợt này chia làm 2 nhóm rõ rệt: 3 story (xuất vi phạm, khóa/mở tenant, xem chi tiết tenant) đã đúng từ trước, xác nhận không cần sửa; 1 story (lưu bộ lọc) hoàn toàn chưa tồn tại, đã xây mới đúng theo tham chiếu thực tế (Jira/Linear — cá nhân, không chia sẻ); 1 story (enforce giới hạn gói) phát hiện lỗ hổng lách giới hạn qua đường mời nhân viên, đã sửa và verify bằng kịch bản khai thác thật; và nhóm audit viewer (3 story: list, diff, trace) phát hiện lỗ hổng nghiêm trọng nhất — rò rỉ dữ liệu xuyên tenant hoàn toàn do thiếu đối chiếu danh tính người gọi với dữ liệu được truy vấn, đã sửa bằng tầng ép phạm vi tenant áp dụng thống nhất cho cả 3 đường đọc, verify trực tiếp bằng user đa tenant thật trong dữ liệu seed.

Môi trường đã được reset sạch hoàn toàn (drop volume, reseed từ đầu) sau đợt test này để loại bỏ dữ liệu test tích luỹ qua nhiều phiên audit trước, xác nhận lại đúng 18 tenant, migration tới V85, và RBAC nguyên vẹn.
