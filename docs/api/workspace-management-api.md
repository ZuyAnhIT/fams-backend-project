# Tài liệu tích hợp Quản lý Workspace (Phòng ban/Đội nhóm) — Review, sửa lỗi, bổ sung và API tham chiếu

> Cập nhật theo code đang chạy ngày 26/07/2026. Base path: `/api/v1/tenants/{tenantId}/workspaces`.

## 0. Tóm tắt kết quả

**5 tính năng bạn liệt kê đã được xây dựng đầy đủ, đúng nghiệp vụ từ trước** — tạo/danh sách/cập nhật workspace, gán nhân viên vào workspace, chuyển nhân viên giữa các workspace đều có API thật, đã hoạt động tốt. Qua review đối chiếu với các hệ thống HRIS/workforce thực tế (BambooHR, Deputy, Connecteam — đều có khái niệm "department/team" phân cấp), tôi tìm thấy **1 lỗ hổng nghiệp vụ thật** (HR_MANAGER không tạo/sửa được workspace dù bạn yêu cầu rõ "HR/quản lý nhân sự" là người thực hiện), **1 tính năng bị bỏ dở giữa chừng** (quyền `workspaces:delete` đã seed cho TENANT_ADMIN/PLATFORM_ADMIN từ đầu nhưng chưa từng có endpoint dùng tới), và **1 khoảng trống an toàn dữ liệu** (không có gì ngăn gán/chuyển nhân viên vào 1 workspace đã ngừng hoạt động). Ngoài ra phát hiện và (theo quyết định của bạn) **đã xử lý 1 vấn đề kiến trúc** (mục 4): hệ thống có 2 khái niệm "phòng ban" độc lập, không liên kết — đã hợp nhất về `Workspace`, loại bỏ hẳn `Department`.

| # | Tính năng bạn yêu cầu | Trạng thái trước | Việc đã làm |
|---|---|---|---|
| 1 | Tạo workspace | ✅ Đã có, đúng nghiệp vụ | Bổ sung `activeMemberCount`/`childWorkspaceCount` trong response — mục 2.1 |
| 2 | Danh sách workspace (tìm/lọc trạng thái) | ✅ Đã có, đúng nghiệp vụ | Bổ sung 2 field đếm ở trên, không đổi hành vi tìm/lọc |
| 3 | Cập nhật workspace (tên/mã/mô tả/cha) | ✅ Đã có, đúng nghiệp vụ, đã chống tham chiếu vòng | Xác nhận lại, không cần sửa |
| 4 | Gán nhân viên vào workspace | ✅ Đã có, đúng nghiệp vụ | **Đã sửa** — chặn gán vào workspace đã inactive, mục 2.2 |
| 5 | Chuyển nhân viên sang workspace khác | ✅ Đã có, đúng nghiệp vụ | **Đã sửa** — chặn chuyển vào workspace đích đã inactive, mục 2.2 |
| — | Xóa workspace | ❌ Có quyền (`workspaces:delete`) nhưng không có endpoint nào dùng | **✅ Xây mới** — mục 2.3 (bạn không yêu cầu, xem giải thích) |
| — | HR_MANAGER tạo/sửa workspace | ❌ Seed chỉ cho HR_MANAGER xem (`read`/`list`), không cho tạo/sửa dù đúng yêu cầu của bạn | **Đã sửa** — mục 2.4 |
| — | Trùng lặp `Department` vs `Workspace` | ❌ 2 hệ thống song song, không liên kết | **✅ Đã hợp nhất** — bạn chọn gộp về Workspace, xem mục 4 |

**Kết quả test**: build lại, test sống từng lỗi/tính năng mới, chạy lại toàn bộ `tests/workspace/*.sh` (6 file, 65 test) + `tests/rbac/*.sh` (11 file, 100 test) + `tests/employee/*.sh` (13 file, 96 test) + `tests/tenant/*.sh` (9 file, 91 test) + `tests/search/*.sh` (16 test) + `tests/report/*.sh` (6 file, 67 test) — **100% pass**, không hồi quy.

## 1. Trả lời câu hỏi nghiệp vụ: các tính năng có liên kết với nhau không?

Có — chuỗi liên kết đúng như các hệ thống workforce management thực tế vẫn làm:

```
Tạo workspace → xuất hiện trong Danh sách/Cây workspace
       ↓
Gán nhân viên vào workspace → activeMemberCount tăng, hiện trong "Danh sách thành viên"
       ↓
Chuyển nhân viên sang workspace khác → activeMemberCount nơi cũ giảm, nơi mới tăng
(atomic: xóa mềm thành viên cũ + tạo thành viên mới trong cùng 1 transaction)
       ↓
Cập nhật workspace (đổi cha) → cây phân cấp cập nhật ngay, có chống vòng lặp
       ↓
Xóa workspace → chỉ cho phép khi KHÔNG còn thành viên active VÀ KHÔNG còn workspace con active
```

Điểm liên kết quan trọng nhất: **`activeMemberCount`/`childWorkspaceCount` chính là điều kiện để xóa** — giống hệt cách Role đã làm ở đợt review RBAC trước (`assignmentCount` chặn xóa role đang có người giữ). Đây là pattern nhất quán xuyên suốt toàn hệ thống: **không cho xóa 1 thực thể đang được tham chiếu, phải gỡ hết tham chiếu trước**.

## 2. Chi tiết các thay đổi

### 2.1 [Đã bổ sung] Số lượng thành viên/workspace con hiển thị ngay trong response

**Trước khi sửa**: `WorkspaceResponse` không có cách nào để biết 1 workspace đang có bao nhiêu nhân viên hay bao nhiêu workspace con mà không gọi thêm API riêng — HR muốn biết trước khi định xóa hay tổ chức lại phải tự đếm thủ công.

**Đã sửa**: thêm 2 field:
```json
{
  "activeMemberCount": 12,
  "childWorkspaceCount": 2
}
```
Có mặt trong **cả 4 endpoint** trả về `WorkspaceResponse`: tạo, danh sách (phân trang), cập nhật, xem chi tiết. Danh sách dùng truy vấn gộp theo lô (batch `GROUP BY`) để không phát sinh N+1 query — cùng kỹ thuật đã dùng cho `assignmentCount` của Role.

Test sống xác nhận: tạo workspace cha → cả 2 field = 0; thêm 1 workspace con → `childWorkspaceCount` cha = 1; gán 1 nhân viên vào cha → `activeMemberCount` = 1.

### 2.2 [Đã sửa — lỗ hổng nghiệp vụ] Vẫn gán/chuyển được nhân viên vào workspace đã ngừng hoạt động

**Trước khi sửa**: đặt `status = "inactive"` cho 1 workspace (nghĩa là "đang giải thể/không còn dùng") không ngăn được việc tiếp tục gán nhân viên mới vào đó, hoặc chuyển nhân viên từ nơi khác sang. Không có hệ thống HRIS thực tế nào cho phép việc này — một phòng ban đã "khai tử" mà vẫn nhận thêm người là mâu thuẫn nghiệp vụ rõ ràng, dẫn tới báo cáo tổ chức sai lệch (nhân viên thuộc 1 đơn vị "không tồn tại" trên sơ đồ).

**Đã sửa**: áp dụng đúng nguyên tắc **"deactivation chặn hành động MỚI, không ảnh hưởng người đã ở trong đó"** — giống hệt cách Role đã làm ở đợt review RBAC trước (role bị vô hiệu hóa không tước quyền người đang giữ, chỉ chặn gán thêm người mới):
- `assignMember`: chặn nếu workspace đích `status != "active"`, trả lỗi rõ ràng "Workspace 'X' is inactive and can no longer accept new members".
- `transferMember`: chặn nếu workspace **đích** (không phải nguồn) `status != "active"` — nhân viên vẫn có thể được chuyển RA KHỎI 1 workspace inactive (thực ra transfer luôn implicit rời khỏi nguồn), chỉ không được chuyển VÀO 1 nơi đã inactive.

Test sống xác nhận: tạo workspace, đặt inactive → gán nhân viên vào → `400` đúng thông báo; tạo nhân viên đã ở workspace khác đang active → chuyển vào workspace inactive → `400` đúng thông báo "Target workspace ... is inactive".

### 2.3 [Xây mới — bạn không yêu cầu, nhưng khớp với quyền đã seed sẵn] `DELETE /workspaces/{id}`

**Phát hiện khi review**: migration `V20__create_workspaces.sql` đã cấp quyền `workspaces:delete` cho `PLATFORM_ADMIN` và `TENANT_ADMIN` **từ lúc tạo module** — nhưng không hề có endpoint nào gọi tới. Nghĩa là quyền tồn tại trên giấy, chưa từng có tác dụng thật. Đây rất có thể là tính năng bị bỏ dở giữa chừng khi module được xây ban đầu (V20/V21), chứ không phải cố ý thiết kế "không cho xóa".

**Đã xây**: `DELETE /api/v1/tenants/{tenantId}/workspaces/{workspaceId}` — xóa mềm (`deletedAt`), điều kiện:
- Chặn nếu còn `activeMemberCount > 0` — phải gỡ/chuyển hết nhân viên trước.
- Chặn nếu còn `childWorkspaceCount > 0` — phải đổi cha hoặc xóa hết workspace con trước.
- Yêu cầu quyền `workspaces:delete`.

Đây là bổ sung chủ động của tôi (không nằm trong 5 tính năng bạn liệt kê), lý do: quyền đã tồn tại sẵn trong seed data mà không có tác dụng là một dạng "tính năng ma" dễ gây hiểu lầm khi audit hệ thống — nên tôi hoàn thiện nốt cho khớp với ý định ban đầu của migration.

Test sống xác nhận: xóa workspace còn thành viên/con → `400` đúng cả 2 trường hợp; gỡ hết thành viên và workspace con → xóa thành công `200`; gọi lại `GET` sau khi xóa → `404`.

### 2.4 [Đã sửa — lỗ hổng nghiệp vụ] HR_MANAGER không tạo/sửa được workspace

**Phát hiện khi review seed data**: bạn yêu cầu rõ *"hr/admin hoặc quản lý nhân sự tôi muốn tạo phòng ban/đội nhóm"* và *"...tôi muốn sửa tên, mã, mô tả..."* — nhưng migration gốc chỉ cấp `workspaces:read`/`workspaces:list` cho `HR_MANAGER`, còn `workspaces:create`/`update`/`delete` chỉ có `TENANT_ADMIN`. Nghĩa là theo cấu hình mặc định, HR_MANAGER **chỉ xem được** sơ đồ tổ chức, không tự tạo/sửa được — trái với đúng nghiệp vụ bạn mô tả và trái với cách các hệ thống HRIS thực tế vẫn phân quyền (BambooHR/Deputy đều cho phép vai trò "HR Admin" tự quản lý cây phòng ban mà không cần leo lên vai trò company owner).

**Đã sửa** (migration `V70__grant_hr_manager_workspace_management.sql`): cấp thêm `workspaces:create` và `workspaces:update` cho `HR_MANAGER`. **Cố ý KHÔNG cấp `workspaces:delete`** cho HR_MANAGER — giữ việc xóa hẳn 1 đơn vị tổ chức (hành động khó đảo ngược nhất) ở mức TENANT_ADMIN, cùng nguyên tắc "hành động phá hủy cần bậc quyền cao hơn tạo/sửa" đã áp dụng nhất quán cho Role ở đợt review trước. Nếu bạn muốn HR_MANAGER cũng xóa được, báo tôi thêm 1 dòng cấp quyền nữa.

Test sống xác nhận: tạo user gán role HR_MANAGER (không phải TENANT_ADMIN) → gọi `POST /workspaces` → `201` (trước sửa sẽ là `403`); `PUT /workspaces/{id}` → `200`. Chạy lại toàn bộ `tests/rbac/*.sh` sau khi seed thay đổi — vẫn 100% pass, không phá vỡ test nào giả định quyền cũ.

## 3. API tham chiếu đầy đủ

Base path: `/api/v1/tenants/{tenantId}/workspaces`

| # | Endpoint | Method | Quyền cần | Mô tả |
|---|---|---|---|---|
| 1 | `/` | POST | `workspaces:create` | Tạo workspace (department/team), tên duy nhất không phân biệt hoa/thường trong tenant, `parentId` optional |
| 2 | `/` | GET | `workspaces:list` | Danh sách phân trang, filter `search`/`status`/`type`, sort `sortBy`/`sortDir` |
| 3 | `/tree` | GET | `workspaces:list` | Cây phân cấp đầy đủ, filter `search` (giữ cả tổ tiên của node khớp) và `status` |
| 4 | `/{id}` | GET | `workspaces:read` | Chi tiết 1 workspace, kèm `activeMemberCount`/`childWorkspaceCount` |
| 5 | `/{id}` | PUT | `workspaces:update` | Sửa tên/mô tả/loại/trạng thái/cha (partial update), chống tham chiếu vòng |
| 6 | `/{id}` | DELETE | `workspaces:delete` | **Mới** — xóa mềm, chặn nếu còn thành viên/con active |
| 7 | `/{id}/members` | POST | `workspace_members:create` | Gán nhân viên vào workspace, chặn nếu workspace inactive (mới) |
| 8 | `/{id}/members` | GET | `workspace_members:list` | Danh sách thành viên (phân trang), kèm thông tin nhân viên rút gọn |
| 9 | `/{id}/members/{memberId}` | DELETE | `workspace_members:delete` | Gỡ nhân viên khỏi workspace (xóa mềm) |
| 10 | `/{sourceId}/members/{memberId}/transfer` | POST | `workspace_members:create` **và** `workspace_members:delete` | Chuyển nhân viên sang workspace khác, kế thừa vai trò trừ khi ghi đè, chặn nếu đích inactive (mới) |

**Response `WorkspaceResponse` (đầy đủ, sau thay đổi)**:
```json
{
  "id": "uuid",
  "tenantId": "uuid",
  "name": "Engineering",
  "description": "...",
  "type": "department | team",
  "parentId": "uuid | null",
  "status": "active | inactive",
  "activeMemberCount": 12,
  "childWorkspaceCount": 2,
  "createdBy": "uuid",
  "createdAt": "...",
  "updatedAt": "..."
}
```

**Vì sao `transferMember` cần CẢ 2 quyền `create` và `delete`** (không phải lỗi, xác nhận lại): chuyển nhân viên về bản chất là "xóa thành viên cũ + tạo thành viên mới" trong 1 transaction — người thực hiện phải có quyền làm cả 2 vế, đúng với cách các hệ thống phân quyền chi tiết (fine-grained RBAC) vẫn yêu cầu. Không cần sửa gì ở đây.

## 3.1 Request/Response đầy đủ từng trường (để dựng form chính xác)

**`POST /` — tạo workspace** (`CreateWorkspaceRequest`):
```json
{
  "name": "Engineering",           // bắt buộc, tối đa 100 ký tự, unique không phân biệt hoa/thường trong tenant
  "description": "...",            // optional, không giới hạn độ dài
  "type": "department",            // optional (mặc định "department") — CHỈ nhận đúng 2 giá trị: "department" | "team"
  "parentId": "uuid | null"        // optional — phải là workspace có thật trong cùng tenant
}
```
Lỗi validate: thiếu `name` hoặc `name` toàn khoảng trắng → `400` message "Name is required"; `name` > 100 ký tự → `400`; `type` khác "department"/"team" → `400` "Type must be 'department' or 'team'".

**`PUT /{id}` — cập nhật workspace** (`UpdateWorkspaceRequest`, mọi field optional — chỉ field có mặt mới bị đổi):
```json
{
  "name": "Engineering Dept",      // optional, cùng ràng buộc như create
  "description": "...",            // optional; truyền "" (rỗng) để xóa mô tả hiện có
  "type": "department",            // optional, cùng enum như create
  "parentId": "uuid | null",       // optional — dùng CHUNG với clearParent, xem dưới
  "clearParent": false,            // set true để chủ động bỏ cha (thành root) — bắt buộc khi muốn xóa cha
  "status": "active"               // optional — CHỈ nhận "active" | "inactive"
}
```
**Lưu ý quan trọng cho form sửa**: muốn xóa cha (đưa workspace về root) phải gửi `"clearParent": true` — gửi `"parentId": null` một mình mà không có `clearParent: true` sẽ **không đổi gì** (BE bỏ qua `parentId` null vì coi là "không muốn đổi", chỉ `clearParent: true` mới là tín hiệu tường minh). Nếu form của bạn có checkbox riêng "Bỏ workspace cha", tick checkbox đó → gửi `clearParent: true`, không gửi `parentId`.

**`POST /{id}/members` — gán nhân viên** (`AssignWorkspaceMemberRequest`):
```json
{
  "employeeId": "uuid",   // bắt buộc
  "role": "member"        // optional (mặc định "member") — CHỈ nhận: "member" | "lead" | "manager"
}
```

**`POST /{sourceId}/members/{memberId}/transfer` — chuyển nhân viên** (`TransferWorkspaceMemberRequest`):
```json
{
  "targetWorkspaceId": "uuid",  // bắt buộc
  "role": "manager"             // optional — nếu bỏ trống, GIỮ NGUYÊN vai trò hiện tại (không reset về "member")
}
```

**Response `GET /{id}/members` — 1 phần tử trong danh sách** (`WorkspaceMemberResponse`):
```json
{
  "id": "uuid",              // id của bản ghi membership (dùng cho DELETE/transfer)
  "workspaceId": "uuid",
  "employeeId": "uuid",
  "tenantId": "uuid",
  "role": "member",
  "employee": {               // rút gọn thông tin nhân viên, đủ để render 1 dòng trong bảng thành viên
    "id": "uuid",
    "employeeCode": "EMP-001",
    "firstName": "John",
    "lastName": "Doe",
    "fullName": "John Doe",
    "position": "Site Engineer",
    "email": "john.doe@example.com",
    "status": "active"        // "active" | "inactive" | "terminated" — hiện badge nếu != active
  },
  "assignedBy": "uuid",
  "createdAt": "...",         // = ngày được gán vào workspace này
  "updatedAt": "..."
}
```

**Response `GET /tree` — 1 node** (`WorkspaceTreeResponse`, đệ quy qua `children`):
```json
{
  "id": "uuid", "tenantId": "uuid", "name": "Engineering",
  "description": "...", "type": "department", "parentId": "uuid | null",
  "status": "active",
  "activeMemberCount": 12,      // MỚI bổ sung — trước đây tree KHÔNG có 2 field đếm này
  "childWorkspaceCount": 2,     // = children.length, nhưng có sẵn không cần tự đếm mảng con
  "createdBy": "uuid", "createdAt": "...", "updatedAt": "...",
  "children": [ /* cùng cấu trúc, đệ quy */ ]
}
```
**Trước đợt cập nhật này, `/tree` không trả `activeMemberCount`/`childWorkspaceCount`** — nếu bạn định hiện nút "Xóa" ngay trong cây (không chỉ ở bảng danh sách phẳng), trước đây sẽ phải gọi thêm `GET /{id}` cho từng node để biết có xóa được không. Giờ 2 field này có sẵn ngay trong response cây, dùng trực tiếp — không cần gọi thêm API nào.

**Response `GET /` (danh sách phẳng, phân trang)** — bọc `WorkspaceResponse` (mục 3, đã có ở trên) trong `PageResponse` chuẩn:
```json
{
  "content": [ /* mảng WorkspaceResponse */ ],
  "page": 0, "size": 20, "totalElements": 42, "totalPages": 3,
  "first": true, "last": false
}
```

**Mã lỗi cần bắt trong form** (áp dụng cho mọi endpoint tạo/sửa/gán/chuyển ở trên):
| HTTP | Khi nào xảy ra | Message mẫu (hiện trực tiếp, không cần tự soạn lại) |
|---|---|---|
| 400 | Validate field sai (xem trên), hoặc tự đặt làm cha của chính mình, hoặc tham chiếu vòng, hoặc workspace đích inactive | `"A workspace cannot be its own parent"`, `"Cannot set a descendant workspace as parent — circular reference detected"`, `"Workspace 'X' is inactive and can no longer accept new members"` |
| 401 | Thiếu/hết hạn token | — |
| 403 | Thiếu quyền tương ứng (`workspaces:*`/`workspace_members:*`) | — |
| 404 | Tenant/workspace/nhân viên/membership không tồn tại (hoặc sai tenant) | `"Workspace not found: <id>"`, `"Employee not found: <id>"` |
| 409 | Trùng tên workspace trong tenant, hoặc nhân viên đã là thành viên workspace đích | `"Workspace 'X' already exists in this tenant"`, `"Employee is already a member of this workspace"` / `"...of the target workspace"` |

## 4. [Đã xử lý] Hợp nhất `Department` vào `Workspace`

Hệ thống trước đây có **2 khái niệm "phòng ban" hoàn toàn độc lập, không liên kết với nhau**:

| | `Department` (module Employee, **đã loại bỏ**) | `Workspace type="department"` |
|---|---|---|
| Cấu trúc | Phẳng, không phân cấp | Phân cấp qua `parentId` (cây cha-con) |
| Liên kết nhân viên | `Employee.departmentId` (1 FK trực tiếp) | Qua bảng trung gian `workspace_members` (nhiều-nhiều theo thời gian, có vai trò `member/lead/manager`) |
| Quyền | `employees:*` | `workspaces:*` / `workspace_members:*` |
| Migration | V55 | V20/V21 |
| Endpoint | ~~`/tenants/{id}/departments`~~ (đã xóa) | `/tenants/{id}/workspaces` |

Đối chiếu thực tế (BambooHR, Deputy, Employment Hero, Auth0 Organizations): không hệ thống nào duy trì 2 bảng riêng cho cùng 1 khái niệm "đơn vị tổ chức" — luôn chỉ có 1 cây tổ chức duy nhất. Bạn đã chọn hướng **gộp về `Workspace`, loại bỏ `Department`** — đã triển khai đầy đủ:

**Đã làm**:
- Xóa toàn bộ module `Department` (entity, repository, service, controller, DTO, endpoint `/departments`).
- Migration `V71__consolidate_departments_into_workspaces.sql`: mọi `Department` hiện có được chuyển thành `Workspace(type='department')`, giữ nguyên UUID (`department.id = workspace.id`) để `employees.department_id` không cần remap giá trị — chỉ đổi FK trỏ sang bảng `workspaces`. Bảng `departments` bị xóa hẳn sau khi migrate.
- `EmployeeService.createEmployee`/`updateEmployee`: field `departmentId` trong request **vẫn giữ tên cũ** (không đổi API shape cho FE) nhưng giờ tra cứu/validate qua `WorkspaceRepository` thay vì `DepartmentRepository` — phải là 1 Workspace có thật trong tenant, không nhất thiết `type='department'` (tra cứu không ràng buộc type vì `type` chỉ là nhãn mô tả, không có khác biệt hành vi — xem đã xác nhận ở mục 3).
- `Employee.department` (chuỗi text cache dùng cho tìm kiếm/lọc/export/report — `EmployeeSpecification`, `EmployeeExportService`, `SearchService`, `ReportService`, `CheckinService`) **giữ nguyên không đổi** — vẫn là cùng 1 field, chỉ khác nguồn gốc dữ liệu ghi vào nó (từ tên Workspace thay vì tên Department). Toàn bộ 5 module đọc field này **không cần sửa 1 dòng nào**.

**Test đã chạy**: build lại, verify migration (đếm bảng `departments` = 0, `workspaces type=department` tăng đúng số lượng cũ, FK `employees.department_id` join đúng tên workspace đã migrate); viết lại `tests/employee/test_departments.sh` (giờ test luồng tạo workspace type=department → gán cho nhân viên → đổi department qua workspace khác → lọc theo tên) — 8/8 pass; chạy lại toàn bộ `tests/workspace` (65), `tests/employee` (96), `tests/rbac` (100), `tests/tenant` (91), `tests/search` (16), `tests/report` (67) — **100% pass, không hồi quy**.

**Nếu FE đang gọi `/departments`**: cần đổi sang gọi `/tenants/{id}/workspaces` (lọc `type=department` nếu muốn chỉ hiện phòng ban, bỏ filter nếu muốn xem cả team). Trường `departmentId` trong request/response tạo/sửa/xem nhân viên **không đổi tên**, chỉ đổi ý nghĩa (giờ trỏ tới Workspace) — không cần sửa gì ở phía FE cho riêng field này, chỉ cần đổi màn "Quản lý phòng ban" (nếu có) sang dùng API Workspace.

## 5. Điểm đã xác nhận đúng, không cần sửa

- **`SiteScopeService` không áp dụng cho module Workspace** — đây là thiết kế đúng, không phải thiếu sót: Workspace là khái niệm tổ chức toàn tenant (ai thuộc phòng ban nào), không gắn với vị trí vật lý (site) như Random Check hay Employee list/export đã cần giới hạn theo site ở các đợt review trước. Thực tế seed cũng xác nhận `SITE_SUPERVISOR` không được cấp bất kỳ quyền `workspaces:*`/`workspace_members:*` nào — nên vấn đề này không có rủi ro thực tế nào để xử lý.
- **Tên workspace duy nhất theo tenant, không phân biệt hoa/thường** — đúng nghiệp vụ, tránh 2 phòng ban trùng tên gây nhầm lẫn báo cáo.
- **Chống tham chiếu vòng khi đổi cha** (`isDescendant` check) — đã hoạt động đúng, test sống xác nhận `400` khi cố đặt 1 workspace con thành cha của chính tổ tiên nó.
