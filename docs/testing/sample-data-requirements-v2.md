> **[ĐÃ TRIỂN KHAI 02/08/2026]** Toàn bộ 6 việc ở mục "Tổng kết — việc cần làm tiếp theo" đã implement + reseed sạch + regression 31/31 pass:
> 1. 10 nhóm nghiệp vụ (mục 6.1) — mỗi tenant chuyên sâu (Hoàng Long/Bình Minh/Phương Nam) giờ có 40 nhân viên (15 đặt tên tay + 25 sinh theo đúng 7 nhóm còn thiếu: Quản lý công trình/Trưởng bộ phận/Giám sát công trình/Nhân viên công trình/Nhân viên văn phòng/Nhân viên thời vụ/Nhân viên thử việc), qua hàm `mk_employee_group` mới trong `scripts/seed.sh`.
> 2. 3 role tùy chỉnh/tenant chuyên sâu (9 role tổng, trước đó 1/tenant).
> 3. Workspace 3 nhánh × 3 cấp/tenant chuyên sâu (9 nhánh tổng, trước đó 1 nhánh/tenant).
> 4. 3 nhân viên làm việc đồng thời tại 2 công trình khác nhau (1/tenant chuyên sâu).
> 5. 2 tài khoản `chuaxacthucmail1/2@gmail.com` đã liên kết vào nhân viên thật (trước đó là user độc lập, không gắn employee).
> 6. Lý do từ chối Face ID đa dạng hóa thành 5 câu khác nhau (trước đó lặp lại 1 câu).
>
> **Bộ dữ liệu hiệu năng (mục 20.4)**: đã build `scripts/seed_perf.sql` (+ `scripts/seed_perf_cleanup.sql` để dọn riêng) — test chạy thật cho ra **150 tenant, 9.455 user, 797 site, 23.302 nhân viên (2 mega tenant 2.500 nhân viên/mỗi, đúng khoảng 1.000-5.000/1 công ty), 1.111.986 dòng checkins** (~1.1 triệu, đạt "hàng triệu" ở cận dưới — có thể tăng bằng cách sửa hằng số `mega_checkin_days`/`normal_checkin_days` trong file nếu cần nhiều hơn). Đã chạy thử + dọn sạch lại, KHÔNG để lẫn vào bộ demo 18-tenant chính. Xem hướng dẫn chạy ở đầu `scripts/seed_perf.sql`.

# TÀI LIỆU YÊU CẦU DỮ LIỆU MẪU LỊCH SỬ (v2 — đã thay thế)

> **Không còn là đặc tả seed đang chạy.** Tài liệu này được giữ lại để truy vết quyết định cũ về bộ dữ liệu tải lớn. Bộ seed chức năng hiện hành được mô tả tại `docs/testing/demo-seed-data.md`: 3 tenant, một tenant chính có 15 thành viên và dữ liệu bắt đầu từ tháng 09/2026.

> Viết lại từ bộ yêu cầu bạn cung cấp (22 mục), **đối chiếu từng dòng với schema database thật (43 bảng) và business logic thật đã audit** (`docs/api/backend-feature-audit-2026-08-01.md`), không chỉ chép lại nguyên văn. Nguyên tắc xử lý: mục nào hệ thống **có** đúng khái niệm tương ứng → giữ lại, đổi tên đúng thuật ngữ hệ thống đang dùng, gắn field/entity thật. Mục nào hệ thống **không có** entity/cột dữ liệu tương ứng → đánh dấu rõ **[KHÔNG HỖ TRỢ]**, không bịa dữ liệu giả cho khái niệm không tồn tại (làm vậy sẽ tạo ra dữ liệu "ma" không ai dùng được, và có thể khiến người test hiểu nhầm là tính năng đã có). Mục nào hệ thống có nhưng **khác cách hoạt động** so với mô tả gốc → ghi chú "thực tế hệ thống hoạt động như sau" để không mô tả sai lệch nghiệp vụ.

## Bảng đối chiếu thuật ngữ (đọc trước khi dùng tài liệu này)

| Thuật ngữ trong yêu cầu gốc | Tên/entity thật trong hệ thống | Ghi chú |
|---|---|---|
| Công ty | `Tenant` | — |
| Phòng ban / đơn vị tổ chức | `Workspace` (type = `department` hoặc `team`) | Không có entity `Department` riêng — đã hợp nhất vào Workspace từ trước |
| Công trình | `Site` | — |
| Vùng làm việc | `Geofence` | Là **đa giác** (mảng tọa độ ≥4 điểm khép kín) + `bufferMeters` (dung sai mét quanh biên) — KHÔNG có "dạng bán kính" như 1 field riêng, dạng tròn phải tự vẽ đa giác xấp xỉ hình tròn |
| Ca làm việc | `Shift` | — |
| Phân công | `Assignment` | 1 nhân viên chỉ có **1 assignment active tại 1 site** tại 1 thời điểm (ràng buộc DB) |
| Gói dịch vụ | `Plan` + `PlanLimits` + `TenantSubscription` | Không có khái niệm "gói miễn phí" riêng — gói thấp nhất là `trial` (dùng thử có hạn, không phải "free" vĩnh viễn) |
| Lời mời | `EmployeeInvitation` (mời nhân viên) / `PlatformInvitation` (mời nhân viên nền tảng) | — |
| Random Check | `RandomCheckConfig` + `ScheduledCheck` + `CheckResponse` | — |
| Vi phạm | `Violation` | 4 loại: `no_response`, `location_fail`, `face_fail`, `liveness_fail` |
| Tổng kết công / bảng công | `AttendanceSummary` | — |
| Chấm công | `CheckinRecord` (bảng `checkins`) | 1 bản ghi chứa cả check-in lẫn check-out (không phải 2 bảng riêng) |

---

## 1. Mục đích

Giữ nguyên như bản gốc — dùng cho dev/test/demo, **không dùng cho production**.

## 2. Nguyên tắc chung

Giữ nguyên 2.1 (đủ số lượng), 2.4 (dữ liệu thực tế, không "Test 1/User 1"), 2.5 (script tái tạo được), 2.6 (mật khẩu chung, quy ước email/phone dễ nhận biết, có tài liệu liệt kê tài khoản) — **những nguyên tắc này áp dụng được 1:1, không cần điều chỉnh.**

### 2.2. Đủ trạng thái nghiệp vụ — điều chỉnh theo trạng thái THẬT của từng entity

Yêu cầu gốc liệt kê 1 danh sách trạng thái chung chung (mới tạo/chờ xác nhận/tạm khóa/hết hạn/bị từ chối...) áp cho mọi entity. Thực tế **mỗi entity trong hệ thống có tập trạng thái riêng, cố định bằng CHECK constraint ở DB** — không thể gán tùy ý trạng thái nào cho entity nào. Bảng dưới đây liệt kê đúng tập trạng thái thật, dùng để build dữ liệu thay cho danh sách chung chung gốc:

| Entity | Trạng thái thật (CHECK constraint) |
|---|---|
| `tenants.status` | `trial`, `active`, `suspended`, `cancelled` |
| `tenant_subscriptions.status` | `TRIAL`, `ACTIVE`, `CANCELLED` (+ hết hạn suy ra từ `expires_at` đã qua, không phải 1 status riêng) |
| `plans.is_active` | boolean (đang bán / ngừng bán) |
| `employees.status` | **CHỈ CÓ 3 giá trị**: `active`, `inactive`, `terminated` — không có "thử việc", "nghỉ phép", "nghỉ không lương", "thời vụ", "chờ nhận việc" (xem mục 6 bên dưới, phần [KHÔNG HỖ TRỢ]) |
| `employee_invitations.status` | `pending`, `accepted`, `expired`, `cancelled` — không có "đã mở/chưa mở" (hệ thống không track việc mở email) |
| `workspaces.status` | `active`, `inactive` |
| `sites.status` | `active`, `inactive` (không có "chưa bắt đầu/tạm dừng/sắp hoàn thành/quá hạn" — Site không phải là "dự án có tiến độ", chỉ là địa điểm chấm công cố định, xem mục 9) |
| `geofences.status` | `active`, `superseded` (bản cũ tự động chuyển khi có bản mới, không xóa) |
| `shifts.status` | `active`, `inactive` |
| `assignments.status` | `active`, `cancelled` (+ hết hạn tự nhiên suy ra từ `endDate` đã qua, không phải status riêng) |
| `checkins.status` | `valid`, `pending_review` |
| `face_profiles.status` | `not_enrolled`, `enrolled`, `revoked` — độc lập với `review_status`: `none`, `pending`, `rejected` (2 chiều dữ liệu riêng biệt, xem mục 13) |
| `scheduled_checks.status` | `pending`, `sent`, `responded`, `no_response`, `cancelled` |
| `violations.resolution` | chưa xử lý (NULL) / `confirmed` / `dismissed` |
| `notifications.is_read` | boolean |
| `users.locked_until`, `email_verified`, `phone_verified`, `totp_enabled`, `is_active` | các cờ độc lập, không phải 1 "trạng thái tài khoản" duy nhất |

### 2.3. Liên kết logic — bổ sung 1 ràng buộc quan trọng thường bị quên khi tay tạo dữ liệu

Ngoài các liên kết bạn nêu (nhân viên đúng công ty, công trình đúng công ty...), có 1 ràng buộc DB **bắt buộc** phải tôn trọng khi build dữ liệu thủ công hoặc qua script: **1 nhân viên chỉ có tối đa 1 `assignment` với `status=active` tại CÙNG 1 site** — tạo assignment thứ 2 cùng site trong khi cái cũ còn active sẽ bị hệ thống từ chối (409), không phải lỗi script.

---

## 3. Dữ liệu mẫu cấp nền tảng hệ thống

### 3.1. Tài khoản quản trị và nhân viên nền tảng

| Yêu cầu gốc | Điều chỉnh theo hệ thống thật |
|---|---|
| 01 Super Admin / Platform Owner | `is_platform_admin=true` — hệ thống chỉ có **1 tài khoản admin nền tảng** (`admin@fams.com`), không có khái niệm nhiều "Platform Owner" riêng biệt với "Platform Admin" — đây là 1 role duy nhất |
| 02–03 Admin nền tảng | **[ĐIỀU CHỈNH]** Không có role "Admin nền tảng" tách biệt với "Super Admin" — chỉ có 1 role hệ thống `PLATFORM_ADMIN`. Muốn có nhiều người với quyền quản trị cao nhưng khác nhau một phần → dùng **role nền tảng tùy chỉnh** (đã có sẵn cơ chế `POST /roles` không gán `tenantId`) |
| >15 nhân viên nền tảng | Giữ nguyên — dùng role `PLATFORM_STAFF` (mặc định) + role tùy chỉnh |
| Một số chỉ quyền xem / vận hành / quản lý công ty-gói-người dùng | Map vào role tùy chỉnh theo permission resource: `tenants`+`users` (hỗ trợ khách hàng), `plans`+`subscriptions` (billing), `roles`+`permissions` (bảo mật/audit) — đã có sẵn 8 role tùy chỉnh mẫu covering đúng các nhóm này |
| Một số tài khoản bị khóa/ngừng hoạt động | Dùng `users.is_active=false` (vô hiệu hóa hẳn) — khác với `locked_until` (khóa tạm do đăng nhập sai, tự hết hạn) — **2 cơ chế riêng biệt, cần dữ liệu mẫu cho CẢ 2**, không gộp làm 1 |

### 3.2. Vai trò và quyền của nền tảng

Giữ nguyên toàn bộ yêu cầu gốc — hệ thống hỗ trợ đầy đủ: role có toàn quyền/chỉ xem/custom một phần, role đang dùng/chưa gán ai, role không xóa được do đang gán (409 nếu còn user giữ role — xác nhận qua code `RoleService`).

**[KHÔNG HỖ TRỢ]** "Vai trò bị thay đổi quyền trong khi đang được gán cho người dùng" — hệ thống **cho phép sửa quyền của role đang có người dùng** (không khóa), đây không phải case lỗi cần test riêng mà là hành vi bình thường — sau khi sửa, quyền áp dụng ngay cho mọi user đang giữ role đó (không cần re-assign). Nên đổi hướng test case này thành: "sửa quyền role đang gán → xác nhận user giữ role đó NGAY LẬP TỨC có/mất quyền tương ứng ở lần gọi API tiếp theo" (đã có trong kịch bản B.6 của `feature-test-guide.md`).

**[KHÔNG HỖ TRỢ]** "Hai vai trò có quyền chồng lặp" không phải là 1 trạng thái dữ liệu cần tạo riêng — bất kỳ 2 role tùy chỉnh nào có chung 1 permission đều tự động minh họa case này, không cần thiết kế đặc biệt.

### 3.3. Gói dịch vụ hệ thống

| Yêu cầu gốc | Điều chỉnh |
|---|---|
| Gói miễn phí / dùng thử / tiêu chuẩn / nâng cao / tùy chỉnh doanh nghiệp | Hệ thống có đúng **4 gói cố định**: `trial` (dùng thử, có hạn — KHÔNG phải "miễn phí vĩnh viễn"), `basic`, `pro`, `enterprise`. Không có khái niệm "gói tùy chỉnh riêng cho từng doanh nghiệp" — nhưng **platform admin tạo được gói MỚI bất kỳ lúc nào** qua `POST /plans`, nên có thể tạo thêm 1 gói kiểu "Enterprise Custom" nếu cần minh họa |
| Giới hạn số lượng người dùng / công trình / dung lượng / tính năng | Thật ra field `plan_limits` **CHỈ CÓ** `max_employees` và `max_sites` — **KHÔNG CÓ** giới hạn dung lượng lưu trữ hay giới hạn theo từng tính năng riêng lẻ (không có bảng "feature flags theo gói"). Đừng build dữ liệu cho giới hạn dung lượng/tính năng vì không có chỗ lưu và không có logic enforce |
| Gói bị tạm dừng | **[KHÔNG HỖ TRỢ]** Plan chỉ có `is_active` (đang bán/ngừng bán), không có trạng thái "tạm dừng" riêng cho plan. (Tenant thì CÓ trạng thái `suspended` — đừng nhầm 2 khái niệm) |
| Công ty vượt giới hạn của gói | Có — enforce thật qua `PlanLimitEnforcementService`, trả `422 PLAN_LIMIT_EXCEEDED` |
| Công ty sắp hết hạn / đã hết hạn nhưng còn dữ liệu | Có — dựa vào `tenant_subscriptions.expires_at`, dữ liệu (nhân viên/site/checkin...) **không tự xóa** khi hết hạn, chỉ đơn thuần không enforce cứng việc khóa tính năng ngay khi hết hạn (đây là điểm cần xác nhận lại với đội sản phẩm, ghi ở mục 20.2) |

### 3.4. Danh sách công ty sử dụng nền tảng

Giữ nguyên toàn bộ — 12-15 công ty, đa dạng plan/trạng thái/quy mô/thời điểm đăng ký. Bổ sung 1 điều kiện **BẮT BUỘC bạn đã nêu rõ và tôi xác nhận lại: mỗi tenant phải có `owner_id` là 1 user riêng biệt, KHÔNG dùng chung `admin@fams.com` làm chủ của nhiều tenant** — đây là lỗi đã xảy ra ở bộ dữ liệu trước (do chạy nhầm test script sau khi seed) và đã khắc phục; ghi lại thành nguyên tắc cứng để không lặp lại.

### 3.5. Người dùng hệ thống

Giữ phần lớn — riêng 2 case sau cần điều chỉnh:

- **[KHÔNG HỖ TRỢ]** "Email xác thực đã hết hạn" như 1 trạng thái riêng của user — thực tế token xác thực email (`email_verification_tokens`-kiểu) có TTL riêng và tự hết hạn, nhưng **user vẫn chỉ có 1 cờ `email_verified=false`**, không phân biệt được "chưa từng xác thực" với "token xác thực đã hết hạn" ở cấp dữ liệu user. Muốn minh họa case này → tạo user `email_verified=false` rồi mô tả bằng lời rằng "coi như token xác thực gửi lần đầu đã hết hạn", không cần cột riêng.
- **[KHÔNG HỖ TRỢ]** "Tài khoản chưa hoàn tất hồ sơ" — không có khái niệm % hoàn thiện hồ sơ hay cờ "hồ sơ chưa đầy đủ". Có thể mô phỏng bằng cách để trống các field optional (`avatarUrl`, `dateOfBirth`, `hometown`, `gender`, `address`) cho 1 vài user, nhưng hệ thống không tự nhận diện/cảnh báo case này.

Còn lại (đã xác thực đầy đủ / xác thực 1 phần / chưa xác thực gì / khóa do đăng nhập sai / bị Admin khóa / ngừng hoạt động) — **đều đã có sẵn trong bộ seed hiện tại** (`chuaxacthucmail1/2`, `chuaxacthucphone1/2`, `taikhoanbikhoa`, `kythuat3.nentang` bị vô hiệu hóa).

---

## 4. Dữ liệu mẫu cấp công ty — 3 công ty chuyên sâu

Giữ đúng cấu trúc 3 công ty đại diện, điều chỉnh tên gọi theo gói thật:

| Công ty mẫu gốc | Gói tương ứng thật | Tenant hiện có |
|---|---|---|
| Công ty 1 — gói miễn phí | **Đổi thành: gói `trial`, đang ở đúng giới hạn (5/5 nhân viên)** — "miễn phí" không tồn tại, trial là gói thấp nhất có enforce giới hạn rõ ràng nhất để test cảnh báo/khóa tính năng | `tia-sang-startup` (đã đúng mô tả) |
| Công ty 2 — gói cao cấp | `enterprise` — nhiều phòng ban/nhân viên/công trình, đầy đủ tính năng, nhiều role tùy chỉnh, chấm công dài ngày, vi phạm/phản hồi/thông báo/báo cáo | `gamma-logistics` (Enterprise, yearly — đã đúng, cần bổ sung thêm độ sâu theo mục 6 bên dưới) |
| Công ty 3 — mới tạo | Tenant hoàn toàn rỗng: chưa có site/shift/employee/Face ID/config | **[MỚI]** `rong-vang-holdings` — đã seed nhưng ở mức "rỗng tuyệt đối" đúng yêu cầu; **cần bổ sung**: hiện tenant này quá rỗng đến mức không minh họa được "các bước onboarding CHƯA hoàn thành" (vì không có gì để so sánh dở dang) — xem điều chỉnh ở mục 20 |
| Công ty 4 (tùy chọn) — bị khóa/hết hạn | `status=suspended` hoặc trial hết hạn | `dong-a-jsc` (SUSPENDED) + `nam-viet-services` (trial hết hạn 5 ngày) — đã có sẵn 2 case, thừa so với yêu cầu "tùy chọn 1 công ty" |

**[ĐIỀU CHỈNH QUAN TRỌNG]**: yêu cầu gốc chỉ định "≥3 công ty chuyên sâu" nhưng mục 6 dưới đây (nhân viên) yêu cầu 30-50 nhân viên/công ty chuyên sâu với ~10 nhóm nghiệp vụ khác nhau — **bộ dữ liệu hiện tại có 3 tenant chính (`acme-corp`, `beta-industries`, `gamma-logistics`) mỗi tenant đã có 30 nhân viên nhưng chỉ ~5 nhóm vị trí** (Nhân viên/Kỹ thuật viên/Chuyên viên/Tổ trưởng/Nhân viên vận hành dùng chung 1 pool tên gọi, KHÔNG phân biệt rõ theo 10 nhóm nghiệp vụ bạn liệt kê ở mục 6) — đây là điểm **CẦN LÀM LẠI**, không phải chỉ thêm số lượng.

---

## 5. Dữ liệu tổ chức và phòng ban (Workspace)

Giữ đúng yêu cầu 12-15 workspace/tenant chuyên sâu, đã có sẵn phần lớn case. Bổ sung/điều chỉnh:

- "Phòng ban có trưởng bộ phận / chưa có trưởng bộ phận": map vào `workspace_members.role='lead'` — cần đảm bảo **có ít nhất vài workspace CHƯA có ai giữ role `lead`** (hiện tại đa số workspace demo đều có 1 lead, cần bổ sung case thiếu lead).
- "Phòng ban con của phòng ban khác / cấu trúc nhiều cấp": đã có (3 cấp: Đội → Nhóm), nhưng **CHỈ 1 nhánh/tenant** có 3 cấp — cần mở rộng để có ít nhất 2-3 nhánh 3 cấp/tenant chuyên sâu để test tree UI thực sự có chiều sâu.
- "Phòng ban có nhân viên được điều chuyển": map vào API `POST .../workspaces/{id}/members/{memberId}/transfer` — cần có ít nhất 1-2 case **đã transfer trong lịch sử seed** (hiện tại seed chỉ gán 1 lần, chưa có ai từng bị chuyển) để minh họa dữ liệu "đã từng chuyển", không chỉ test API sống.
- "Phòng ban không thể xóa do đang có dữ liệu liên quan": **XÁC NHẬN CÓ THẬT** — `WorkspaceService` chặn xóa nếu còn `workspace_members` active (409 "Workspace still has active members"). Không cần dữ liệu riêng, dùng bất kỳ workspace đang có member để test.

---

## 6. Dữ liệu nhân viên công ty — ĐIỀU CHỈNH LỚN NHẤT

Đây là mục cần build lại nhiều nhất so với bộ hiện tại. Yêu cầu gốc muốn 30-50 nhân viên/công ty chuyên sâu, chia theo 10 nhóm nghiệp vụ. Vì `employees.position` là free-text và **KHÔNG có field "loại nhân viên" chuẩn hóa**, cách làm đúng là: dùng `position` làm nhãn nghiệp vụ nhất quán + gán `assignments.role` (`worker`/`supervisor`) + role RBAC tương ứng, để 3 tầng dữ liệu này khớp nhau logic, thay vì random như hiện tại.

### 6.1. 10 nhóm nghiệp vụ — map cụ thể vào field hệ thống

| Nhóm nghiệp vụ (yêu cầu gốc) | `position` gán | `assignments.role` | RBAC role gán kèm (nếu có login) |
|---|---|---|---|
| Admin công ty | "Giám đốc điều hành" / "Chủ doanh nghiệp" | — (thường không có assignment, làm văn phòng) | `TENANT_ADMIN` |
| Nhân viên nhân sự | "Chuyên viên Nhân sự" / "Trưởng phòng Nhân sự" | — | `HR_MANAGER` (ít nhất 1 người) |
| Trưởng phòng | "Trưởng phòng {Tên phòng ban}" | — | Role tùy chỉnh hoặc `HR_MANAGER` tùy phòng |
| Quản lý công trình | "Quản lý công trình" | `supervisor` | `SITE_SUPERVISOR` (site-scoped) |
| Trưởng bộ phận công trình | "Trưởng bộ phận thi công" | `supervisor` | — (không nhất thiết có login riêng) |
| Giám sát công trình | "Giám sát công trình" | `supervisor` | `SITE_SUPERVISOR` |
| Nhân viên công trình | "Công nhân" / "Kỹ thuật viên công trình" | `worker` | `EMPLOYEE` (1 số có login) |
| Nhân viên văn phòng | "Nhân viên {phòng ban văn phòng}" | — (không chấm công GPS site, hoặc gán site = văn phòng) | `EMPLOYEE` |
| Nhân viên thời vụ | **[KHÔNG HỖ TRỢ status riêng]** — chỉ mô phỏng qua `position="Nhân viên thời vụ"` + `assignments` có `endDate` ngắn hạn (vài tuần) | `worker` | — |
| Nhân viên thử việc | **[KHÔNG HỖ TRỢ status riêng]** — mô phỏng qua `position="Nhân viên thử việc"` + `hiredDate` gần đây (< 2 tháng) | `worker` | — |

### 6.2. Trạng thái nhân viên — điều chỉnh theo 3 status thật

Yêu cầu gốc liệt kê 10 trạng thái nhân viên (đang làm việc/thử việc/nghỉ phép/nghỉ không lương/tạm đình chỉ/nghỉ việc/chờ nhận việc/chưa kích hoạt/chưa xác thực/...). Đối chiếu:

| Trạng thái yêu cầu gốc | Cách thể hiện thật trong hệ thống |
|---|---|
| Đang làm việc | `status=active` |
| Đang thử việc | `status=active` + `position` chứa "thử việc" + `hiredDate` gần đây (KHÔNG có status riêng) |
| **Đang nghỉ phép / nghỉ không lương / nghỉ ốm** | **[KHÔNG HỖ TRỢ]** — hệ thống KHÔNG có entity/cột lưu trạng thái nghỉ phép nào cả (đã xác nhận qua audit: không có bảng `leaves`/`time_off`, không có field trên Employee/Assignment/Checkin). Không tạo dữ liệu giả cho case này. Nếu nghiệp vụ thực sự cần, đây là **tính năng CHƯA XÂY DỰNG**, cần báo sản phẩm bổ sung entity mới trước, không phải việc của seed data |
| **Tạm đình chỉ** | Gần nhất với `status=inactive` ("tạm ngừng") — nhưng đây là field CHUNG cho cả "tạm ngừng" lẫn ý nghĩa khác, hệ thống không phân biệt "tạm đình chỉ vì kỷ luật" với "tạm ngừng vì lý do khác" |
| Đã nghỉ việc | `status=terminated` |
| **Chờ nhận việc** | **[KHÔNG HỖ TRỢ trực tiếp]** — gần nhất là `employee_invitations.status=pending` (đã mời, chưa accept) VÀ/HOẶC employee đã tạo (`status=active`) nhưng `hiredDate` trong tương lai — cách thứ 2 khả thi hơn vì tạo được record `Employee` thật |
| Chưa được kích hoạt tài khoản / không có tài khoản đăng nhập | `employees.user_id IS NULL` — **đa số nhân viên demo hiện tại đã ở trạng thái này** (chỉ 15/tenant có login qua bảng lịch sử) |
| Đã có tài khoản nhưng chưa xác thực | `user_id` liên kết tới 1 user có `email_verified=false` — **CHƯA có case này trong seed hiện tại cho EMPLOYEE** (2 tài khoản `chuaxacthucmail1/2` hiện đang là user độc lập, không liên kết employee nào) — **CẦN BỔ SUNG**: liên kết 1-2 employee thật vào user chưa xác thực |

### 6.3. Trường hợp đặc biệt — đối chiếu

| Yêu cầu gốc | Trạng thái hiện tại |
|---|---|
| Một người thuộc 2 công ty, vai trò khác nhau | ✅ Có sẵn (2 người: Phạm Thị Dung, Trương Văn Đạt) |
| Nhân viên thuộc nhiều phòng ban | **[KHÔNG HỖ TRỢ]** — `employees.department_id` là **1-1** (1 nhân viên chỉ thuộc 1 phòng ban chính thức qua field này). `workspace_members` thì CÓ THỂ nhiều-nhiều (1 nhân viên là member của nhiều workspace) — 2 khái niệm khác nhau, cần làm rõ khi build: "phòng ban chính thức" luôn 1, "thành viên workspace" có thể nhiều |
| Nhân viên được phân công tại nhiều công trình | ✅ Khả thi (nhiều `assignments` khác site, không trùng thời gian active) — **CHƯA có case demo nào minh họa rõ điều này** (hiện mỗi nhân viên demo chỉ có 1 assignment) — cần bổ sung ≥2-3 nhân viên có 2 assignment ở 2 site khác nhau cùng lúc |
| Nhân viên được điều chuyển phòng ban | Xem mục 5 |
| Nhân viên có/chưa có quản lý trực tiếp | **[KHÔNG HỖ TRỢ]** — không có field `managerId`/`reportsTo` trên Employee. Gần nhất là `workspace_members.role='lead'/'manager'` của phòng ban họ thuộc về — không phải quan hệ 1-1 "quản lý trực tiếp" thật sự |
| Ngày bắt đầu và kết thúc làm việc | `hiredDate` có sẵn; **KHÔNG có `terminatedDate`/ngày nghỉ việc riêng** — khi `status=terminated`, hệ thống chỉ có `updatedAt` làm mốc gián tiếp |
| Mã nhân viên/email trùng để test validation | Không tạo sẵn trong seed (sẽ vi phạm UNIQUE constraint, script insert sẽ lỗi) — đây là case phải test THỦ CÔNG qua API tại thời điểm test, không thể "có sẵn trong DB" vì DB đã chặn trùng ở tầng constraint |

---

## 7. Dữ liệu lời mời tham gia công ty

| Yêu cầu gốc | Đối chiếu |
|---|---|
| Đã gửi / chưa mở / đã mở | **[KHÔNG HỖ TRỢ theo dõi "mở email"]** — chỉ có `pending` (đã gửi, chưa accept/hết hạn/hủy) |
| Đã chấp nhận / đã hết hạn / đã thu hồi | ✅ `accepted`/`expired`/`cancelled` — đã có đủ 4 trạng thái trong seed |
| **Đã từ chối** | **[KHÔNG HỖ TRỢ]** — không có action "từ chối lời mời" (chỉ có accept hoặc để hết hạn/bị hủy bởi HR) |
| Được gửi lại nhiều lần | **[KHÔNG HỖ TRỢ]** — không có API resend, và `email` là UNIQUE trong `employee_invitations` (không tạo được 2 bản ghi cùng email cùng tenant khi bản cũ còn pending) |
| Gửi đến email đã tồn tại (user khác) / người đã thuộc công ty / người thuộc công ty khác | Cần test qua API tại thời điểm test (kỳ vọng: lỗi 409 hoặc validation phù hợp) — không tạo sẵn được vì sẽ tự lỗi khi insert |
| Lời mời có vai trò mặc định / tùy chỉnh | Có field `roleId` trên invitation — hiện seed để `NULL` (dùng role mặc định) cho toàn bộ, **cần bổ sung 1-2 lời mời chỉ định `roleId` cụ thể** để test rõ |
| Lời mời chưa/đã gán sẵn phòng ban, công trình | **[KHÔNG HỖ TRỢ]** — `employee_invitations` KHÔNG có field `departmentId`/`siteId` — phân công phòng ban/công trình chỉ thực hiện SAU khi accept, tạo ra `Employee` thật rồi mới gán qua `workspace_members`/`assignments` |

---

## 8. Vai trò và quyền trong công ty

| Yêu cầu gốc | Đối chiếu |
|---|---|
| Chủ công ty / Admin công ty / Nhân sự / Quản lý công trình / Giám sát / Nhân viên | Hệ thống có đúng: `TENANT_ADMIN`, `HR_MANAGER`, `SITE_SUPERVISOR`, `EMPLOYEE` (4 role hệ thống cấp tenant — không có "Chủ công ty" và "Quản lý công trình" tách biệt, `TENANT_ADMIN` là role cao nhất, `SITE_SUPERVISOR` đã bao hàm nghĩa "quản lý công trình") |
| Người chỉ được xem báo cáo | **[TẠO ĐƯỢC qua role tùy chỉnh]** — permission `reports:*` dạng read-only, đã có tiền lệ ở role nền tảng `PLATFORM_QA_REVIEWER` |
| 3-5 vai trò tùy chỉnh/tenant | Hiện mỗi tenant chuyên sâu chỉ có **1** role tùy chỉnh — **CẦN BỔ SUNG lên 3-5/tenant** như yêu cầu gốc |
| Vai trò chỉ truy cập 1 số công trình | ✅ Có — cơ chế `user_role_sites` (site-scoped role), đã demo 2 case |
| **Vai trò chỉ quản lý nhân viên thuộc phòng ban của mình** | **[KHÔNG HỖ TRỢ]** — RBAC hiện tại chỉ scope theo **site** (`user_role_sites`), KHÔNG có cơ chế scope theo **workspace/phòng ban**. Đây là gap thật sự, không phải thiếu dữ liệu — nếu cần, phải là yêu cầu tính năng mới cho backend, không giải quyết được bằng seed data |
| Vai trò xem chấm công không sửa / duyệt chấm công không cấu hình hệ thống | Tạo được qua tổ hợp permission (`checkins:list` không kèm `checkins:manage`, hoặc kèm quyền `attendance:adjust` không kèm `sites:*`) — cần bổ sung role tùy chỉnh minh họa đúng 2 tổ hợp này |
| Vai trò không xem thông tin nhạy cảm | Không có khái niệm "field nhạy cảm" ở mức permission (permission chỉ theo resource như `employees`, không xuống tới field-level) — không tạo được đúng nghĩa, gần nhất là role không có `face_id:*`/`checkins:*` |

---

## 9. Dữ liệu công trình (Site)

**[ĐIỀU CHỈNH QUAN TRỌNG NHẤT MỤC NÀY]**: yêu cầu gốc mô tả Site như 1 "dự án xây dựng có tiến độ" (chưa bắt đầu/đang hoạt động/tạm dừng/sắp hoàn thành/đã hoàn thành/quá hạn tiến độ, có ngày bắt đầu/kết thúc dự kiến/thực tế). **Thực tế Site trong hệ thống là 1 ĐỊA ĐIỂM CHẤM CÔNG CỐ ĐỊNH** (nhà máy, văn phòng, kho, công trường...), KHÔNG phải 1 dự án có vòng đời tiến độ:

- `sites` **CHỈ CÓ** `status ∈ {active, inactive}` — không có "chưa bắt đầu/tạm dừng/sắp hoàn thành/đã hoàn thành/hủy/quá hạn".
- `sites` **KHÔNG CÓ** `startDate`/`plannedEndDate`/`actualEndDate` — chỉ có `createdAt`.
- `sites` **KHÔNG CÓ** field "người quản lý công trình" trực tiếp — quản lý được thể hiện gián tiếp qua `assignments.role=supervisor` hoặc `user_role_sites` (site-scoped SITE_SUPERVISOR).
- "Phòng ban phụ trách" — **[KHÔNG HỖ TRỢ]** không có liên kết Site↔Workspace.

→ **Nếu nghiệp vụ thực tế của công ty bạn cần Site có vòng đời tiến độ dự án** (bắt đầu/kết thúc/hoàn thành), đây là gap tính năng thật, cần làm việc với đội sản phẩm để thêm field, KHÔNG thể mô phỏng bằng dữ liệu mẫu trên schema hiện tại. Trong lúc chờ, seed chỉ nên build đúng theo field thật đang có: `active`/`inactive`, đa dạng địa lý (đã có), có/không có geofence, có/không có shift, có/không có assignment.

---

## 10. Dữ liệu vùng làm việc (Geofence)

| Yêu cầu gốc | Đối chiếu |
|---|---|
| 1 công trình có 1 / nhiều vùng làm việc | **[ĐIỀU CHỈNH]** Thực tế **1 site chỉ có 1 geofence `active` tại 1 thời điểm** (không phải nhiều vùng cùng active song song) — "nhiều vùng làm việc" chỉ đúng theo nghĩa LỊCH SỬ (1 active + nhiều bản `superseded` cũ), không phải nhiều khu vực áp dụng đồng thời |
| Dạng bán kính / đa giác | Đã đính chính ở bảng đối chiếu thuật ngữ đầu tài liệu: **luôn là đa giác** (`coordinates` là mảng điểm), `bufferMeters` chỉ là dung sai cộng thêm quanh biên, không phải 1 "chế độ bán kính" riêng |
| Các vùng có phạm vi giao nhau | Không áp dụng do mỗi site chỉ 1 geofence active — không có 2 geofence của 2 site khác nhau "giao nhau" có ý nghĩa nghiệp vụ gì đặc biệt cần test |
| Vùng chưa cấu hình tọa độ đầy đủ | Không tạo được — `POST /geofences` validate bắt buộc ≥4 điểm khép kín ngay từ đầu, không lưu được geofence thiếu tọa độ |
| Bán kính rất nhỏ / rất lớn | Đổi thành: **đa giác rất nhỏ (buffer ~20-30m, phù hợp văn phòng nhỏ) / rất lớn (buffer ~200-500m, phù hợp công trường/kho rộng)** — đã có biến thiên nhẹ trong seed hiện tại (50-80m), **cần bổ sung thêm 2 thái cực rõ rệt hơn** |
| Nhân viên được phân công tại nhiều vùng làm việc | = nhân viên có assignment ở nhiều site (mỗi site 1 geofence riêng) — xem mục 6.3 |

---

## 11. Dữ liệu ca làm việc (Shift)

| Yêu cầu gốc | Đối chiếu |
|---|---|
| Ca hành chính/sáng/chiều/đêm/qua ngày/cuối tuần | ✅ Có sẵn phần lớn qua `startTime`/`endTime`/`allowOvernight`. **[KHÔNG HỖ TRỢ]** "Ca cuối tuần" như 1 thuộc tính riêng — Shift không có field ngày-trong-tuần áp dụng (không giới hạn thứ mấy dùng ca nào), việc "ca nào chạy ngày nào" nằm ở `assignments.days_of_week` (bitmask), không phải ở Shift |
| Ca linh hoạt | **[KHÔNG HỖ TRỢ]** không có khái niệm "giờ linh hoạt" (giờ vào/ra co giãn theo nhân viên tự chọn trong khung) — Shift luôn có giờ bắt đầu/kết thúc cố định, chỉ có dung sai `earlyCheckinMinutes`/`lateCheckoutMinutes` |
| Ca có thời gian nghỉ giữa ca / nhiều khoảng nghỉ / không có nghỉ | **[KHÔNG HỖ TRỢ]** Shift không có field break-time — không tính giờ nghỉ giữa ca vào công thức `work_minutes` |
| Ca cho phép đi muộn/về sớm trong giới hạn | ✅ = `earlyCheckinMinutes`/`lateCheckoutMinutes` — đã có, đã đa dạng hóa (thêm case "Ca ngắn 6h" dung sai lệch hẳn 45p/5p) |
| Ca có tính tăng ca | ✅ = `allowOvertime` |
| Ca không yêu cầu chấm công | **[KHÔNG HỖ TRỢ]** mọi Shift đều gắn với việc chấm công qua Assignment — không có "ca không chấm công" |
| Ca tạm ngừng sử dụng | ✅ `status=inactive` — đã có 3 case |
| Hai ca trùng giờ (cùng site) | Hệ thống **KHÔNG chặn** tạo 2 shift trùng giờ tại cùng site (không phải lỗi cần test — là hành vi cho phép, vì thực tế 1 site có thể có nhiều ca chạy song song cho các nhóm nhân viên khác nhau) |
| Ca bắt đầu trước 00:00, kết thúc hôm sau | ✅ = `allowOvernight=true`, đã có nhiều case (Ca đêm 22h-6h/23h-7h) |
| Ca được sửa sau khi đã có chấm công | Cần test qua API tại thời điểm test: sửa giờ 1 shift đã có `checkins` liên kết → xác nhận dữ liệu chấm công CŨ giữ nguyên giờ cũ (không bị tính lại theo giờ mới) — đúng theo cơ chế snapshot đã audit ở `random-check-config-review.md` |
| Ca đang dùng nên không xóa được | ✅ Xác nhận có (`DELETE .../shifts/{id}` chặn nếu còn assignment tham chiếu) |
| Nhân viên nhiều ca cùng ngày / đổi ca | Nhân viên có 2 assignment ở 2 shift khác nhau **cùng site** sẽ bị chặn (ràng buộc 1 active assignment/site) — muốn "nhiều ca cùng ngày" phải ở **2 site khác nhau**, hoặc đổi ca = cancel assignment cũ + tạo assignment mới với shift khác |

---

## 12. Dữ liệu phân công nhân viên (Assignment)

Giữ phần lớn yêu cầu gốc — đã audit khớp. Điều chỉnh 2 điểm:

- "Phân công theo phòng ban / theo nhóm hàng loạt": **[KHÔNG HỖ TRỢ bulk theo phòng ban ở tầng API]** — `POST /assignments` chỉ nhận 1 `employeeId`/lần gọi, không có endpoint "phân công cả phòng ban cùng lúc". Muốn có nhiều assignment cho 1 phòng ban → gọi API nhiều lần (script làm việc này giúp, nhưng đó là hành vi CLIENT lặp lại, không phải tính năng bulk của backend).
- "Phân công ngoài thời gian làm việc của nhân viên": **[KHÔNG RÕ NGHĨA trong hệ thống này]** — Employee không có field "thời gian làm việc cho phép" độc lập với Assignment/Shift, nên không có khái niệm "ngoài giờ làm việc của nhân viên" tách biệt khỏi "ngoài ca". Bỏ case này hoặc hiểu lại thành "check-in ngoài khung giờ ca" (đã có ở mục A.7 #71/73 của `feature-test-guide.md`).

---

## 13. Dữ liệu đăng ký Face ID

| Yêu cầu gốc | Đối chiếu |
|---|---|
| Đăng ký thành công / chưa đăng ký / đang chờ xử lý | ✅ = `status=enrolled`/`not_enrolled` + `review_status=pending` |
| Đăng ký thất bại / ảnh không đạt chất lượng / không nhận diện được / nhiều khuôn mặt trong ảnh | Gộp chung vào `review_status=rejected` + `rejection_reason` (text tự do) — hệ thống **KHÔNG phân loại lý do từ chối thành enum riêng** (không có field `rejectionCode`), tất cả lưu dạng câu chữ HR tự nhập khi từ chối. Seed hiện có 1 lý do mẫu ("Ảnh mờ...") — **nên đa dạng câu chữ lý do hơn** để trông thực tế (nhiều khuôn mặt / không nhận diện được / độ phân giải thấp...) thay vì lặp lại 1 câu |
| Dữ liệu cần đăng ký lại | = case `status=enrolled` (đã có mặt cũ) + `review_status=pending` (đang nộp lại) — ✅ đã có (bucket 3 trong seed) |
| Face ID bị vô hiệu hóa | ✅ `status=revoked` |
| **Face ID đã hết hiệu lực (nếu hệ thống có cơ chế này)** | **[KHÔNG HỖ TRỢ]** — không có TTL/ngày hết hạn tự động cho Face ID đã enrolled. Chỉ có job dọn **ẢNH** cũ theo ngày (`biometric-photo-days`, mặc định 30 ngày, xem `docs/api/random-check-config-review.md` mục 13.4) — đây là dọn file ảnh vật lý, KHÔNG làm `face_profiles.status` chuyển sang hết hạn |
| Nhiều ảnh mẫu / 1 ảnh mẫu | **[KHÔNG HỖ TRỢ track số lượng]** — `face_profiles` chỉ lưu 1 `embedding` cuối cùng (vector tổng hợp), không lưu riêng từng ảnh đã dùng để enroll (ảnh gốc lưu ở `fams-ai`, số lượng ảnh dùng để enroll là tham số lúc gọi API, không phải thuộc tính lưu trữ lâu dài) |

---

## 14. Dữ liệu chấm công

### 14.1–14.3 (hợp lệ / đi muộn-về sớm / thiếu dữ liệu)

Phần lớn khớp với hệ thống thật, đã có trong seed. Điều chỉnh:

- "Có lý do được chấp nhận / bị từ chối" (cho đi muộn/về sớm): **[KHÔNG HỖ TRỢ workflow riêng]** — không có bảng "giải trình đi muộn" tách biệt. Cơ chế giải trình DUY NHẤT trong hệ thống là `checkins.employee_note`/`employee_photo_url` cho case `pending_review` (chấm công ngoài geofence), KHÔNG áp dụng cho đi muộn/về sớm đơn thuần (đi muộn không cần giải trình, hệ thống chỉ tính số phút muộn).
- "Chấm công trùng nhiều lần" / "check-out trước check-in": hệ thống **CHẶN Ở TẦNG VALIDATION** (không tạo ra được record kiểu này qua API) — nên hiểu đây là case **PHẢI TEST BẰNG CÁCH GỌI API SAI**, không phải dữ liệu có sẵn trong DB.
- "Dữ liệu chấm công bị xóa/hủy": **[KHÔNG HỖ TRỢ]** không có API hủy 1 checkin đã tạo (chỉ có `deletedAt` ở tầng DB dùng cho soft-delete nội bộ, không có endpoint public để HR "hủy chấm công").

### 14.4. Chấm công không hợp lệ

Khớp phần lớn — đã có `pending_review` (ngoài geofence), face/liveness fail (qua violations). Điều chỉnh:

- "Chấm công tại sai công trình": tương đương "check-in tại site KHÔNG nằm trong assignment của nhân viên" → hệ thống chặn ở tầng validate site được phép (`available-sites`), không tạo ra được record sai site qua API bình thường.
- "Chấm công bằng thiết bị không hợp lệ": **[KHÔNG HỖ TRỢ]** không có device-fingerprint/whitelist thiết bị cho chấm công (khác với `user_devices` — đó là để nhận PUSH, không liên quan xác thực chấm công).
- "Chấm công khi tài khoản bị khóa" / "khi nhân viên đã nghỉ việc": test bằng cách login 1 tài khoản đã `locked_until` còn hiệu lực, hoặc `employees.status=terminated` rồi thử gọi checkin → kỳ vọng bị chặn, **không tạo sẵn record chấm công thành công cho case này** (vì đúng ra phải bị chặn).

### 14.5. Nghỉ phép và vắng mặt

**[TOÀN BỘ MỤC NÀY KHÔNG HỖ TRỢ]** — đã nêu ở mục 6.2: hệ thống hoàn toàn chưa có entity nghỉ phép/vắng mặt/ngày lễ. Không build dữ liệu giả cho mục này. "Vắng mặt không phép" duy nhất suy ra được gián tiếp = có `assignment` active cho 1 ngày nhưng KHÔNG có `checkins` nào ngày đó — đây là cách DUY NHẤT hệ thống hiện tại "biết" 1 người vắng, không phải 1 trạng thái được ghi nhận chủ động.

### 14.6. Tăng ca

| Yêu cầu gốc | Đối chiếu |
|---|---|
| Tăng ca trước ca / sau ca | Hệ thống chỉ tính OT là **thời gian làm THÊM SAU giờ kết thúc ca** (`work_minutes` vượt quá thời lượng ca chuẩn) — **[KHÔNG HỖ TRỢ]** "tăng ca trước ca" (đến sớm không được tính là OT, chỉ có `earlyCheckinMinutes` là dung sai cho phép vào sớm, không sinh ra giờ OT) |
| Tăng ca ngày thường/cuối tuần/ngày lễ — mức tính khác nhau | **[KHÔNG HỖ TRỢ phân biệt]** — công thức tính OT hiện tại KHÔNG phân biệt ngày trong tuần hay ngày lễ, chỉ đơn thuần: giờ làm vượt ca, cap theo `lateCheckoutMinutes`, có bật `allowOvertime` hay không |
| **Tăng ca đã/chưa được phê duyệt / bị từ chối** | **[KHÔNG HỖ TRỢ — GAP LỚN NHẤT của mục này]** — OT được **TỰ ĐỘNG TÍNH TOÁN**, hoàn toàn KHÔNG có workflow phê duyệt (không có trạng thái `otMinutes` "pending approval"). Đây khác hẳn với mô tả yêu cầu gốc coi tăng ca như 1 quy trình cần duyệt — thực tế hệ thống thiết kế OT là số liệu tính sẵn, HR chỉ có thể `adjust`/`unlock-and-recompute` toàn bộ `AttendanceSummary` (không phải duyệt riêng từng khoản OT) |
| Tăng ca vượt giới hạn cho phép | ✅ Có — cap bởi `lateCheckoutMinutes`, phần vượt quá KHÔNG được tính vào `otMinutes` (tự động cắt, không phải "vượt giới hạn" bị từ chối thủ công) |

---

## 15. Dữ liệu tổng kết công (AttendanceSummary)

| Yêu cầu gốc | Đối chiếu |
|---|---|
| Số ngày làm/nghỉ, đi muộn, về sớm, giờ làm, giờ OT, chấm công thiếu/không hợp lệ | ✅ Có đủ field tương ứng (`lateMinutes`, `earlyLeaveMinutes`, `otMinutes`, `missingCheckout`...) — riêng "số ngày nghỉ" không có vì không có entity nghỉ phép (mục 14.5) |
| Tổng số vi phạm | Không phải field trực tiếp trên `AttendanceSummary` — phải JOIN sang bảng `violations` theo `employeeId`+khoảng ngày, không có cột đếm sẵn |
| **Trạng thái xác nhận bảng công** (chưa tổng hợp/chờ nhân viên xác nhận/nhân viên đã xác nhận/yêu cầu điều chỉnh/quản lý duyệt/từ chối/khóa/mở khóa) | **[KHÔNG HỖ TRỢ phần lớn]** — `AttendanceSummary` KHÔNG có workflow xác nhận 2 chiều (nhân viên xác nhận → quản lý duyệt). Thực tế chỉ có: **tự động tổng hợp** (job đêm + tính lại real-time mỗi lần chấm công) và **HR điều chỉnh tay** qua `adjust`/`unlock-and-recompute`. Không có bước "nhân viên tự xác nhận bảng công của mình" ở đâu trong API đã audit. Đây là gap tính năng cần làm rõ với sản phẩm, không phải thiếu dữ liệu mẫu |
| Người xác nhận / thời gian xác nhận | Gần nhất là `adjustment_reason` (lý do HR điều chỉnh, dạng text) — không có audit đầy đủ "ai duyệt lúc nào" tách riêng cho attendance |

---

## 16. Dữ liệu Random Check

### 16.1. Cấu hình

| Yêu cầu gốc | Field thật |
|---|---|
| Bật/tắt | `random_check_configs.is_active` |
| Tần suất kiểm tra | `checksPerShift` (số lần/ca) + `minIntervalMinutes` (giãn cách tối thiểu giữa 2 lần) |
| Khoảng thời gian áp dụng | `allowedStartTime`/`allowedEndTime` |
| Đối tượng áp dụng | `applicableRoles` (mảng string: `worker`/`supervisor`) |
| Công trình áp dụng | Cấu hình theo site qua **override** (`site_id` khác NULL) — không phải 1 danh sách site chọn nhiều, mà là **1 config/1 site cụ thể**, fallback về tenant-default nếu site không có override riêng |
| **Vùng làm việc áp dụng** | **[KHÔNG HỖ TRỢ]** — cấu hình chỉ theo site, KHÔNG xuống tới từng geofence/vùng con (vì mỗi site chỉ có 1 geofence active, không có "nhiều vùng" để chọn) |
| Số lần kiểm tra tối đa/ngày | = `checksPerShift` (thực chất là "tối đa/ca", không phải "/ngày" — 1 ngày có thể nhiều ca) |
| Thời gian cho phép phản hồi | `responseWindowSeconds` |
| Hình thức xác thực | `checkMode` ∈ `location_only`/`location_face`/`location_face_liveness` |
| **Quy định xử lý khi không phản hồi** | **[KHÔNG CẤU HÌNH ĐƯỢC]** — hành vi cố định trong code: hết `responseWindowSeconds` → tự động `status=no_response` → tự sinh `violation` loại `no_response`. Không có field để tùy biến quy tắc này theo tenant |

### 16.2. Kết quả

Khớp gần như hoàn toàn với hệ thống thật, đã có đủ trong seed (pass/fail, face/liveness fail, ngoài vùng, hủy, hết hạn, giải trình qua `check_responses`+`violations.employee_note`, HR xác nhận/bác bỏ qua `resolution`).

---

## 17. Dữ liệu phản hồi và vi phạm (Violation)

| Yêu cầu gốc | Đối chiếu |
|---|---|
| Đi muộn / về sớm / vắng mặt như 1 LOẠI VI PHẠM | **[KHÔNG HỖ TRỢ]** — `violations.violation_type` **CHỈ CÓ 4 giá trị** (CHECK constraint): `no_response`, `location_fail`, `face_fail`, `liveness_fail` — đi muộn/về sớm/vắng mặt/sai công trình/không tuân thủ lịch KHÔNG được ghi nhận là `Violation`, chúng chỉ là các CHỈ SỐ trên `AttendanceSummary` (mục 15), hoàn toàn 2 cơ chế tách biệt trong hệ thống này |
| Mức độ vi phạm | **[KHÔNG HỖ TRỢ]** — không có field `severity`/mức độ, chỉ có loại + đã xử lý hay chưa |
| Bằng chứng/tệp đính kèm | = `employee_photo_url` (chỉ 1 ảnh, không phải nhiều tệp đính kèm đa dạng loại) |

---

## 18. Dữ liệu thông báo (Notification)

| Yêu cầu gốc | Đối chiếu |
|---|---|
| Nhóm thông báo (hệ thống/nền tảng→công ty/công ty→nhân viên/phân công/đổi ca/chấm công bất thường/vi phạm/random check/gói sắp hết hạn/yêu cầu xác thực) | Hệ thống dùng `eventType` dạng chuỗi tự do (không phải enum cố định) — đã có sẵn ~13 loại demo tương ứng phần lớn nhóm trên. **[KHÔNG HỖ TRỢ]**: "thông báo gói dịch vụ sắp hết hạn" và "yêu cầu xác thực tài khoản" **CHƯA có `eventType` nào tương ứng được kích hoạt thật trong code** (không tìm thấy trigger nào gửi loại này) — nếu seed data tạo notification với `eventType` như vậy, đó là **dữ liệu demo tĩnh, không phản ánh tính năng thật đang chạy** |
| Đã lên lịch / gửi thất bại | `notification_delivery_logs.status` có `FAILED`/`SUCCESS`, nhưng **`notifications` (bản ghi in-app) không có trạng thái "đã lên lịch"** — tạo là gửi ngay, không có hàng đợi lên lịch trước cho notification thường (khác với `scheduled_checks` là hàng đợi riêng của Random Check) |
| Gửi theo phòng ban / toàn công ty | **[KHÔNG HỖ TRỢ]** — `notifications.user_id` luôn là **1 người cụ thể**, không có cơ chế gửi broadcast theo `workspace`/toàn `tenant` ở tầng lưu trữ (mỗi người nhận là 1 row riêng — muốn "gửi cả phòng ban" phải lặp code tạo N row, không phải 1 khái niệm broadcast có sẵn) |
| Thông báo có đường dẫn / tệp đính kèm | = `metadata` (JSONB tự do, đã dùng cho `checkId`/`siteId`/`expiresAt` của Random Check) — không có field "attachment" chuẩn hóa riêng |

---

## 19. Dữ liệu cấu hình công ty

| Yêu cầu gốc | Field thật (`tenant_settings` + `tenants`) |
|---|---|
| Múi giờ, ngôn ngữ | `tenants.timezone`, `tenants.locale` |
| Định dạng ngày giờ | `tenant_settings.date_format`/`time_format` |
| Sai số vị trí cho phép | Không ở cấp tenant — nằm ở `geofences.buffer_meters` (theo TỪNG SITE, không phải 1 cấu hình chung toàn tenant) |
| Khoảng cho phép đi muộn/về sớm | Không ở cấp tenant — nằm ở `shifts.earlyCheckinMinutes`/`lateCheckoutMinutes` (theo TỪNG CA) |
| **Quy định tăng ca / Random Check / Face ID ở cấp tenant chung** | Random Check: đúng là có cấp tenant-default (`random_check_configs` với `site_id=NULL`). Tăng ca/Face ID: **KHÔNG có bảng cấu hình chung cấp tenant** — tăng ca cấu hình theo từng Shift, Face ID không có "bật/tắt theo tenant" (chỉ enable/disable qua từng check-mode của Random Check hoặc theo từng site cần Face ID hay không) |
| **Cơ chế duyệt bảng công / duyệt nghỉ phép** | **[KHÔNG HỖ TRỢ]** — xem mục 15 và 14.5 |
| Ngày bắt đầu kỳ công, ngày khóa bảng công | **[KHÔNG HỖ TRỢ]** — không có field kỳ lương/kỳ công tùy biến theo tenant, `AttendanceSummary` luôn tính theo NGÀY DƯƠNG LỊCH tự nhiên, tháng luôn là tháng dương lịch |
| Ngày nghỉ cuối tuần / danh sách ngày nghỉ lễ | **[KHÔNG HỖ TRỢ]** — không có bảng ngày lễ/cấu hình ngày nghỉ theo tenant. Việc tính "đi làm ngày nào" hoàn toàn phụ thuộc có `assignment` active hay không, không tự động loại trừ thứ 7/CN hay ngày lễ |
| Employee code prefix/format | Có sẵn (`employee_code_prefix`/`padding`/`seq`) — **yêu cầu gốc không nhắc tới nhưng hệ thống CÓ**, nên giữ trong seed (đã có: HL/BM/PN/TS/DA...) |

---

## 20. Các tình huống bổ sung

### 20.1. Phân quyền và cách ly dữ liệu — giữ nguyên, khớp hệ thống

Toàn bộ case (công ty A không xem công ty B, admin công ty không vào được trang quản trị nền tảng, site-scoped role, đa công ty đổi ngữ cảnh) đều **có thật và test được** — đã có đủ dữ liệu, xem `feature-test-guide.md` mục B.2.

### 20.2. Giới hạn gói dịch vụ — giữ nguyên phần lớn, làm rõ 1 điểm còn mơ hồ

"Công ty trong thời gian gia hạn" — **[KHÔNG HỖ TRỢ]** không có trạng thái "gia hạn" (grace period) riêng biệt với "đã hết hạn" — chỉ có `expires_at` đã qua hay chưa, nhị phân, không có vùng đệm.

"Công ty hết hạn gói" **liệu có thực sự bị khóa tính năng hay không** — đây là điểm mục 3.3 tôi đã nêu **CẦN XÁC NHẬN LẠI VỚI ĐỘI SẢN PHẨM**, không tự suy đoán khi build seed. Trước mắt build dữ liệu (tenant hết hạn) đúng theo field `expires_at`, còn hành vi enforce thực tế phải test sống qua API để biết chắc, không giả định trước.

### 20.3. Dữ liệu không hợp lệ (validation)

Giữ nguyên toàn bộ — nhưng như đã nói ở mục 6.3/14, **phần lớn case validation KHÔNG THỂ có sẵn trong DB** (vì UNIQUE/CHECK constraint chặn ngay lúc insert) — nhóm này **PHẢI test bằng cách tự gọi API sai lúc test**, không phải dữ liệu seed sẵn. Tài liệu bàn giao (mục 21) nên có 1 file riêng liệt kê **payload mẫu để tự tay gọi API test validation**, không cố nhét vào seed script.

### 20.4. Dữ liệu hiệu năng lớn

Giữ nguyên yêu cầu nhưng **tách hoàn toàn khỏi seed demo chính**, do 2 lý do kỹ thuật thật: (1) `scripts/seed.sh` hiện tạo tenant qua **API thật** (không phải insert SQL trực tiếp) để đảm bảo đi qua đúng validation/business logic — ở quy mô 100-500 tenant sẽ mất hàng giờ chạy qua HTTP; (2) hàng triệu bản ghi `checkins` cần insert thẳng SQL (như `seed_historical.sql` đang làm) chứ không thể qua API. → Đề xuất: 1 script `scripts/seed_perf.sql` HOÀN TOÀN RIÊNG, chỉ insert thẳng SQL hàng loạt, chạy trên 1 DB nhánh riêng dành cho test hiệu năng, KHÔNG chạy chung với `scripts/seed.sh`/`seed_historical.sql` (nếu chạy chung sẽ làm hỏng tính "sạch, dễ đọc" của bộ dữ liệu demo chức năng chính) — **đây là hạng mục riêng, cần xác nhận bạn có thực sự cần trong đợt này không trước khi triển khai**, vì effort khác hẳn nhóm việc ở mục 3-19.

### 20.5. Nhật ký hoạt động (Audit Log)

**[GAP ĐÃ BIẾT, NHẮC LẠI RÕ]** — đối chiếu với audit trước đó (`backend-feature-audit-2026-08-01.md` mục "Ghi audit cho hành động quan trọng" = Partial): audit log **HIỆN CHỈ THỰC SỰ GHI** cho sự kiện auth (login/logout/đổi mật khẩu...). **KHÔNG có audit thật** cho: tạo/sửa/khóa user, đổi role/quyền, tạo/sửa công trình, đổi phân công, sửa chấm công, duyệt bảng công (vốn cũng không tồn tại, mục 15), đổi cấu hình công ty, đổi gói dịch vụ. Seed data lịch sử hiện có ~10 dòng `audit_logs` MINH HỌA TĨNH cho các loại hành động này (để UI danh sách audit không trống hoàn toàn khi demo) — **cần nói rõ với người xem: đây là dữ liệu demo hiển thị minh họa, KHÔNG PHẢI bằng chứng hệ thống đang thực sự audit các hành động đó** — tránh hiểu nhầm tính năng đã hoàn chỉnh khi thực tế còn gap.

---

## 21. Danh mục bàn giao (điều chỉnh theo hiện trạng repo)

| # | Yêu cầu gốc | Đã có / vị trí |
|---|---|---|
| 1 | Script tạo dữ liệu mẫu | ✅ `scripts/seed.sh` + `scripts/seed_historical.sql` |
| 2 | Script xóa/khôi phục | ✅ Quy trình reseed: xóa volume Postgres/Redis + chạy lại `seed.sh` (đã dùng suốt phiên làm việc này) — **chưa có 1 script `.sh` đóng gói sẵn thao tác này**, hiện làm thủ công qua `docker compose down` + `docker volume rm` + `docker compose up` + `seed.sh`. **Nên đóng gói thành `scripts/reseed.sh`** nếu dùng thường xuyên |
| 3 | Danh sách tài khoản đăng nhập | ✅ `docs/testing/feature-test-guide.md` Phần A (bảng theo từng tính năng) |
| 4 | Mật khẩu chung | ✅ `Admin@1234` — ghi trong mọi tài liệu liên quan |
| 5 | Danh sách công ty mẫu + đặc điểm | ✅ `docs/testing/sample-data-requirements.md` mục 2.1 + `feature-test-guide.md` |
| 6 | Ma trận người dùng–công ty–vai trò–quyền | **CHƯA CÓ dạng bảng tổng hợp riêng** — hiện nằm rải rác trong `feature-test-guide.md`. **Cần bổ sung 1 bảng ma trận tổng hợp riêng** nếu muốn tra cứu nhanh dạng spreadsheet |
| 7 | Danh sách trường hợp nghiệp vụ đã tạo | ✅ Tài liệu này (mục 3-19, cột "Đối chiếu") |
| 8 | Quan hệ giữa các thực thể | ⚠️ Có rải rác qua các buổi audit trước, **chưa có 1 sơ đồ ERD tổng hợp** — có thể bổ sung nếu cần |
| 9-11 | Dữ liệu demo / kiểm thử ngoại lệ / hiệu năng | Demo: ✅ có. Ngoại lệ: 1 phần có sẵn (trạng thái biên), 1 phần phải tự gọi API sai lúc test (mục 20.3). Hiệu năng: **CHƯA LÀM**, xem mục 20.4 |
| 12 | Hướng dẫn tái tạo/reset | ✅ Ghi trong `docs/testing/sample-data-requirements.md` + `feature-test-guide.md` phần "Ghi chú vận hành" |

---

## 22. Tiêu chí nghiệm thu — điều chỉnh còn lại CHƯA đạt so với bản gốc

Đối chiếu 14 tiêu chí gốc, đánh dấu tiêu chí nào **ĐÃ ĐẠT** với bộ dữ liệu hiện có và tiêu chí nào **CHƯA ĐẠT** (do gap tính năng hoặc do bộ dữ liệu hiện tại còn hời hợt so với mức 30-50 nhân viên/10 nhóm nghiệp vụ ở mục 6):

| Tiêu chí | Trạng thái |
|---|---|
| Đủ dữ liệu cấp nền tảng + cấp công ty | ✅ Đạt |
| ≥12-15 công ty mẫu | ✅ Đạt (18) |
| ≥3 công ty chuyên sâu | ✅ Đạt (3 + có thêm case biên) |
| Thực thể chính đủ số lượng | ⚠️ **CHƯA ĐẠT ĐÚNG Ý** cho nhân viên (30/tenant nhưng chưa đúng 10 nhóm nghiệp vụ, xem mục 6) — cần build lại phần này |
| Đủ trạng thái nghiệp vụ | ⚠️ Đủ theo trạng thái THẬT của hệ thống (bảng mục 2.2), nhưng KHÔNG THỂ đủ theo danh sách gốc vì nhiều trạng thái không tồn tại trong schema (nghỉ phép, duyệt OT...) |
| Liên kết logic hợp lý | ✅ Đạt |
| Test được luồng nghiệp vụ đầu-cuối | ✅ Đạt cho các luồng CÓ THẬT (xem `feature-test-guide.md` Phần B) |
| Test được phân quyền/cách ly dữ liệu | ✅ Đạt |
| Có tài khoản cho từng vai trò chính | ✅ Đạt |
| Tài khoản đúng trạng thái thiết kế | ✅ Đạt |
| Dữ liệu chấm công/OT/vi phạm để đối chiếu báo cáo | ✅ Đạt (nghỉ phép: không đạt được vì không tồn tại) |
| Tái tạo/reset được | ✅ Đạt |
| Không chứa dữ liệu cá nhân thật | ✅ Đạt |
| Ổn định cho dev/QA/demo/nghiệm thu | ✅ Đạt sau khi sửa lỗi "1 chủ nhiều công ty" ở phiên trước |

---

## Tổng kết — việc cần làm tiếp theo (chờ bạn xác nhận trước khi triển khai)

1. **Build lại mục 6** (nhân viên 3 tenant chuyên sâu) theo đúng 10 nhóm nghiệp vụ + map `position`/`assignments.role`/RBAC nhất quán, thay vì pool tên gọi chung chung hiện tại.
2. **Bổ sung 3-5 role tùy chỉnh/tenant** (mục 8) thay vì 1 role/tenant hiện tại.
3. **Bổ sung workspace tree sâu hơn** (≥2-3 nhánh 3 cấp/tenant thay vì 1 nhánh).
4. **Bổ sung 2-3 nhân viên có 2 assignment ở 2 site khác nhau cùng lúc** (case hiện chưa có).
5. **Liên kết 1-2 employee thật vào tài khoản chưa xác thực email** (hiện case này chỉ là user độc lập, chưa gắn employee).
6. **Đa dạng câu chữ lý do từ chối Face ID** thay vì lặp lại 1 câu.
7. **Quyết định có làm mục 20.4 (dữ liệu hiệu năng lớn) trong đợt này hay tách riêng** — effort lớn, cần bạn xác nhận độ ưu tiên.
8. Các mục **[KHÔNG HỖ TRỢ]** liệt kê xuyên suốt tài liệu (nghỉ phép, duyệt OT, quản lý trực tiếp, RBAC theo phòng ban, Site có tiến độ dự án...) — đề nghị bạn xác nhận: bỏ qua hẳn (vì chưa phải nhu cầu thật), hay đây là các tính năng **CẦN BỔ SUNG VÀO BACKEND TRƯỚC** (không phải việc của seed data) để lần sau đưa vào lại bộ yêu cầu.
