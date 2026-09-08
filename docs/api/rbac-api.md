# Tài liệu tích hợp RBAC (Role & Permission) — Review, sửa lỗi và API tham chiếu

> Cập nhật theo code đang chạy ngày 25/07/2026 (đợt 2). Base path: `/api/v1/roles`, `/api/v1/permissions`, `/api/v1/user-roles`, `/api/v1/users`.
> Đây vừa là **báo cáo review** các tính năng RBAC bạn yêu cầu, vừa là **tài liệu API** để bên frontend dựng màn hình quản lý vai trò/quyền.
> **Đợt cập nhật 25/07/2026 (đầu ngày)**: đã cài đặt đầy đủ **gán role theo phạm vi site** (mục 6) và **role tùy chỉnh cấp nền tảng** cho phân cấp nhân sự nội bộ FAMS (mục 8).
> **Đợt cập nhật 25/07/2026 (đợt 2 — theo phản hồi từ frontend)**: sửa 4 giới hạn FE báo cáo (xem mục 0.1) + xác nhận và mở rộng tính năng **quản lý người dùng toàn hệ thống** (mục 7, mới).

## 0. Tóm tắt kết quả

Module RBAC đã có sẵn CRUD role, gán/thu hồi role, seed tự động qua Flyway migration từ trước. Qua các đợt review (24/07 và 25/07/2026), đã sửa **2 lỗi thật** (lộ dữ liệu chéo tenant, cache quyền cũ), **bổ sung/mở rộng 5 tính năng** (vô hiệu hóa role, gán role theo site, role tùy chỉnh cấp nền tảng, mở rộng user directory, đóng nốt phần site-scope còn thiếu cho random-check).

| # | Tính năng bạn yêu cầu | Trạng thái trước | Việc đã làm |
|---|---|---|---|
| 1 | Seed role/permission khi deploy | ✅ Đã có, tự động qua Flyway | Xác nhận lại, không cần sửa |
| 2 | Danh sách role (tìm/lọc/phân trang), phân biệt admin nền tảng vs admin công ty | ✅ Đã có nhưng **có lỗi bảo mật** | **Đã sửa**: chặn admin công ty xem role của tenant khác |
| 3 | Tạo role tùy chỉnh cho công ty | ✅ Đã có, đúng nghiệp vụ | Xác nhận lại, không cần sửa |
| 4 | Sửa role/quyền + câu hỏi "ảnh hưởng chéo" | ✅ Đã có, đúng nghiệp vụ — **nhưng có lỗi cache** | **Đã sửa**: đổi quyền có hiệu lực ngay, không đợi tối đa 5 phút |
| 5 | Xóa/vô hiệu hóa role | ⚠️ Chỉ có "xóa", chưa có "vô hiệu hóa" | **Đã bổ sung**: cờ `isActive` riêng biệt với xóa |
| 6 | Xem permission theo nhóm | ✅ Đã có, đúng nghiệp vụ | Xác nhận lại, không cần sửa |
| 7 | Gán role cho user theo **site** (không chỉ theo tenant) | ⚠️ 24/07: chưa có | **✅ 25/07: đã cài đặt** — chi tiết mục 6, enforce đầy đủ mọi module kể cả random-check đã dispatch (đóng nốt ở đợt 2) |
| 8 | Thu hồi role | ✅ Đã có, đúng nghiệp vụ | Xác nhận lại, không cần sửa |
| 9 | Role/quyền riêng cấp **nền tảng** (phân cấp nhân sự FAMS, cao/thấp hơn PLATFORM_STAFF) | ⚠️ 24/07: chưa có | **✅ 25/07: đã cài đặt** — chi tiết mục 8 |

**Kết quả test**: sửa xong, build lại, test sống từng lỗi (xem mục 4), chạy lại toàn bộ `tests/rbac/*.sh` (11 file) + `tests/tenant/*.sh` (9 file) + `tests/security/*.sh` — **100% pass**. Trong lúc test tôi cũng phát hiện và dọn dẹp 1 khoản dữ liệu rác trong DB dev (không phải lỗi code) — xem mục 5.

## 0.1 Phản hồi từ frontend (đợt 2) — đã xử lý

| FE báo cáo | Đã xử lý | Chi tiết |
|---|---|---|
| Chưa có API tìm kiếm/danh sách toàn bộ nhân sự FAMS cho màn gán role nền tảng | **API đã tồn tại từ trước**, chỉ là FE chưa biết — `GET /api/v1/users`. Đã mở rộng thêm sort + filter (`isActive`, `isPlatformAdmin`) để đáp ứng đúng nhu cầu | Mục 7 |
| `GET /roles/me` chỉ trả `siteIds`, không trả tên site | **Đã sửa**: bổ sung field `sites: [{id, name}]` bên cạnh `siteIds` cũ (giữ nguyên để không phá vỡ FE đã tích hợp) | Mục 6.2 |
| Site scope chưa áp dụng cho lịch random-check đã dispatch (`scheduled-checks`) | **Đã sửa**: áp dụng đầy đủ cho list/summary/detail/cancel/dispatch | Mục 6.3 |
| Backend chưa trả `assignmentCount` trong danh sách role | **Đã sửa**: `GET /roles` và `GET /roles/{id}` giờ có field `assignmentCount` | Mục 3.1, 3.2 |

## 1. Trả lời câu hỏi nghiệp vụ cốt lõi của bạn

> "Nếu cả hệ thống có bộ quyền chung ban đầu do admin hệ thống dựng nên và sau này các công ty đang mặc định dùng theo khi mới lập công ty, nếu một ngày admin hệ thống sửa bất kỳ role quyền nào thì công ty đang dùng mặc định theo có ảnh hưởng không? Và ngược lại công ty muốn sửa riêng theo ý mình thì tổng thể các công ty khác hoặc mẫu ban đầu có ảnh hưởng không?"

Đây là câu hỏi kiến trúc quan trọng nhất, và **hệ thống hiện tại đã trả lời đúng theo cách các SaaS thực tế làm** (AWS IAM managed policy, Salesforce Standard Profile, GitHub Enterprise base role): **role hệ thống là "khuôn mẫu bất biến dùng chung", role tùy chỉnh là "bản sao riêng cách ly hoàn toàn theo từng công ty"** — hai loại này không thể ảnh hưởng lẫn nhau, vì lý do khác nhau:

### 1.1 Role hệ thống (PLATFORM_ADMIN, TENANT_ADMIN, HR_MANAGER, SITE_SUPERVISOR, EMPLOYEE, PLATFORM_STAFF)

- Chỉ có **đúng 1 bản ghi** cho mỗi role này trong toàn hệ thống (`tenant_id IS NULL`), được tất cả các công ty **dùng chung tham chiếu** — không phải mỗi công ty có 1 bản sao riêng.
- **Không ai sửa/xóa được các role này qua API — kể cả Platform Admin.** Tôi đã test trực tiếp:

```bash
PUT /api/v1/roles/{id-của-TENANT_ADMIN}   (gọi bằng Platform Admin token)
→ 400 {"message":"System roles cannot be modified"}

DELETE /api/v1/roles/{id-của-TENANT_ADMIN}
→ 400 {"message":"System roles cannot be deleted"}
```

- **Vậy câu trả lời cho vế đầu**: admin hệ thống **không có cách nào sửa quyền của TENANT_ADMIN (hay bất kỳ role hệ thống nào) qua giao diện quản trị** — nên không có rủi ro "sửa 1 phát ảnh hưởng hết mọi công ty". Muốn thay đổi bộ quyền mặc định (ví dụ thêm quyền mới cho TENANT_ADMIN), cách duy nhất là **qua migration khi deploy phiên bản mới** (giống `V13`, `V62`, `V66`) — đây là thay đổi có kiểm soát, có review code, có version, đúng tinh thần "managed policy" của AWS chứ không phải một nút bấm sửa tuỳ tiện trên UI.

### 1.2 Role tùy chỉnh do công ty tự tạo

- Mỗi role tùy chỉnh **thuộc về đúng 1 tenant** (`tenant_id` được set), có ràng buộc **unique theo từng tenant** ở tầng DB (`uq_roles_tenant_name`) — công ty A không thể nhìn thấy, sửa, hay vô tình trùng tên với role của công ty B.
- **Vậy câu trả lời cho vế sau**: công ty sửa role tùy chỉnh của mình **không ảnh hưởng gì tới công ty khác hoặc tới role mẫu hệ thống** — vì đó là 2 bảng ghi hoàn toàn khác nhau ngay từ đầu, không có khái niệm "kế thừa rồi tách nhánh" ở đây. Tôi đã test trực tiếp: chủ công ty B gọi `GET /roles?tenantId=<công ty A>` để dò xem công ty A có role gì → hệ thống trả `403` (đây chính là lỗi tôi tìm thấy và sửa, xem mục 4.1).

### 1.3 Tóm tắt mô hình

```text
Role hệ thống (is_system=true, tenant_id=NULL)
  └─ 1 bản ghi DUY NHẤT, dùng chung cho MỌI công ty
  └─ Bất biến qua API — sửa/xóa đều bị chặn (400), kể cả Platform Admin
  └─ Thay đổi CHỈ qua migration lúc deploy version mới (có kiểm soát, có version)

Role tùy chỉnh (is_system=false, tenant_id=<uuid công ty>)
  └─ Mỗi công ty có các bản ghi RIÊNG, cách ly hoàn toàn (DB constraint theo tenant)
  └─ Chủ công ty toàn quyền tạo/sửa/xóa/vô hiệu hóa — không đụng tới công ty khác
  └─ Không có khái niệm "clone từ role hệ thống rồi tùy biến" — tạo mới hoàn toàn độc lập
```

Nếu sau này bạn muốn công ty có thể "nhân bản 1 role hệ thống rồi tùy biến riêng" (ví dụ bấm "Sao chép TENANT_ADMIN" để tạo 1 role tùy chỉnh có sẵn cùng bộ quyền), đó là một tính năng UX bổ sung dễ làm (chỉ cần đọc permission list của role hệ thống rồi gọi `POST /roles` với `permissionIds` tương ứng) — nói với tôi nếu bạn muốn có nút này.

## 2. Seed role/permission khi deploy

Đã xác nhận: Flyway (`spring.flyway.enabled=true`) tự chạy mọi migration khi container khởi động — không cần thao tác thủ công nào. Việc seed nằm rải ở nhiều file, tất cả đều dùng `WHERE NOT EXISTS`/`ON CONFLICT DO NOTHING` nên chạy lại nhiều lần vẫn an toàn:

| Migration | Nội dung |
|---|---|
| `V12` | Tạo bảng `permissions`, `roles`, `role_permissions`, `user_roles` |
| `V13` | Seed ~80 permission (theo resource: users/employees/sites/shifts/assignments/checkins/attendance/randomchecks/violations/reports/notifications/audit/tenants/plans/roles/permissions) + 5 role hệ thống: `PLATFORM_ADMIN, TENANT_ADMIN, HR_MANAGER, SITE_SUPERVISOR, EMPLOYEE` |
| `V62` | Thêm role `PLATFORM_STAFF` (nhân viên vận hành nền tảng, quyền hạn chế hơn Platform Admin) |
| `V66` | Cấp thêm quyền `tenants:create` cho `PLATFORM_STAFF` (để tạo tenant hộ khách) |
| `V67` (mới hôm nay) | Thêm cột `roles.is_active` cho tính năng vô hiệu hóa role |
| Các migration tính năng khác (`V20`, `V23`,...) | Seed thêm permission riêng khi thêm module mới (workspaces, geofences...) |

**Không cần làm gì thêm** — mỗi lần deploy version mới, chỉ cần thêm 1 migration mới nếu muốn bổ sung permission/role hệ thống, Flyway tự áp dụng.

## 3. Tham chiếu API đầy đủ

### 3.1 Danh sách role — `GET /api/v1/roles`

| Query param | Ghi chú |
|---|---|
| `tenantId` | Bỏ trống = chỉ trả role hệ thống. Có giá trị = role hệ thống + role tùy chỉnh của tenant đó |
| `search` | Tìm theo tên role (chứa, không phân biệt hoa thường) |
| `isSystem` | `true`/`false` — lọc riêng role hệ thống hoặc role tùy chỉnh |
| `isActive` | (mới) `true`/`false` — lọc role tùy chỉnh đang hoạt động/đã vô hiệu hóa. Role hệ thống luôn `true` |
| `sortBy` | `name`, `isSystem`, `isActive`, `createdAt`, `updatedAt` |
| `sortDir`, `page`, `size` | Chuẩn như các API khác |

**Quyền truy cập** (cập nhật 24/07/2026 — xem lỗi đã sửa ở mục 4.1):
- Không truyền `tenantId` → ai đăng nhập cũng xem được (chỉ thấy role hệ thống, không nhạy cảm).
- Truyền `tenantId` + là Platform Admin → xem được tenant bất kỳ.
- Truyền `tenantId` + **không phải** Platform Admin → **chỉ xem được nếu bản thân là thành viên của đúng tenant đó**, ngược lại `403`.

**Response mỗi role có field `assignmentCount`** (mới, 25/07/2026 đợt 2): số lượng user đang giữ role đó (đếm theo `user_roles` còn hiệu lực). Dùng để FE biết trước role nào "an toàn" để xóa (0 người giữ) và role nào cần cảnh báo trước khi thao tác — không cần đợi gọi `DELETE` rồi mới biết qua lỗi `400`.

### 3.2 Chi tiết role — `GET /api/v1/roles/{id}`

Trả đầy đủ danh sách permission. Role hệ thống: ai xem cũng được (không nhạy cảm, đây là catalogue công khai). Role tùy chỉnh: Platform Admin hoặc thành viên tenant đó có quyền `roles:read`/`roles:update`.

### 3.3 Tạo role tùy chỉnh — `POST /api/v1/roles`

```json
{
  "tenantId": "<uuid công ty>",
  "name": "Regional Manager VN South",
  "description": "Quản lý các site khu vực miền Nam",
  "permissionIds": ["<uuid1>", "<uuid2>"]
}
```

- Yêu cầu quyền `roles:create` trong đúng tenant đó (hoặc Platform Admin).
- `permissionIds` chọn từ catalogue chung (mục 3.6) — không tự bịa permission mới qua API này.
- Trả `409` nếu tên role trùng trong cùng tenant (role hệ thống và role của tenant khác không tính trùng).

### 3.4 Sửa role — `PUT /api/v1/roles/{id}`

```json
{
  "name": "Senior Site Supervisor",
  "description": "Quản lý nhiều site",
  "permissionIds": ["<uuid1>", "<uuid2>"],
  "isActive": true
}
```

- **Chỉ áp dụng cho role tùy chỉnh** — gọi lên role hệ thống trả `400 "System roles cannot be modified"`.
- `permissionIds` là **danh sách thay thế toàn bộ** (không phải thêm/bớt) — gửi mảng rỗng để xóa hết quyền của role đó.
- `isActive` (mới, optional) — xem mục 3.5.
- Đổi quyền có hiệu lực **ngay lập tức** cho mọi user đang giữ role này (xem lỗi đã sửa ở mục 4.2) — không cần user đó đăng xuất/đăng nhập lại.

### 3.5 Vô hiệu hóa role (mới) vs Xóa role

Đây là tính năng bạn hỏi riêng ("xóa HOẶC vô hiệu hóa") — trước đây hệ thống chỉ có xóa, tôi bổ sung thêm khái niệm vô hiệu hóa tách biệt:

| | Vô hiệu hóa (`PUT` với `isActive:false`) | Xóa (`DELETE`) |
|---|---|---|
| User đang giữ role có bị ảnh hưởng? | **Không** — vẫn giữ nguyên quyền như cũ | Bị chặn hoàn toàn nếu còn ai đang giữ role (`400`) |
| Có gán được cho user MỚI không? | **Không** — `POST /user-roles` trả `400` nếu role đã bị vô hiệu hóa | N/A (role không còn tồn tại) |
| Đảo ngược được không? | Có — `PUT` lại `isActive:true` | Không (soft-delete, nhưng tên vẫn coi như đã dùng cho unique-check trong khung thời gian đó — trên thực tế tên có thể tái sử dụng sau khi xóa) |
| Ai được làm | Chủ công ty (role của mình) hoặc Platform Admin | Chủ công ty (role của mình) hoặc Platform Admin |
| Áp dụng cho role hệ thống? | Không — role hệ thống luôn `isActive=true`, không có cách tắt | Không — bị chặn hoàn toàn |

**Quy trình khuyến nghị cho FE**: khi công ty muốn "ngừng dùng" 1 role, hướng dẫn họ **vô hiệu hóa trước** (an toàn, không phá vỡ user hiện tại), rồi thu hồi role từng người dần dần, cuối cùng mới **xóa hẳn** khi không còn ai giữ.

**Cập nhật 25/07/2026 (đợt 2)**: dùng field `assignmentCount` (mục 3.1) để tự động disable nút **Xóa** ngay từ màn danh sách khi `assignmentCount > 0`, kèm tooltip "Còn N người đang giữ role này — thu hồi hết trước khi xóa". Không cần đợi gọi API xóa rồi mới hiển thị lỗi.

### 3.6 Xem permission theo nhóm — `GET /api/v1/permissions`

Trả về danh sách permission gộp theo `resource` (nghiệp vụ), ai đăng nhập cũng xem được (đây là catalogue chung, không nhạy cảm — công ty cần xem để chọn khi tạo role):

```json
{
  "success": true,
  "data": [
    {
      "resource": "employees",
      "permissionCount": 5,
      "permissions": [
        {"id": "...", "name": "employees:create", "resource": "employees", "action": "create", "description": "Create employees"},
        {"id": "...", "name": "employees:read", "resource": "employees", "action": "read", "description": "View an employee"}
      ]
    }
  ]
}
```

### 3.7 Gán role cho user — `POST /api/v1/user-roles`

```json
{
  "userId": "<uuid nhân viên>",
  "roleId": "<uuid role — hệ thống hoặc tùy chỉnh của tenant này>",
  "tenantId": "<uuid công ty>"
}
```

- Yêu cầu quyền `roles:update` trong tenant đó (hoặc Platform Admin).
- Role phải là role hệ thống, **hoặc** role tùy chỉnh thuộc đúng `tenantId` này (gán nhầm role của tenant khác → `400`).
- Role tùy chỉnh đã bị vô hiệu hóa → `400` (mục 3.5).
- Gán lại cho user đã có role đó (còn hiệu lực) → `409`. Nếu trước đó đã bị thu hồi (soft-delete) → tự động kích hoạt lại thay vì tạo bản ghi mới.
- Có thể kèm `siteIds` để giới hạn phạm vi theo site — xem chi tiết mục 6.

**Đổi role cho nhân viên**: hiện chưa có API "đổi role" 1-bước — quy trình 2 bước: `DELETE /user-roles/{id cũ}` (thu hồi) rồi `POST /user-roles` (gán role mới). Nếu FE cần trải nghiệm "1 dropdown đổi role", ghép 2 lệnh này lại phía client là đủ, không cần thêm API mới.

**Response** (cả `assignRole` lẫn `GET /roles/me`) đều có 2 field song song để FE không phải gọi thêm API resolve tên site: `siteIds` (mảng UUID thô) và `sites` (mảng `{id, name}` đã resolve sẵn tên — mới, 25/07/2026 đợt 2). Cả hai đều rỗng khi role không giới hạn site.

### 3.8 Gán role phạm vi toàn nền tảng — `POST /api/v1/user-roles/platform`

Riêng cho role hệ thống không gắn tenant nào (ví dụ `PLATFORM_STAFF`) — chỉ Platform Admin gọi được, không có khái niệm "tenant" ở đây.

### 3.9 Thu hồi role — `DELETE /api/v1/user-roles/{id}`

Thu hồi (`soft-delete`) 1 bản ghi gán role cụ thể (không phải xóa role). Gán phạm vi tenant → cần quyền `roles:update` trong tenant đó. Gán phạm vi nền tảng (mục 3.8) → chỉ Platform Admin thu hồi được.

### 3.10 Xem role của chính mình — `GET /api/v1/roles/me`

Trả về mọi role user đang giữ, gộp cả tên công ty/slug (hữu ích cho màn "chuyển công ty" nếu user thuộc nhiều tenant) và full permission list theo từng role — dùng để FE dựng menu/ẩn hiện tính năng phía client mà không cần gọi thêm API.

## 4. Chi tiết 2 lỗi đã sửa (kèm bằng chứng test sống)

### 4.1 [Đã sửa] Lộ danh sách role của công ty khác

**Trước khi sửa**: `GET /roles?tenantId=<bất kỳ>` chỉ kiểm tra tenant đó có tồn tại, **không kiểm tra người gọi có thuộc tenant đó không** (trừ khi là Platform Admin). Nghĩa là bất kỳ user nào đã đăng nhập — kể cả tài khoản mới đăng ký, chưa thuộc công ty nào — đều có thể dò `tenantId` của công ty khác và xem được tên/mô tả/số lượng quyền của các role tùy chỉnh công ty đó đã đặt ra (ví dụ tên role kiểu "Trưởng phòng khu vực miền Nam" có thể tiết lộ cơ cấu tổ chức nội bộ).

**Đã sửa**: thêm kiểm tra thành viên tenant cho người gọi không phải Platform Admin.

**Test sống đã chạy**:
```bash
# Chủ công ty B (không thuộc công ty A) cố xem role của công ty A
GET /api/v1/roles?tenantId=<công ty A>   (Bearer: token chủ công ty B)
→ 403 {"message":"Access denied"}

# Chủ công ty A xem role của chính mình — vẫn hoạt động bình thường
GET /api/v1/roles?tenantId=<công ty A>   (Bearer: token chủ công ty A)
→ 200 (thấy đúng role của mình)

# Platform Admin xem role tenant bất kỳ — không bị ảnh hưởng
GET /api/v1/roles?tenantId=<công ty A>   (Bearer: token Platform Admin)
→ 200
```

### 4.2 [Đã sửa] Đổi quyền của role không có hiệu lực ngay

**Trước khi sửa**: hệ thống cache quyền của mỗi user theo tenant trong Redis, tối đa 5 phút (`JwtAuthFilter`), để đỡ phải truy vấn DB mỗi request. Khi công ty sửa quyền của 1 role tùy chỉnh (thêm/bớt permission), **cache của những user đang giữ role đó KHÔNG được xóa** — nghĩa là họ tiếp tục hoạt động với bộ quyền CŨ tối đa 5 phút, kể cả khi đăng xuất/đăng nhập lại (vì cache tính theo user+tenant, không tính theo phiên đăng nhập). Với tính năng "sửa quyền theo thực tế công ty" mà bạn yêu cầu, đây là lỗi ảnh hưởng trực tiếp — công ty tưởng đã thu hồi quyền nhưng nhân viên vẫn thao tác được thêm vài phút.

**Đã sửa**: mỗi lần sửa role tùy chỉnh, hệ thống tự động xóa cache quyền của TẤT CẢ user đang giữ role đó.

**Test sống đã chạy** (dùng 1 role có quyền `sites:read`, gán cho 1 nhân viên thật):
```bash
# Trước khi sửa role: nhân viên gọi API cần sites:read -> permission còn hiệu lực -> 404 (không tìm thấy site, nhưng đã qua được bước kiểm tra quyền)
GET /tenants/{id}/sites/{site-giả}   (Bearer: token nhân viên)
→ 404 Site not found   (= có quyền, chỉ là site không tồn tại)

# Chủ công ty rút hết quyền khỏi role
PUT /roles/{id}  {"permissionIds": []}
→ 200

# Gọi lại NGAY LẬP TỨC (không đợi) bằng đúng token nhân viên đó
GET /tenants/{id}/sites/{site-giả}   (Bearer: token nhân viên, chưa đăng xuất/đăng nhập lại)
→ 403 Access denied   (= quyền đã mất hiệu lực ngay, không phải đợi tối đa 5 phút)
```

## 5. Dữ liệu rác trong DB dev phát hiện khi test (không phải lỗi code)

Trong lúc chạy `tests/tenant/test_plan_limits.sh` để đảm bảo không có hồi quy, tôi phát hiện 2 khoản dữ liệu rác trong database dev — hậu quả của các lần live-test thủ công trước đó trong phiên làm việc này, **không phải lỗi trong code**:

1. Một gói dịch vụ test (`custom-limits-...`) bị để `sort_order=0` (thấp hơn `trial`) và vẫn `is_active=true` → khiến logic "gán gói mặc định = gói active có sort_order thấp nhất" (đã làm ở phần trước) chọn nhầm gói test này thay vì `trial` cho **23 tenant** được tạo trong lúc test hôm nay. Đã vô hiệu hóa gói test này.
2. Giới hạn của gói `trial` bị 1 lần test trước đó ghi đè thành `max_employees=10, max_sites=NULL` thay vì giá trị chuẩn `5/1` (đúng theo seed `V10`). Đã khôi phục lại đúng giá trị chuẩn.

Cả hai chỉ ảnh hưởng tenant test tạo trong phiên làm việc hôm nay ở môi trường dev, không phải dữ liệu khách hàng thật. Nêu ra để bạn biết nguyên nhân nếu có ai hỏi tại sao số liệu gói dịch vụ dev "tự nhiên khác" so với tài liệu — đã khôi phục xong, không cần hành động thêm.

## 6. Gán role theo phạm vi Site (mới, 25/07/2026)

### 6.1 Cách hoạt động

Trước đây `SITE_SUPERVISOR` chỉ là *tên gọi* — người giữ role này có quyền trên **TẤT CẢ site của tenant**, không giới hạn được về 1 site cụ thể. Đã bổ sung: mỗi lượt **gán role** (không phải bản thân role) có thể kèm theo danh sách site giới hạn.

- Bảng mới `user_role_sites` liên kết 1 bản ghi gán role (`user_roles.id`) với 1 hoặc nhiều `site_id`.
- **Quy ước giống hệt IP whitelist đã làm trước đó**: gán role mà **không** kèm site nào (mặc định, hành vi cũ không đổi) = không giới hạn, thấy toàn bộ tenant. Kèm 1+ site = **chỉ** giới hạn trong các site đó.
- **Một user giữ nhiều role trong cùng tenant → quy tắc "không giới hạn thắng"**: nếu BẤT KỲ role nào của họ trong tenant đó là không giới hạn (kể cả Platform Admin, hoặc 1 role hệ thống gán không kèm site), họ luôn thấy toàn bộ tenant — giống cách tổng quyền của 1 user luôn là hợp của mọi role họ giữ, không phải giao. Chỉ khi **toàn bộ** role của họ trong tenant đó đều bị giới hạn site thì họ mới bị giới hạn, và phạm vi là **hợp** của các site từ mọi role đó.

### 6.2 API — chỉ thêm 1 field, không phá vỡ gì cũ

`POST /api/v1/user-roles` — thêm field optional `siteIds`:

```json
{
  "userId": "<uuid nhân viên>",
  "roleId": "<uuid role SITE_SUPERVISOR>",
  "tenantId": "<uuid công ty>",
  "siteIds": ["<uuid site 1>", "<uuid site 2>"]
}
```

Bỏ qua `siteIds` hoặc gửi mảng rỗng = hành vi y hệt trước 25/07/2026 (không giới hạn). Site phải thuộc đúng tenant, không thì trả `404`.

Response (`GET /roles/me`, `POST /user-roles`, ...) đều có thêm field `siteIds` để FE biết phạm vi hiện tại của từng lượt gán.

### 6.3 Phạm vi đã enforce thật — liệt kê chính xác để không hiểu nhầm

Tôi đã **enforce thật** (không chỉ lưu dữ liệu cho có) ở các module sau, test trực tiếp bằng kịch bản: tạo 2 site, 1 supervisor chỉ gán cho Site A, xác nhận họ thấy Site A nhưng bị `403` ở Site B, còn admin/owner không giới hạn không bị ảnh hưởng gì:

| Module | Endpoint | Trạng thái |
|---|---|---|
| Site | `GET /sites` (danh sách), `GET /sites/{id}` (chi tiết) | ✅ Đã enforce |
| Assignment | `GET/POST/PATCH/DELETE .../sites/{siteId}/assignments...` | ✅ Đã enforce (site nằm sẵn trên URL, chỉ cần kiểm tra 1 site) |
| Employee | `GET /employees` (danh sách), `GET /employees/{id}` (chi tiết) | ✅ Đã enforce — nhân viên không gắn site trực tiếp, suy ra qua bảng Assignment (nhân viên từng được assign vào site nào) |
| Checkin | `GET .../checkin` (list HR), `GET .../checkin/{id}` (chi tiết), override check-in | ✅ Đã enforce |
| Attendance | `GET .../attendance` (list), chi tiết, `GET .../attendance/monthly`, `PATCH .../adjust` | ✅ Đã enforce |
| Random check — cấu hình theo site (tạo/xem/sửa/xóa, cả theo `siteId` lẫn theo `configId`) | `.../random-check-configs/...` | ✅ Đã enforce (kể cả list — lọc bớt config của site khác, giữ lại config mặc định toàn tenant) |
| Random check — lịch đã dispatch (mới đóng, 25/07/2026 đợt 2) | `GET .../scheduled-checks` (list/summary/detail), `POST .../{id}/cancel`, `POST .../{id}/dispatch` | ✅ Đã enforce — supervisor giới hạn 1 site: không truyền `siteId` sẽ tự lọc theo site của họ; truyền `siteId` của site khác → `403`; giới hạn nhiều site mà không truyền `siteId` → `403` kèm yêu cầu chỉ định rõ 1 site (list API hiện chỉ lọc được 1 site tại 1 lần gọi) |

**CHƯA enforce (biết rõ, không che giấu)**:

| Module | Endpoint | Lý do chưa làm |
|---|---|---|
| Random check — trạng thái hàng đợi dispatch | `GET .../scheduled-checks/dispatch-queue` | Đây là số liệu vận hành nội bộ (kích thước hàng đợi Redis), không phải dữ liệu nghiệp vụ theo site — độ ưu tiên thấp, chưa làm trong đợt này |

**Nếu bạn cần mục trên gấp**, báo tôi làm tiếp — cùng cơ chế `SiteScopeService` đã dựng sẵn.

### 6.4 Ví dụ thực tế

```text
Công ty có 2 site: "Site Quận 1" và "Site Quận 7"
Owner gán:
  - Anh Long -> SITE_SUPERVISOR, siteIds=["Site Quận 1"]
  - Chị Mai  -> SITE_SUPERVISOR, siteIds=["Site Quận 7"]

Kết quả:
  - Anh Long: GET /sites chỉ thấy "Site Quận 1", xem nhân viên/checkin/chấm công CHỈ của site đó
  - Chị Mai: tương tự nhưng với "Site Quận 7"
  - Owner (TENANT_ADMIN, không giới hạn): vẫn thấy cả 2 site như bình thường
```

## 7. Quản lý người dùng toàn hệ thống (User Directory) — Platform Admin

Bạn hỏi: *"kiểm tra hộ tôi xem có tính năng quản lý người dùng trên toàn bộ hệ thống cho phía nền tảng chưa? ... để khi tạo công ty cho khách tôi có gọi xem danh sách trên giao diện (có tính năng gợi ý khi nhập), admin cũng có thể quản lý danh sách nhân viên nền tảng và gán vai trò"*.

### 7.1 Kết quả kiểm tra

**API này đã tồn tại từ trước** (`GET /api/v1/users`) — không phải FE thiếu chức năng, mà là **FE chưa biết endpoint này tồn tại**. Tôi đã rà soát và xác nhận:

- Đã có sẵn: tìm kiếm theo email/tên (`search`), phân trang (`page`/`size`), chỉ Platform Admin gọi được, tự động lọc tài khoản đã xóa mềm.
- **Còn thiếu so với yêu cầu thực tế** (đã bổ sung hôm nay): chưa có sắp xếp (`sortBy`/`sortDir`), chưa lọc theo trạng thái tài khoản hay theo "có phải nhân viên nền tảng không", response DTO thiếu field `isPlatformAdmin`/`lastLoginAt` (cần để dựng màn "Quản lý nhân viên nền tảng").

**Đối chiếu hệ thống thực tế**: đây đúng là mẫu "Admin Console → Users" mà Auth0, Okta, AWS Cognito đều có — 1 API duy nhất phục vụ cả 2 mục đích (tra cứu nhanh để autocomplete, và duyệt/lọc toàn bộ để quản lý) thay vì tách riêng 2 hệ thống, vì quy mô người dùng của 1 nền tảng B2B như FAMS chưa đến mức cần endpoint "gợi ý nhanh" (typeahead) tách biệt về hiệu năng — dùng `size` nhỏ (5-10) khi gọi cho mục đích autocomplete là đủ.

### 7.2 API — `GET /api/v1/users`

| Query param | Ghi chú |
|---|---|
| `search` | Tìm theo email hoặc tên hiển thị (chứa, không phân biệt hoa thường) |
| `isActive` | (mới) `true`/`false` — lọc tài khoản đang hoạt động/đã khóa |
| `isPlatformAdmin` | (mới) `true`/`false` — lọc riêng tài khoản là nhân viên nền tảng (dùng để dựng màn "Danh sách nhân viên FAMS") |
| `sortBy` | (mới) `email`, `displayName`, `createdAt`, `lastLoginAt` |
| `sortDir`, `page`, `size` | Chuẩn như các API khác |

Response mỗi user (đã bổ sung `isPlatformAdmin`, `lastLoginAt`):

```json
{
  "id": "...",
  "email": "alice@acme.com",
  "displayName": "Alice Nguyen",
  "avatarUrl": null,
  "isActive": true,
  "isPlatformAdmin": false,
  "lastLoginAt": "2026-07-24T08:00:00Z",
  "createdAt": "2026-06-01T00:00:00Z"
}
```

*(Response đầy đủ còn có `emailVerified`, `phone`, `phoneVerified`, `dateOfBirth`, `hometown`, `gender`, `address`, `googleLinked` — xem `UserProfileResponse`, đây là DTO dùng chung với API "hồ sơ cá nhân".)*

### 7.3 Hai kịch bản sử dụng chính

**(a) Autocomplete khi tạo công ty hộ khách hàng** (`POST /tenants` với `ownerEmail`/`ownerUserId`):
```text
Admin gõ vào ô "Chủ sở hữu" → gọi GET /users?search=<text đang gõ>&size=8
→ hiện dropdown: tên + email, chọn xong lấy id điền vào ownerUserId
```

**(b) Quản lý nhân viên nền tảng** (màn "Nhân sự FAMS" trong Admin Console, phục vụ gán role nền tảng — mục 8):
```text
GET /users?isPlatformAdmin=true&sortBy=lastLoginAt&sortDir=desc
→ bảng danh sách nhân viên đang có quyền nền tảng, sắp xếp theo hoạt động gần nhất
→ mỗi dòng có nút "Gán vai trò nền tảng" → mở modal chọn role → gọi POST /user-roles/platform
```

Với (b), lưu ý: `isPlatformAdmin=true` chỉ lọc user có cờ `isPlatformAdmin` (tức PLATFORM_ADMIN thật) — **không** phải danh sách người đang giữ role `PLATFORM_STAFF`/role cấp nền tảng tùy chỉnh (đó là 2 khái niệm khác nhau: cờ tài khoản vs role được gán). Nếu FE cần "danh sách người đang giữ role X", phải dùng `GET /roles/{roleId}` rồi tra `assignmentCount`, hoặc cần 1 API mới "liệt kê user theo role" — hiện **chưa có**, báo tôi nếu cần.

### 7.4 Bảo mật — giữ nguyên quy ước đã có

Chỉ Platform Admin gọi được (`hasRole('PLATFORM_ADMIN')`), không lộ field nhạy cảm (`passwordHash`, `totpSecret`, số lần đăng nhập sai, thời điểm khóa tài khoản) — response dùng chung DTO với API hồ sơ cá nhân nên tự động thừa hưởng quy ước ẩn dữ liệu nhạy cảm đó, không cần làm gì thêm.

## 8. Role tùy chỉnh cấp Nền tảng — phân cấp nhân sự nội bộ FAMS (mới, 25/07/2026)

### 8.1 Nhu cầu

Bạn hỏi: *"admin nền tảng có thể tạo/chỉnh role riêng hay quyền riêng liên quan đến các chức năng phía nền tảng (ví dụ sau này muốn tạo vai trò cao hoặc thấp hơn vai trò nhân viên nền tảng)"*. Trước 25/07/2026, role hệ thống cấp nền tảng chỉ có 2 bậc cố định: `PLATFORM_ADMIN` (toàn quyền) và `PLATFORM_STAFF` (quyền hạn chế, seed cứng trong migration). Không có cách nào tạo thêm 1 bậc mới (ví dụ "Support Tier 2", "Billing Ops") mà không sửa code + release migration mới.

### 8.2 Cách hoạt động

`POST /api/v1/roles` — field `tenantId` giờ **không bắt buộc**:

```json
{
  "name": "Support Tier 2",
  "description": "Xử lý escalation, quyền cao hơn Support Tier 1 nhưng thấp hơn PLATFORM_STAFF"
}
```

- **Bỏ trống `tenantId`** = tạo role cấp nền tảng (`tenant_id = NULL`, giống hệt cơ chế `PLATFORM_STAFF` nhưng do Platform Admin tự định nghĩa qua API, không cần release code mới).
- Chỉ **Platform Admin thật** (không tính `PLATFORM_STAFF`) mới tạo/sửa/xóa/xem được loại role này — đây là quyết định quản trị nền tảng, không giao cho ai khác kể cả staff cấp cao. Tương tự cách AWS chỉ "root"/admin mới sửa được cấu trúc IAM cơ bản.
- **Ẩn hoàn toàn với người ngoài**: `GET /roles` (không truyền `tenantId`) chỉ trả role hệ thống công khai (`TENANT_ADMIN`, `EMPLOYEE`...) cho người dùng thường — role tùy chỉnh cấp nền tảng **không xuất hiện** trong danh sách này trừ khi người gọi là Platform Admin. Gọi thẳng `GET /roles/{id}` cũng bị chặn `403` nếu không phải Platform Admin.
- Gán role này cho 1 nhân viên FAMS: dùng endpoint sẵn có `POST /api/v1/user-roles/platform` (giống cách gán `PLATFORM_STAFF` từ trước) — role tùy chỉnh cấp nền tảng **không thể** gán qua `POST /user-roles` (endpoint theo tenant) dù có cố tình cũng bị chặn `400`, tránh việc 1 tenant admin vô tình cấp quyền quản trị nền tảng cho ai đó.
- Cũng dùng được cờ **`isActive`** (mục 5) để tạm ngừng 1 bậc nhân sự nội bộ mà không cần xóa hẳn.

### 8.3 Ví dụ luồng đầy đủ

```bash
# 1. Platform Admin tạo role mới cấp nền tảng
POST /api/v1/roles
{"name":"Support Tier 2","description":"Escalation support"}
→ 201, tenantId: null

# 2. Gán quyền cho role (giống role thường)
PUT /api/v1/roles/{id}
{"name":"Support Tier 2","permissionIds":["<uuid tenants:read>","<uuid audit:read>"]}

# 3. Gán role này cho 1 nhân viên FAMS
POST /api/v1/user-roles/platform
{"userId":"<uuid nhân viên>","roleId":"<uuid Support Tier 2>"}
```

### 8.4 Test đã chạy

Đã test sống: nhân viên công ty thường tạo role không `tenantId` → `403`; Platform Admin tạo được → `201` với `tenantId: null`; role này **không** xuất hiện trong `GET /roles` của người dùng thường nhưng **có** xuất hiện khi Platform Admin gọi; gọi trực tiếp `GET /roles/{id}` bằng tài khoản thường → `403`; cố gán role này qua `POST /user-roles` (theo tenant) → `400` với thông báo rõ ràng yêu cầu dùng đúng endpoint `/user-roles/platform`; gán đúng cách qua `/user-roles/platform` → `201` thành công.

## 9. Checklist bàn giao frontend

> Khi gán trùng vai trò trong công ty, API trả thông báo tiếng Việt dạng `Nhân viên đã có vai trò "Nhân viên" trong công ty này.`; không trả UUID người dùng hoặc tên mã hệ thống như `EMPLOYEE` ra giao diện. Bulk assign giữ cùng thông báo trong từng phần tử lỗi để UI hiển thị trực tiếp.

- [ ] Màn "Quản lý vai trò" (company admin) chỉ hiện role của công ty mình + role hệ thống (read-only, không có nút sửa/xóa role hệ thống).
- [ ] Nút "Sửa"/"Xóa"/"Vô hiệu hóa" ẩn hoàn toàn với role hệ thống (`isSystem=true`) — kể cả với Platform Admin.
- [ ] Form tạo/sửa role: chọn permission từ `GET /permissions` (đã nhóm sẵn theo resource, dựng UI dạng checklist theo nhóm).
- [ ] Khi công ty muốn "ngừng dùng 1 role", hướng dẫn UI ưu tiên nút **Vô hiệu hóa** trước, chỉ hiện nút **Xóa** khi role đó không còn ai giữ (BE tự chặn xóa nếu còn người giữ, nhưng nên disable nút phía UI luôn để tránh gọi API rồi nhận lỗi).
- [ ] Đổi role cho nhân viên trong màn "Danh sách nhân viên": ghép 2 lệnh `DELETE /user-roles/{id cũ}` + `POST /user-roles` (chưa có API 1 bước).
- [ ] Màn gán role cho 1 nhân viên: nếu role là `SITE_SUPERVISOR` (hoặc role tùy chỉnh tương tự), hiện thêm ô chọn "Phạm vi: Toàn công ty / Chọn site cụ thể" — map sang field `siteIds` (mục 6.2). Bỏ trống = toàn công ty.
- [ ] Màn "Danh sách công ty của tôi" (site-scoped user): `GET /roles/me` trả sẵn `sites: [{id,name}]` cho từng role (đã resolve tên, mới 25/07/2026 đợt 2) — dùng để hiện badge "Phụ trách: Site A, Site B" mà không cần gọi thêm `GET /sites`.
- [ ] Màn quản trị nội bộ FAMS (Platform Admin only, KHÔNG lộ cho company admin/staff): "Quản lý vai trò nền tảng" — tạo/sửa/gán role không kèm `tenantId`, dùng `/user-roles/platform` để gán, xem mục 8.
- [ ] Nút **Xóa role** trong danh sách: disable khi `assignmentCount > 0` (mục 3.1) kèm tooltip số người đang giữ — không đợi gọi API rồi nhận lỗi mới biết.
- [ ] Ô "Chủ sở hữu" trong form tạo công ty hộ khách hàng: dùng autocomplete gọi `GET /users?search=...&size=8` (mục 7.3a), gửi UUID chọn được vào `ownerUserId`.
- [ ] Màn "Nhân sự nền tảng" (Admin Console, Platform Admin only): dùng `GET /users?isPlatformAdmin=true` (mục 7.3b) làm danh sách chính; hành động "Gán vai trò" mở modal chọn role rồi gọi `/user-roles/platform`.
- [ ] Lịch random-check đã dispatch (`scheduled-checks`) giờ đã áp dụng site-scope đầy đủ — supervisor bị giới hạn nhiều site sẽ nhận `403` nếu không truyền `siteId` cụ thể, FE nên hiện bộ lọc site bắt buộc chọn 1 site cho nhóm user này.
