# Tài liệu bàn giao UI: Web vs App và ẩn/hiện theo vai trò — Quản lý Workspace

> Cập nhật theo code đang chạy ngày 26/07/2026. Đây **không phải** tài liệu API — mọi request/response chi tiết đã có ở `docs/api/workspace-management-api.md`, tài liệu này chỉ trả lời 2 câu hỏi khi dựng giao diện:
> 1. Tính năng này thuộc **Web** hay **App**?
> 2. Với từng vai trò đăng nhập, phần tử nào **ẩn hẳn**, phần tử nào **hiện nhưng disable**, phần tử nào **hiện đầy đủ**?

Phạm vi: 5 tính năng workspace bạn yêu cầu + 1 tính năng bổ sung (xóa workspace).

## 1. Kết luận nhanh: App hay Web?

**Toàn bộ 100% Web-only** — giống nhóm tính năng RBAC/Tenant đã review trước đây, khác với nhóm Employee (có cả App). Lý do: quản lý workspace là công cụ tổ chức nội bộ dành cho HR/Admin (dựng sơ đồ công ty, gán/chuyển nhân sự) — nhân viên thường **không tự thao tác** với workspace của chính mình, họ chỉ được thêm/chuyển bởi HR. Nếu nhân viên cần *xem* mình đang thuộc workspace nào, đó là dữ liệu **đọc** nằm trong màn "Chi tiết nhân viên" hoặc "Hồ sơ của tôi" (đã có ở `employee-ui-permissions-guide.md` mục 5b, dùng field `workspaces` trong `GET /employees/{id}` hoặc tương đương cho tự xem) — không phải 1 màn hình workspace riêng trên app.

| Tính năng | Nền tảng |
|---|---|
| Tạo workspace | **Web only** |
| Danh sách workspace (tìm/lọc) | **Web only** |
| Cập nhật workspace | **Web only** |
| Gán nhân viên vào workspace | **Web only** |
| Chuyển nhân viên sang workspace khác | **Web only** |
| Xóa workspace (mới) | **Web only** |

**Không có phần nào cần dựng trên Mobile App cho tính năng này.** Kể cả sau khi hợp nhất `Department` vào `Workspace` (mục 4 tài liệu API) — việc chọn "phòng ban" cho nhân viên vẫn là 1 phần của màn **Tạo/Sửa nhân viên** (Web, Company Portal), không phát sinh màn hình hay luồng App nào mới.

## 1.1 Ảnh hưởng của việc hợp nhất `Department` vào `Workspace` lên giao diện

Đây là thay đổi bạn cần biết khi dựng UI, không chỉ là chi tiết backend:

- **Nếu FE trước đây có màn "Quản lý phòng ban" riêng gọi `/tenants/{id}/departments`**: màn đó phải xóa hoặc chuyển hẳn sang gọi `/tenants/{id}/workspaces` (đã trình bày ở mục 2-5 tài liệu này) — API `/departments` không còn tồn tại nữa (đã xóa, gọi vào sẽ ra `404` do route không khớp).
- **Dropdown "Phòng ban" trong màn Tạo/Sửa nhân viên** (thuộc `employee-ui-permissions-guide.md`, mục "Tạo nhân viên thủ công"/"Cập nhật nhân viên"): trước đây populate từ `GET /tenants/{id}/departments`, giờ phải đổi sang `GET /tenants/{id}/workspaces?type=department` (lọc đúng loại "phòng ban", bỏ qua "team" trong dropdown này để không gây nhầm lẫn — dù backend không ép buộc điều này, nhưng UX nên tách rõ). Field gửi lên vẫn tên `departmentId` như cũ, **không đổi tên**, chỉ đổi nguồn dữ liệu options trong dropdown.
- **Field `department` (chuỗi text) trong bảng danh sách nhân viên, filter, export** — hoàn toàn không đổi, vẫn hiển thị đúng tên phòng ban như trước, FE không cần sửa gì ở các màn này.
- **Nếu người dùng đổi tên 1 Workspace type=department** (qua màn Workspace) **sau khi** đã có nhân viên gán vào đó qua `departmentId`: tên hiển thị trong `Employee.department` (cache) sẽ **không tự cập nhật** cho tới lần nhân viên đó được sửa lại — đây là hành vi kế thừa y hệt từ `Department` cũ (không phải lỗi mới phát sinh do hợp nhất), đã ghi nhận trong tài liệu Employee. Không cần xử lý gì thêm ở FE, chỉ cần biết để không báo là "bug" khi thấy tên lệch tạm thời.

## 2. Ma trận tổng hợp: Tính năng × Vai trò (Company Portal)

Ký hiệu: **Full** = thao tác đầy đủ · **View** = chỉ xem · **Ẩn** = không hiện nút/màn (không chỉ disable).

| # | Tính năng | Endpoint chính | TENANT_ADMIN | HR_MANAGER | SITE_SUPERVISOR | Nhân viên thường |
|---|---|---|---|---|---|---|
| 1 | Tạo workspace | `POST /workspaces` | Full | **Full** (mới cấp — mục 2.4 API doc) | Ẩn | Ẩn |
| 2 | Danh sách/Cây workspace | `GET /workspaces`, `/tree` | Full | Full | Ẩn | Ẩn |
| 3 | Cập nhật workspace | `PUT /workspaces/{id}` | Full | **Full** (mới cấp) | Ẩn | Ẩn |
| 4 | Xóa workspace (mới) | `DELETE /workspaces/{id}` | **Full** | Ẩn (cố ý — xem giải thích dưới) | Ẩn | Ẩn |
| 5 | Gán nhân viên vào workspace | `POST /workspaces/{id}/members` | Full | Full | Ẩn | Ẩn |
| 6 | Danh sách thành viên | `GET /workspaces/{id}/members` | Full | Full | Ẩn | Ẩn |
| 7 | Gỡ nhân viên khỏi workspace | `DELETE .../members/{id}` | Full | Full | Ẩn | Ẩn |
| 8 | Chuyển nhân viên | `POST .../members/{id}/transfer` | Full | Full | Ẩn | Ẩn |

**Vì sao SITE_SUPERVISOR ẩn hoàn toàn**: theo seed mặc định, vai trò này không được cấp bất kỳ quyền `workspaces:*`/`workspace_members:*` nào — workspace là khái niệm tổ chức toàn công ty, không thuộc phạm vi quản lý của 1 giám sát viên site cụ thể. Không cần logic ẩn/hiện phức tạp theo site ở đây (khác với Employee/Random Check, nơi SITE_SUPERVISOR *có* quyền nhưng bị giới hạn phạm vi).

**Vì sao nút "Xóa workspace" chỉ hiện cho TENANT_ADMIN, không cho HR_MANAGER** dù HR_MANAGER giờ đã tạo/sửa được: xóa là hành động khó đảo ngược nhất trong nhóm này (dù có chặn khi còn thành viên/con), nên giữ ở bậc quyền cao nhất — cùng logic đã áp dụng cho việc xóa Role ở đợt review RBAC trước. Nếu sau này bạn muốn mở quyền xóa cho HR_MANAGER, chỉ cần báo lại, đây là 1 dòng migration.

## 3. Chi tiết ẩn/hiện và các trạng thái nút cần lưu ý

### 3.1 Nút "Xóa" trên từng dòng workspace (Company Portal)

- **Hiện nhưng disable + tooltip giải thích** khi `activeMemberCount > 0` hoặc `childWorkspaceCount > 0` — ví dụ: "Không thể xóa: còn 12 nhân viên. Hãy chuyển hết nhân viên sang workspace khác trước." Đây là UX tốt hơn "ẩn nút" vì người dùng cần biết TẠI SAO không xóa được, không chỉ là "không có nút".
- **Enable + xác nhận (confirm dialog)** khi cả 2 field đều = 0.
- Dùng đúng 2 field `activeMemberCount`/`childWorkspaceCount` đã có sẵn trong response `GET /workspaces` — không cần gọi thêm API để kiểm tra trước khi hiện/ẩn nút.

### 3.2 Form "Gán nhân viên" / "Chuyển nhân viên" — workspace đích inactive

- Trong dropdown chọn workspace đích (cả màn Gán và màn Chuyển), **lọc bỏ hoặc hiện mờ + nhãn "(Ngừng hoạt động)"** cho các workspace có `status = "inactive"` — API giờ đã chặn ở backend (`400`), nhưng chặn sớm ở FE giúp người dùng không phải submit rồi mới biết lỗi.
- Nếu người dùng vẫn chọn được (ví dụ dropdown không lọc kỹ) và bị `400`, hiện đúng message backend trả về: `"Workspace 'X' is inactive and can no longer accept new members"` — không cần tự soạn message khác, backend đã viết rõ ràng, có tên workspace.

### 3.3 Badge/chú thích trạng thái workspace trong danh sách

- `status = "active"` → không cần badge đặc biệt (mặc định).
- `status = "inactive"` → hiện badge xám "Ngừng hoạt động", và nên hiện chú thích nhỏ nếu `activeMemberCount > 0`: "Vẫn còn N nhân viên trong workspace đã ngừng hoạt động — cân nhắc chuyển họ sang nơi khác." (đây là trạng thái hợp lệ về mặt dữ liệu — inactive chỉ chặn *thêm mới*, không tự động gỡ người cũ — nhưng đáng cảnh báo cho HR).

### 3.4 Cây tổ chức (`/tree`)

- Node nào có `status = "inactive"` nên hiện mờ đi (opacity thấp hơn) trong cây, không xóa khỏi cây — vẫn cần thấy để biết cơ cấu cũ, chỉ phân biệt trực quan với các node đang hoạt động.
- **Mới bổ sung**: mỗi node trong `/tree` giờ có sẵn `activeMemberCount`/`childWorkspaceCount` (trước đây chỉ `GET /workspaces` phẳng mới có 2 field này). Nếu bạn dựng nút "Xóa" ngay trên từng node của cây (không chỉ ở bảng danh sách), dùng trực tiếp 2 field này để enable/disable — không cần gọi thêm `GET /{id}` cho từng node.

## 4. Sơ đồ nav đề xuất

```text
COMPANY PORTAL (fams-front-web-project) — TENANT_ADMIN / HR_MANAGER
└── Cơ cấu tổ chức (Workspaces)
    ├── Danh sách (bảng) hoặc Cây (tree view) — toggle 2 chế độ xem
    │   ├── Nút "Tạo workspace" (chọn cha nếu muốn phân cấp)
    │   ├── Filter: tìm theo tên, lọc trạng thái active/inactive, lọc loại department/team
    │   └── Click 1 dòng → Chi tiết
    │       ├── Sửa tên/mô tả/loại/trạng thái/cha
    │       ├── Danh sách thành viên hiện tại → nút "Gỡ" từng người, nút "Chuyển" từng người
    │       ├── Nút "Thêm thành viên" → chọn nhân viên + vai trò (member/lead/manager)
    │       └── Nút "Xóa workspace" — chỉ TENANT_ADMIN thấy, disable nếu còn thành viên/con
    └── (SITE_SUPERVISOR và nhân viên thường không thấy mục "Cơ cấu tổ chức" trong menu)

MOBILE APP — không có màn nào cho tính năng này
```

## 5. Checklist bàn giao frontend

- [ ] Route "Cơ cấu tổ chức" trên Company Portal **ẩn hoàn toàn** với ai không có quyền `workspaces:*` — không chỉ disable, không hiện trong menu.
- [ ] Sau đợt sửa quyền này, **HR_MANAGER giờ thấy được nút "Tạo workspace" và "Sửa"** — nếu FE trước đây đã tự ẩn cứng 2 nút này chỉ cho TENANT_ADMIN (không dựa vào permission API trả về), cần sửa lại để đọc đúng theo quyền thật (`GET /roles/me` hoặc permission set trong JWT), không hardcode theo tên role.
- [ ] Nút "Xóa workspace" chỉ hiện cho TENANT_ADMIN — disable kèm tooltip khi còn thành viên/workspace con, dùng field `activeMemberCount`/`childWorkspaceCount` có sẵn trong response, không cần gọi thêm API kiểm tra.
- [ ] Dropdown chọn workspace đích khi Gán/Chuyển nhân viên nên lọc hoặc đánh dấu rõ các workspace `inactive` — API đã chặn cứng ở backend nếu FE bỏ sót bước lọc này.
- [ ] Không dựng bất kỳ màn hình workspace nào trên Mobile App — nếu nhân viên cần xem workspace của chính mình, dùng field `workspaces` sẵn có trong dữ liệu hồ sơ cá nhân (xem `employee-ui-permissions-guide.md`), không gọi thẳng API `/workspaces/*` từ app (nhân viên thường không có quyền, sẽ bị `403`).
- [ ] Cây tổ chức (`/tree`) nên hiện mờ các node `inactive` thay vì ẩn hẳn, để giữ ngữ cảnh cơ cấu cũ khi HR cần tham chiếu lại — và giờ đã có `activeMemberCount`/`childWorkspaceCount` ngay trong response cây, dùng luôn không cần gọi thêm API.
- [ ] Nếu có màn "Quản lý phòng ban" riêng gọi `/departments` — **xóa hoặc chuyển hẳn sang `/workspaces`**, API cũ không còn tồn tại (mục 1.1).
- [ ] Dropdown "Phòng ban" trong màn Tạo/Sửa nhân viên phải đổi nguồn dữ liệu sang `GET /workspaces?type=department` — field gửi lên vẫn là `departmentId` (không đổi tên), chỉ đổi options lấy từ đâu (mục 1.1).
