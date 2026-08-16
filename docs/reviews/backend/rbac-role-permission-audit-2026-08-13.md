# Đánh giá RBAC: Role mặc định & danh mục Permission (2026-08-13)

Yêu cầu: xác nhận hệ thống role/permission có đúng thiết kế, đủ cho các tính năng hiện có, và
danh mục permission đã được xây dựng hợp lý theo tính năng thật hay chưa.

**Cập nhật 2026-08-14 — đã triển khai xong 3 việc phát sinh từ báo cáo này:**
1. **Clone role** — `POST /roles/{id}/clone` (backend) + nút "Sao chép" trên mọi dòng role ở
   `RoleManagementPage` (Web Admin) — giải quyết mục 4 (thiếu tiện ích clone).
2. **Gán role hàng loạt** — `POST /user-roles/bulk-assign` (backend, kèm `revokeRoleId` để vừa
   thu hồi role cũ vừa gán role mới trong 1 lần) + nút "Gán role hàng loạt" + modal chọn nhiều
   nhân viên (Web Admin) — giải quyết mục 4 (thiếu tiện ích gán hàng loạt).
3. **Lỗ hổng leo thang đặc quyền nghiêm trọng** (phát hiện khi user hỏi vì sao danh sách role
   phía tenant lộ cả `PLATFORM_ADMIN`/`PLATFORM_STAFF`): thêm cột `is_platform_role` (migration
   V91) phân biệt role hệ thống cấp nền tảng với cấp công ty. Trước khi sửa, **bất kỳ Company
   Admin nào có quyền `roles:update` có thể tự gán role `PLATFORM_ADMIN` (77 quyền) cho bất kỳ ai
   ngay trong tenant của mình** qua `POST /user-roles` — đã xác nhận khai thác được thật trước
   khi vá. Đã chặn ở 3 điểm: danh sách/chi tiết role (ẩn khỏi tenant), gán role (chặn 400), và
   clone role (chặn 403). Xem chi tiết mục 6.

## 1. Role hệ thống — đúng thiết kế, seed hợp lý

6 role hệ thống (`is_system=true`), khớp đúng vai trò nghiệp vụ mong muốn:

| Role | Vai trò | Số permission (seed) |
|---|---|---|
| `PLATFORM_ADMIN` | Admin nền tảng | 77 |
| `PLATFORM_STAFF` | Nhân viên nền tảng | 6 |
| `TENANT_ADMIN` | Admin công ty | 68 |
| `HR_MANAGER` | HR công ty | 52 |
| `SITE_SUPERVISOR` | Quản lý công trường | 24 |
| `EMPLOYEE` | Nhân viên hiện trường | 5 |

Thứ bậc số lượng quyền giảm dần đúng logic nghiệp vụ. Kết luận: **phần role đạt yêu cầu.**

## 2. Hai kiểu enforcement cùng tồn tại

- **Kiểu A (permission-based):** `@PreAuthorize("hasAuthority('x:y')")` hoặc kiểm tra thủ công
  `permissions.contains("x:y")` trong service. Company Admin tick permission nào trong custom
  role thì có đúng quyền đó. Áp dụng cho: `employees:*`, phần lớn `roles:*` (thao tác thật),
  `assignments:list`, `randomchecks:configure`, `workspace_members:list`, `reports:list`.
- **Kiểu B (hard-gate):** khóa theo tên role (`hasRole('PLATFORM_ADMIN')`), theo quyền sở hữu
  (owner-ID equality), hoặc mở cho bất kỳ ai đã đăng nhập (`isAuthenticated()`), **bỏ qua hoàn
  toàn** hệ thống permission dù bảng `permissions` có sẵn entry tương ứng.

## 3. Danh mục permission "chết" — 23 entries có trong DB nhưng không có nơi nào enforce

Phân loại theo nguyên nhân, kèm khuyến nghị xử lý:

### 3a. Lệch tên biến thể `:read`/`:list` (không phải gap bảo mật, chỉ là dư thừa danh mục)
Code chỉ wire 1 trong 2 biến thể, biến thể còn lại chưa từng được dùng:

| Permission chết | Biến thể đang thực sự dùng | Endpoint |
|---|---|---|
| `assignments:read` | `assignments:list` | `GET .../assignments` |
| `randomchecks:read` | `randomchecks:list`/`configure` | `GET /scheduled-checks/{id}` |
| `shifts:read` | `shifts:list` | (chỉ có list, không có "get 1 shift" riêng) |
| `workspace_members:read` | `workspace_members:list` | `GET /workspace-members` |
| `reports:read` | `reports:list` | `ReportController` (mọi endpoint report) |
| `roles:list` | — (không cần permission, mở cho mọi user đã đăng nhập) | `GET /api/v1/roles` |
| `randomchecks:create` | `randomchecks:configure` | `POST /scheduled-checks/manual` |

**Khuyến nghị:** xóa các permission trùng lặp này khỏi danh mục (giữ đúng 1 biến thể đang dùng
thật) để màn "Tạo role tùy chỉnh" không hiện lựa chọn vô nghĩa.

### 3b. Tính năng chưa xây dựng — permission mô tả 1 thao tác chưa có endpoint nào cả
| Permission chết | Ghi chú |
|---|---|
| `employees:delete` | Không có API xóa nhân viên (có #40 "Tạm ngừng/nghỉ việc" dùng cơ chế đổi trạng thái, không phải xóa cứng) |
| `geofences:delete` | Không có API xóa geofence |
| `users:read`/`users:update`/`users:delete` | Không có API nào ngoài `GET /users` (search — dùng permission khác, xem 3c) |
| `violations:create` | Vi phạm do hệ thống tự sinh (từ random check thất bại/hết hạn), không có API tạo tay |

**Khuyến nghị:** xóa khỏi danh mục nếu chắc chắn không làm các tính năng này; giữ lại (và note rõ
"reserved") nếu dự định làm sau.

### 3c. Mở công khai/isAuthenticated có chủ đích — permission trong DB chỉ là dư thừa vô hại
| Permission chết | Thực tế | Đánh giá |
|---|---|---|
| `notifications:list`/`read` | `isAuthenticated()`, lọc theo chính user đó trong service | Hợp lý — thông báo là của riêng user, không cần phân quyền thêm |
| `permissions:list`/`read` | `isAuthenticated()`, mở cho mọi người | Hợp lý — chỉ là danh mục permission để hiển thị UI, không nhạy cảm |
| `plans:list`/`read` | Hoàn toàn public, không cần đăng nhập | Hợp lý — giống trang bảng giá SaaS công khai |

**Khuyến nghị:** xóa khỏi danh mục "chọn khi tạo role" (không cần Company Admin cấp phát cái vốn
đã mở sẵn cho mọi người).

### 3d. Khóa cứng theo role — ranh giới bảo mật cố ý, không nên đổi
| Permission chết | Thực tế | Đánh giá |
|---|---|---|
| `users:list` | `hasRole('PLATFORM_ADMIN')` | Đúng — không nên cho phép cấp lẻ quyền xem danh bạ TOÀN NỀN TẢNG qua custom role |
| `plans:create`/`update` | `hasRole('PLATFORM_ADMIN')` | Đúng — quản lý gói dịch vụ là quyết định kinh doanh cấp cao |

**Khuyến nghị:** giữ nguyên cơ chế, chỉ xóa permission khỏi danh mục hiển thị để tránh Company
Admin tưởng nhầm có thể cấp được.

### 3e. ⚠️ Trường hợp đáng chú ý nhất — `tenants:update`
- Thực tế: `PATCH /tenants/{id}` **không có `@PreAuthorize` nào cả**, chỉ kiểm tra
  `userId == tenant.ownerId` ngay trong service.
- **Khác với mọi resource nhạy cảm khác trong hệ thống** (IP whitelist, cấu hình tenant settings):
  ở đây **Platform Admin không được miễn trừ** — nghĩa là nếu chủ tenant mất quyền truy cập tài
  khoản (quên mật khẩu kèm mất luôn email khôi phục, tài khoản bị vô hiệu hóa nhầm...), **kể cả
  Platform Admin/Support của FAMS cũng không sửa hồ sơ công ty giúp được** qua API này.
- Có 1 dòng Javadoc ghi "Owner-only — including for Platform Admin/Staff" — cho thấy đây **có vẻ
  là quyết định cố ý** (nhất quán với logic tương tự ở `TenantSettingsService`), nhưng khác hẳn
  cách IP whitelist/subscription xử lý (đều có nhánh miễn trừ Platform Admin).

**Cần bạn xác nhận:** đây có đúng là chủ đích ("tuyệt đối chỉ chủ sở hữu mới sửa được hồ sơ công
ty, kể cả FAMS hỗ trợ cũng không") hay nên bổ sung nhánh miễn trừ Platform Admin giống các chỗ
khác (dùng để hỗ trợ khách hàng khi cần)?

## 4. Quy trình đổi quyền cho 1 công ty cụ thể — thiếu 2 tiện ích thao tác

Câu hỏi thực tế: "Tôi là chủ công ty A, role `EMPLOYEE` đang có 5 quyền, tôi muốn tăng/giảm cho
nhân viên công ty mình — sửa trực tiếp được không?"

**Không được** — `EMPLOYEE` là role hệ thống dùng chung mọi tenant (`is_system=true`,
`tenant_id=null`), sửa trực tiếp sẽ ảnh hưởng mọi công ty khác, nên bị khóa cứng. Cách đúng: tạo
1 role tenant-scoped riêng (VD: "Nhân viên — Công ty A") với permission mong muốn, rồi gán lại
nhân viên công ty đó sang role mới.

Quy trình này hiện **thiếu 2 tiện ích** khiến nó cực hơn cần thiết khi công ty có nhiều nhân viên:

- **Không có "Clone/Duplicate role"** — xác nhận không có endpoint nào (`cloneRole`/
  `duplicateRole`/`copyRole` — đã grep toàn bộ `com.fams.modules.rbac`, không có kết quả). Tạo
  role mới phải tự tick lại tay đúng permission gốc, dễ tick sai/thiếu.
- **Không có gán role hàng loạt** — `UserRoleController` chỉ có `POST /user-roles` (gán 1 người)
  và `DELETE /user-roles/{id}` (thu hồi 1 người), không có endpoint chọn nhiều user rồi chuyển
  role cùng lúc. Công ty nhiều nhân viên phải làm từng người một.

Đối chiếu GitHub/GitLab: cả 2 đều có "Clone role" (bấm 1 nút, tick sẵn đúng permission gốc). Đây
là khoảng cách UX thật so với sản phẩm trưởng thành, không phải bug.

**✅ Đã triển khai (2026-08-14)** — cả backend lẫn Web Admin UI, xem mục 6-7.

## 5. Đã triển khai — Clone role & Gán role hàng loạt (2026-08-14)

### Clone role
- `POST /api/v1/roles/{id}/clone` — `{name, description?, tenantId?}`. Copy toàn bộ permission
  từ role nguồn vào role mới, độc lập, chỉnh sửa thoải mái không ảnh hưởng bản gốc.
- Điều kiện role nguồn được phép clone: là role hệ thống **cấp công ty** (không phải
  `PLATFORM_ADMIN`/`PLATFORM_STAFF`, xem mục 6), hoặc là role tùy chỉnh **thuộc đúng tenant đích**,
  hoặc caller là Platform Admin.
- Web Admin: nút "Sao chép" (icon copy) trên **mọi dòng** trong bảng role (`RoleManagementPage`),
  mở modal nhập tên/mô tả cho role mới, prefill từ role nguồn.
- Đã test thật qua API: clone `EMPLOYEE` (5 quyền) → role mới có đúng 5 quyền giống hệt.

### Gán role hàng loạt
- `POST /api/v1/user-roles/bulk-assign` — `{tenantId, roleId, revokeRoleId?, userIds[], siteIds?}`.
  Xử lý từng user độc lập (try/catch riêng) — 1 người lỗi (VD: đã có role, không tồn tại) không
  làm hỏng cả lô, trả về kết quả chi tiết từng người (`successCount`/`failureCount`/`results[]`).
  `revokeRoleId` cho phép vừa thu hồi role cũ vừa gán role mới trong 1 lần gọi — đúng kịch bản
  "chuyển mọi người từ EMPLOYEE sang role mới".
- Web Admin: nút "Gán role hàng loạt" ở đầu trang (chỉ hiện với quyền `roles:update`, scope
  tenant), modal chọn nhiều nhân viên (tìm theo tên, tái dùng `useEmployees`), chọn role gán +
  role thu hồi (tùy chọn), hiện bảng kết quả từng người sau khi submit.
- Đã test thật qua API: gán hàng loạt 4 user (3 hợp lệ + 1 ID giả) → 3 người thành công (đã thu
  hồi role cũ, có role mới, xác nhận trong DB), 1 người báo lỗi riêng, không ảnh hưởng 3 người kia.

## 6. Lỗ hổng leo thang đặc quyền — phát hiện và đã vá (2026-08-14)

User đặt câu hỏi: "Vì sao danh sách role phía tenant lại thấy cả role/permission của nền tảng
quản trị — phải ẩn đi chứ?" Khi điều tra, phát hiện đây **không chỉ là lỗi hiển thị mà là lỗ hổng
bảo mật thật**.

**Nguyên nhân gốc:** 6 role hệ thống chỉ có 1 cờ `is_system=true`, không phân biệt "hệ thống cấp
nền tảng" (`PLATFORM_ADMIN`, `PLATFORM_STAFF`) với "hệ thống cấp công ty" (`TENANT_ADMIN`,
`HR_MANAGER`, `SITE_SUPERVISOR`, `EMPLOYEE`) — cả 6 đều `tenant_id=NULL, is_system=true`, dùng
chung 1 nhóm logic.

**Hậu quả xác nhận khai thác được thật trước khi vá:** gọi `POST /user-roles` (API gán role tenant
Company Admin dùng hàng ngày) với `roleId` = `PLATFORM_ADMIN` và `tenantId` = tenant của chính họ
→ **thành công (HTTP 201)**. Bất kỳ ai có quyền `roles:update` trong 1 tenant tự cấp được toàn bộ
77 quyền `PLATFORM_ADMIN` cho bất kỳ ai, ngay trong tenant của mình — không cần là Platform Admin
thật.

**Đã sửa:**
- Migration V91: thêm cột `roles.is_platform_role`, gán `true` cho đúng `PLATFORM_ADMIN`/
  `PLATFORM_STAFF`.
- Danh sách/chi tiết role (`RoleSpecification`, `RoleService.getRoleById`): ẩn 2 role này khỏi
  mọi caller không phải Platform Admin.
- Gán role (`UserRoleService.assignRole`): chặn gán 2 role này qua API tenant-scoped, bắt buộc đi
  qua `/user-roles/platform` (Platform Admin only).
- Clone role (mục 5, `RoleService.cloneRole`): cũng chặn clone 2 role này vào tenant — tính năng
  Clone vừa xây dựng cùng ngày suýt tái mở lại đúng lỗ hổng này qua đường khác.
- **Đã kiểm chứng lại bằng token chủ tenant thật (không phải Platform Admin)** sau khi vá: danh
  sách role không còn thấy `PLATFORM_ADMIN`/`PLATFORM_STAFF`; gán role bị chặn 400; clone role bị
  chặn 403; đối chiếu clone `EMPLOYEE` (hợp lệ) vẫn hoạt động bình thường (201) — không chặn nhầm.
- Đã dọn dữ liệu rác phát sinh lúc test khai thác (1 role test có đủ 77 quyền PLATFORM_ADMIN đã
  lỡ tạo trong lúc tái hiện lỗ hổng, đã xóa).

## 8. Chuyển quyền chủ sở hữu công ty — mới thêm (2026-08-14)

Phát sinh từ ca thực tế: `hong.ly@phuongnam.vn` giữ role `TENANT_ADMIN` của "Công ty CP Logistics
Phương Nam" nhưng **không thấy** "Cấu hình công ty" — vì owner thật của tenant đó là
`quang.phuongnam@gmail.com`, và trước đây **không có cách nào** để công ty tự chuyển quyền owner
sau khi tạo (xác nhận grep toàn bộ code, không có endpoint nào).

**Đã thêm:**
- Backend: `POST /tenants/{id}/transfer-owner` — `{newOwnerUserId | newOwnerEmail}`. Chỉ owner
  hiện tại (hoặc Platform Admin) gọi được; người nhận **phải đã là thành viên đang hoạt động của
  tenant đó** (có role gì đó rồi), không chuyển được cho người lạ hoàn toàn ngoài công ty; tự động
  gán `TENANT_ADMIN` cho chủ mới nếu họ chưa có; owner cũ không bị thu hồi role đang giữ.
- Frontend: banner "Bạn là chủ sở hữu công ty" (đã có sẵn) giờ kèm nút "Chuyển quyền chủ sở hữu"
  → modal tìm nhân viên đang có trong công ty, xác nhận có cảnh báo rõ hậu quả trước khi chuyển.
- Đã test đầy đủ cả API lẫn UI thật (Playwright): chuyển cho người ngoài tenant bị chặn (400);
  chuyển cho đúng thành viên thành công (200); chủ cũ mất quyền sửa hồ sơ **ngay lập tức** (403);
  chủ mới sửa được ngay; UI: chủ cũ thấy nút, chuyển xong đăng nhập lại thấy đã mất nút, chủ mới
  đăng nhập thấy đúng banner + nút.

**Phát hiện thêm 1 bug thật trong lúc test UI** (không liên quan trực tiếp yêu cầu, nhưng chặn
đúng tính năng vừa làm): route `/customer/settings/tenant` bọc `RoleGuard
allowedRoles=[TENANT_ADMIN]`, so khớp theo **1 role "chính" duy nhất trong JWT** — nếu 1 người giữ
từ 2 role trở lên trong cùng tenant (chính là trường hợp chủ mới vừa được tự động gán thêm
`TENANT_ADMIN` cạnh role cũ), JWT có thể chọn role không phải `TENANT_ADMIN` làm "role chính",
khiến **chủ sở hữu thật bị chặn ngay từ vòng ngoài** dù backend cho phép. Đã sửa: bỏ hẳn
`RoleGuard` khỏi route này — quyền thật của trang là "owner", không phải role, và
`TenantConfigurationPage` đã tự kiểm tra owner chính xác theo dữ liệu backend (có màn 403 riêng).

## 9. Số lượng người giữ role bị lộ chéo tenant, và tính năng "Thành viên công ty" mới (2026-08-14)

Phát sinh từ ca thực tế người dùng báo cáo: công ty "Kiến Trúc Himai" chỉ có 2 người
(`duyanh19102005@gmail.com`, `kingofcodm3142@gmail.com`) giữ role `TENANT_ADMIN`, nhưng số
"Người đang giữ" hiển thị trên danh sách role lại không khớp.

**Nguyên nhân gốc — lỗ hổng lộ dữ liệu chéo tenant, không phải lỗi hiển thị:** `TENANT_ADMIN` và
các role hệ thống khác (`HR_MANAGER`, `SITE_SUPERVISOR`, `EMPLOYEE`) là **1 hàng role dùng chung
cho mọi tenant** (`tenant_id=NULL`). `UserRoleRepository.countActiveByRoleIdIn` đếm theo
`role.id` **không lọc theo `tenant_id`** của assignment — nghĩa là một Company Admin xem role
`TENANT_ADMIN` của công ty mình lại thấy **tổng số người giữ `TENANT_ADMIN` trên TOÀN BỘ nền
tảng**, không phải riêng công ty họ. Với role tenant-owned (custom role) thì không bị ảnh hưởng vì
`role.id` vốn đã ứng với đúng 1 tenant.

**Đã sửa:** thêm `UserRoleRepository.countActiveByRoleIdInAndTenantId` (lọc thêm `tenant_id`),
`RoleService.batchLoadAssignmentCounts` nhận thêm tham số `tenantId` và dùng query mới khi danh
sách đang lọc theo 1 tenant cụ thể. Đã kiểm chứng qua API: Himai giờ hiển thị đúng
`TENANT_ADMIN → 2 người`. *Ghi chú:* `getRoleById`/`updateRole` (chi tiết 1 role, không phải danh
sách) vẫn dùng đường đếm cũ chưa lọc tenant — chưa sửa vì hiện không có màn nào ở frontend render
số này, độ ưu tiên thấp, nên theo dõi nếu sau này có màn chi tiết role hiển thị con số đó.

**Tính năng mới — "Thành viên công ty" (Company Members):** cùng lúc, người dùng phản ánh màn
"Nhân viên" (Employee) hiện tại chỉ là hồ sơ HR (phòng ban/chức vụ/mã nhân viên) — không có màn
nào gộp chung **toàn bộ người thuộc công ty kèm vai trò**, bao gồm cả người có quyền truy cập
nhưng chưa từng được tạo hồ sơ HR (VD: chủ mới nhận qua "Chuyển quyền chủ sở hữu", mục 8, không tự
động có hồ sơ Employee). Đã xây mới:

- Backend: `GET /tenants/{id}/members` (`TenantMemberService`) — gộp `user_roles` (ai có quyền
  gì) với `employees` (hồ sơ HR, best-effort, không bắt buộc) theo từng người, sắp xếp chủ sở hữu
  lên đầu rồi theo tên. Quyền xem: chủ sở hữu tenant, Platform Admin, hoặc bất kỳ ai giữ
  `roles:read`/`roles:update`/`employees:list` trong tenant đó.
- Frontend: tab/trang riêng "Thành viên công ty" (`/customer/settings/members`,
  `TenantMembersPage.tsx`) — không dùng `RoleGuard` ở route (cùng lý do đã ghi ở mục 8: quyền thật
  là "owner HOẶC permission", `RoleGuard` chỉ biết permission/role JWT, sẽ chặn nhầm chủ sở hữu
  không giữ 3 permission trên); thay vào đó component tự nhận diện lỗi 403 thật từ backend và hiện
  màn 403 riêng. Bảng hiển thị: tên/liên hệ + tag "Chủ sở hữu", danh sách role (tag, click để thu
  hồi nếu có quyền `roles:update` và không phải chủ sở hữu), hồ sơ HR (chức vụ/phòng ban hoặc
  "Chưa có hồ sơ HR"), ngày tham gia. Tìm kiếm không dấu tiếng Việt tái dùng
  `matchesVietnameseSearch` (đã có từ trước, mục "search company").
- **Bug phát hiện và sửa ngay trong lúc test UI thật:** nút thu hồi role gọi đúng API
  (`DELETE /user-roles/{id}`, xác nhận backend xóa thành công qua DB) nhưng bảng không tự cập nhật
  — do thiếu `queryClient.invalidateQueries(["tenant-members"])` sau khi revoke thành công. Đã
  thêm invalidation, kiểm chứng lại bằng Playwright: tạo tenant + 2 người test qua API, thu hồi
  role qua UI thật → người đó biến mất khỏi bảng ngay lập tức, đúng như kỳ vọng.
- Đã dọn toàn bộ dữ liệu test (tenant, user, user_roles, audit_logs synthetic) sau khi xác minh.

## 10. ⚠️ Tự khóa vĩnh viễn khi thu hồi role admin cuối cùng — xác nhận qua test tự động (2026-08-15)

Trong đợt chạy tự động lại kịch bản test #24-31 (Playwright, trên 1 tenant thật mới tạo riêng cho
test), case 4/5 của #30 (Thu hồi role) xác nhận một gap đã nêu từ audit gốc (07-22:
"không có safeguard mất admin cuối") là **có thật, và hậu quả nặng hơn dự đoán ban đầu**:

- Thu hồi role `TENANT_ADMIN` cuối cùng của 1 tenant (kể cả tự thu hồi role của chính chủ sở hữu)
  **thành công không cảnh báo** — `DELETE /user-roles/{id}` trả 200 bình thường, không có bất kỳ
  kiểm tra "đây có phải admin cuối cùng không" nào trong `UserRoleService.revokeRole`.
- Dự đoán ban đầu trong kịch bản test là: "chủ sở hữu vẫn còn quyền vì ownership tách biệt role,
  chỉ mất quyền vào màn Vai trò". **Thực tế nặng hơn**: đăng nhập lại sau khi mất role duy nhất
  trong tenant, màn "Chọn công ty làm việc" báo **"Bạn chưa thuộc công ty nào — hãy tạo một công
  ty mới để bắt đầu"** — hệ thống frontend xác định "user thuộc công ty nào" hoàn toàn dựa vào
  `user_roles` đang active, **không tính đến `tenants.owner_id`** (đã xác nhận bằng query DB:
  `owner_id` không đổi, vẫn đúng người này, nhưng họ không còn cách nào để vào lại tenant của
  chính mình qua UI).
- Với tenant chỉ có đúng 1 người quản trị (tình huống phổ biến — mọi tenant self-service mới tạo
  đều bắt đầu ở trạng thái này), đây là **tự khóa vĩnh viễn không có đường phục hồi** ngoài can
  thiệp trực tiếp vào DB. Không có endpoint "khôi phục quyền chủ sở hữu", không có luồng UI nào
  khác cứu được. Đã tái hiện và tự phục hồi được trong lúc test **chỉ vì** tenant test khi đó
  tình cờ có thêm 1 tài khoản thứ hai còn giữ `roles:update` — tình huống này sẽ không tồn tại ở
  một tenant 1-admin thật.

**Đã sửa (2026-08-15), cả 3 hướng đề xuất — user chọn làm đủ:**

1. **Chặn ở gốc — safeguard "admin cuối cùng":** `UserRoleService.assertNotLastAdminHolder`,
   gọi trong cả `revokeRole` và `bulkAssignRole`'s `revokeRoleIfHeld`. Trước khi thu hồi 1
   `user_role`, nếu role đó cấp quyền `roles:update`, đếm
   (`UserRoleRepository.countDistinctActiveHoldersOfPermissionInTenant`) xem tenant còn ai khác
   giữ `roles:update` không — nếu đây là người cuối cùng, chặn 409 với thông báo tiếng Việt rõ
   ràng ("Không thể thu hồi role này vì đây là quyền quản trị vai trò cuối cùng của công ty —
   hãy gán quyền quản trị cho người khác trước khi thu hồi role này."). Platform Admin được
   miễn trừ (kênh hỗ trợ chính đáng cho tenant thực sự bị kẹt). Đã test qua UI thật (RoleMembersModal
   trên màn Vai trò & Phân quyền): chủ sở hữu duy nhất tự thu hồi role của mình bị chặn đúng, toast
   hiện thông báo trên; khi có ≥2 người giữ `roles:update`, thu hồi 1 người vẫn hoạt động bình
   thường (không chặn nhầm).
2. **Sửa gốc "biến mất khỏi công ty của chính mình" — tự phục hồi (self-heal):**
   `UserRoleService.selfHealOwnerRoles(userId)` — với mọi tenant mà `tenants.owner_id = userId`,
   nếu người đó đang giữ 0 role active trong tenant đó, tự động gán lại (hoặc kích hoạt lại bản ghi
   `user_role` cũ nếu còn, tránh đụng unique constraint `uq_user_roles` không loại trừ bản ghi đã
   xóa mềm) role `TENANT_ADMIN`, ghi audit `role_self_healed`. Gọi ở 3 điểm: `AuthService.login`
   (trước khi chọn primary tenant/role cho JWT), `AuthService.switchTenant`, và
   `UserRoleService.getCurrentUserRoles` (nguồn dữ liệu của `GET /roles/me`, dùng bởi màn "Chọn
   công ty làm việc"). Đã tái hiện đúng kịch bản gốc và xác nhận đã hết: Platform Admin force-revoke
   role admin cuối cùng của 1 tenant → owner đăng nhập lại → **tự động phục hồi hoàn toàn về
   TENANT_ADMIN, vào thẳng dashboard, không còn màn "Chọn công ty làm việc" chặn đường, không cần
   can thiệp DB**.
3. **Chưa làm riêng lẻ mục "cảnh báo xác nhận trước khi gửi request"** — không cần thiết nữa vì
   mục 1 đã chặn cứng ở backend (an toàn hơn 1 cảnh báo có thể bấm nhầm "Đồng ý"), và mục 2 đã có
   lưới an toàn thứ hai nếu safeguard 1 bị bypass theo cách nào đó trong tương lai.

## 11. Vá 4 gap #33-36 (mời/chấp nhận/hủy lời mời, danh sách nhân viên) + bổ sung notification cho invite/role-change (2026-08-15)

Đợt test tự động #32-36 (xem mục trước) phát hiện 4 tính năng không đạt đủ Acceptance Criteria
gốc, cộng với 1 khoảng trống logic nghiệp vụ rộng hơn: **toàn hệ thống chỉ có đúng 1 nơi tạo
notification** (`RandomCheckDispatchService`) — mời/chấp nhận/hủy lời mời và gán/thu hồi role đều
không thông báo cho ai cả. Đã vá toàn bộ trong cùng đợt:

- **Migration V93**: thêm `employee_invitations.workspace_id`, `.cancelled_by`, `.cancel_reason`,
  `.cancelled_at`.
- **#33 Mời nhân viên**: `InviteEmployeeRequest` thêm `workspaceId` (validate active workspace
  cùng tenant); modal mời (Web) thêm ô chọn workspace; ghi audit `invitation_sent`; nếu email mời
  đã có tài khoản FAMS, gửi notification `EMPLOYEE_INVITED`.
- **#34 Chấp nhận lời mời**: sau khi Employee được resolve (tạo mới hoặc link bản ghi cũ),
  best-effort tạo `WorkspaceMember` cho workspace mặc định của lời mời (bỏ qua an toàn nếu
  workspace đã bị xóa/vô hiệu hóa, không chặn cả luồng accept); ghi audit `invitation_accepted`;
  gửi notification `INVITATION_ACCEPTED` cho người đã mời.
- **#35 Hủy lời mời**: lưu `cancelledBy`/`cancelReason`/`cancelledAt` trực tiếp trên bản ghi (không
  chỉ audit log); modal Hủy (Web) thêm ô nhập lý do tùy chọn; ghi audit `invitation_cancelled`;
  tooltip lý do hiện trên tag trạng thái "Đã hủy" ở màn Lời mời đã gửi.
- **#36 Danh sách nhân viên**: `EmployeeSpecification` thêm 2 predicate mới qua subquery —
  `faceRegistered` (join `face_profiles.status = 'enrolled'`) và `workspaceId` (join
  `workspace_members`, active) — tách biệt hoàn toàn với filter "Phòng ban" cũ (chỉ so tên chuỗi
  trên `Employee.department`, giữ nguyên không đổi). Web Admin thêm 2 ô lọc mới cạnh ô Phòng ban.
- **Notification cho role assign/revoke** (không nằm trong #33-36 nhưng cùng gốc vấn đề, user yêu
  cầu bổ sung): `UserRoleService.assignRole`/`revokeRole` giờ gửi `ROLE_ASSIGNED`/`ROLE_REVOKED`
  cho người bị ảnh hưởng — đặc biệt quan trọng sau đợt vá "tự khóa vĩnh viễn" ở mục 10, người dùng
  giờ biết ngay khi role của mình đổi thay vì tự phát hiện lúc bị 403.
- Catalog `NotificationEventTypeCatalog` cập nhật đủ 4 event type mới (`EMPLOYEE_INVITED`,
  `INVITATION_ACCEPTED`, `ROLE_ASSIGNED`, `ROLE_REVOKED`) để hiện đúng trong màn cài đặt thông báo
  của user.

**Đã test lại toàn bộ end-to-end** trên 1 tenant thật mới tạo riêng: tạo workspace → mời kèm
workspace → xác nhận `workspace_id` lưu đúng + audit + notification tới người được mời (đã có tài
khoản) → chấp nhận → xác nhận `WorkspaceMember` tạo đúng + audit + notification tới người mời →
hủy 1 lời mời khác kèm lý do → xác nhận `cancelled_by`/`cancel_reason`/`cancelled_at` lưu đúng +
audit → lọc danh sách nhân viên theo Face ID (cả 2 chiều) và theo workspace vừa gán tự động (xác
nhận chéo với kết quả của #34) → gán rồi thu hồi role cho cùng người, xác nhận cả 2 notification
xuất hiện đúng trong màn "Thông báo" thật của người đó. Toàn bộ đã dọn dữ liệu test sau khi xác
minh. #33-36 nâng từ 🟡 PASS MỘT PHẦN lên ✅ PASS — ĐÃ KHÓA (trừ #34 phần Mobile App chưa test).

## 12. Vá lỗi "chọn role khi mời không hiện gợi ý, mặc định EMPLOYEE" (2026-08-16)

User phản ánh: modal "Mời tham gia (gửi email)" không hiện role tùy chỉnh của công ty để chọn,
khiến việc mời luôn rơi về mặc định EMPLOYEE dù công ty đã tạo role riêng. Kiểm tra sống (tạo 1
role tùy chỉnh thật, mở modal, xem đúng request gọi API) xác nhận đúng và tìm ra gốc rễ:

- **Nguyên nhân:** `InviteEmployeeModal.tsx` gọi `useRolesQuery({ size: 100 })` — **thiếu tham số
  `tenantId`**. Khi thiếu `tenantId`, `RoleSpecification` (backend) chỉ trả về 4 role hệ thống
  dùng chung toàn platform (`TENANT_ADMIN/HR_MANAGER/SITE_SUPERVISOR/EMPLOYEE`), không bao giờ
  trả về role tùy chỉnh của tenant (khác `tenantId`). Đối chiếu 2 modal khác dùng chung API này —
  `AssignRoleModal` (Gán Role trên hồ sơ nhân viên) và `BulkAssignRoleModal` (Gán role hàng loạt)
  — cả 2 đều truyền `tenantId` đúng và hiển thị đủ role, xác nhận đây là lỗi cục bộ chỉ ở
  `InviteEmployeeModal`, không phải lỗi hệ thống chung.
- **Đã sửa:** thêm `tenantId` (+ `isActive: true`) vào lệnh gọi, bỏ luôn phần lọc lại phía client
  (dư thừa, server đã lọc đúng).
- **Bổ sung theo yêu cầu user** — "tạo nhân viên thủ công cũng cần chọn được role đúng, truyền
  vào khi mời": màn "Thêm hồ sơ (chưa cần đăng nhập)" (`EmployeeFormModal`) tạo hồ sơ HR chưa có
  tài khoản đăng nhập, nên chưa thể gán `UserRole` thật ngay lúc đó. Thêm cột
  `employees.planned_role_id` (migration V94) + ô "Vai trò dự kiến (Tùy chọn)" trên form này để
  lưu lại Ý ĐỊNH — khi sau này gửi lời mời cho đúng email đó mà HR không chọn role tường minh,
  `EmployeeInvitationService.sendInvitation` tự lấy `plannedRoleId` của hồ sơ chưa liên kết thay
  vì để trống (dẫn tới mặc định EMPLOYEE lúc accept). Không đổi hành vi khi HR chọn role tường
  minh lúc mời — role dự kiến chỉ là fallback.
- **Đã test end-to-end thật** trên 1 tenant mới: tạo role tùy chỉnh → xác nhận cả 3 modal
  (Mời qua email, Gán Role, Gán role hàng loạt) đều hiện đúng role đó → tạo hồ sơ thủ công chọn
  "Vai trò dự kiến" = role tùy chỉnh → mời đúng email đó, CỐ Ý để trống ô role → xác nhận
  `employee_invitations.role_id` tự động là role tùy chỉnh (không null, không phải role EMPLOYEE)
  → chấp nhận lời mời → xác nhận `user_roles` cuối cùng đúng role tùy chỉnh.

## 13. Kết luận chung

- **Role & seed permission theo role: đạt yêu cầu**, đúng thứ bậc nghiệp vụ.
- **Danh mục permission trong DB rộng hơn thực tế enforce** — khoảng 23/78 permission (~30%)
  không có tác dụng gì nếu Company Admin tick vào khi tạo role tùy chỉnh. Phần lớn (3a, 3b, 3c,
  3d) là **vô hại nhưng gây hiểu lầm UX** — nên dọn khỏi danh mục hiển thị, không cần đổi logic
  phân quyền đang chạy ổn định.
- **Duy nhất 1 điểm (`tenants:update`, mục 3e) có khả năng là gap thật cần quyết định nghiệp vụ**
  trước khi sửa, không nên tự ý đổi.
- **Đã xử lý xong (2026-08-14):** Clone role + Gán role hàng loạt (mục 5, cả backend lẫn Web
  Admin UI), và lỗ hổng leo thang đặc quyền role nền tảng (mục 6, backend only — role
  `PLATFORM_ADMIN`/`PLATFORM_STAFF` vốn đã không hiện trên UI tenant nên không cần sửa gì thêm
  phía frontend cho phần này).
- **Đã xử lý xong (2026-08-14, đợt 2) — cả 3 việc còn tồn đọng ở trên:**
  1. **Dọn 23 permission chết:** migration V92 thêm cột `permissions.is_assignable` (không xóa
     DB, chỉ ẩn khỏi picker — an toàn, đảo ngược được). `GET /permissions` (nguồn dữ liệu form
     Tạo Role) giờ chỉ trả 55/78 permission thật sự có tác dụng. Đã test qua API: `tenants:update`
     không còn xuất hiện, `employees:create` (permission thật) vẫn còn.
  2. **`tenants:update` — đã thêm miễn trừ Platform Admin**, nhất quán với IP whitelist/tenant
     settings. Đã test: Platform Admin (không phải owner) sửa được hồ sơ tenant (200); user
     thường không phải owner vẫn bị chặn (403) — không nới lỏng nhầm.
  3. **Chặn leo thang đặc quyền** khi tạo/sửa/clone role: caller không phải Platform Admin chỉ
     được cấp permission mà chính họ đang có trong tenant đó (`RoleService.assertNoPrivilegeEscalation`,
     áp dụng cả 3 đường: `createRole`, `updateRole` — chỉ tính permission MỚI thêm, không tính
     permission role đã có sẵn — và `cloneRole`, bịt luôn đường vòng "clone `TENANT_ADMIN` để lấy
     68 quyền"). Đã test kịch bản thật: tạo 1 nhân sự chỉ giữ đúng `roles:create`+`roles:update`
     → thử tự cấp `employees:create` (không có) bị chặn 403; tạo role chỉ với quyền mình có
     (`roles:create`) vẫn thành công 201; thử lách qua clone `TENANT_ADMIN` cũng bị chặn 403.
  - Không cần sửa gì ở Web Admin UI cho cả 3 việc — permission picker tự động theo API mới,
    lỗi leo thang hiển thị qua toast lỗi chuẩn sẵn có.
- **Đã xử lý xong (2026-08-14, đợt 3, mục 8-9):** Chuyển quyền chủ sở hữu công ty
  (`POST /tenants/{id}/transfer-owner`, cả backend lẫn UI); lỗ hổng lộ chéo tenant ở số "Người
  đang giữ" role hệ thống dùng chung; tính năng mới "Thành viên công ty"
  (`GET /tenants/{id}/members`, trang riêng ở Web Admin) — gộp toàn bộ người có quyền truy cập
  công ty (owner/admin/HR/giám sát/nhân viên), không chỉ người có hồ sơ HR như màn Employee cũ.
- **Đã xử lý xong (2026-08-15, mục 10):** Tự khóa vĩnh viễn khi thu hồi role admin cuối cùng —
  vá cả 2 gốc: safeguard chặn thu hồi (`UserRoleService.assertNotLastAdminHolder`, 409 rõ ràng)
  và tự phục hồi (`UserRoleService.selfHealOwnerRoles`, gọi ở login/switch-tenant/`GET /roles/me`)
  cho chủ sở hữu tenant lỡ mất hết role. Đã tái hiện đúng kịch bản gốc bằng Platform Admin
  force-revoke rồi xác nhận owner tự phục hồi hoàn toàn khi đăng nhập lại, không cần can thiệp
  DB. #30 (Thu hồi role, `docs/manual-tests/sprint-1-feature-30-revoke-role.md`) nâng từ 🟡 PASS
  MỘT PHẦN lên ✅ PASS — ĐÃ KHÓA.
