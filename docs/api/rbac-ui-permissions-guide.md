# Tài liệu bàn giao UI: Web vs App, ẩn/hiện theo vai trò, và hiển thị quyền — RBAC (Role & Permission)

> Cập nhật theo code đang chạy ngày 25/07/2026 (đợt 2). Đây **không phải** tài liệu API — mọi request/response chi tiết đã có ở `docs/api/rbac-api.md`, tài liệu này chỉ trả lời 4 câu hỏi khi dựng giao diện:
> 1. Tính năng này thuộc **Web** hay **App**, và nếu Web thì thuộc **Admin Console** (nội bộ FAMS) hay **Company Portal** (khách hàng)?
> 2. Với từng vai trò đăng nhập, phần tử nào **ẩn hẳn**, phần tử nào **hiện nhưng disable/readonly**, phần tử nào **hiện đầy đủ**?
> 3. **Quyền (permission) nên hiển thị trên giao diện như thế nào**, và màn nào dành cho ai?
> 4. **(Mới)** Màn "chọn người dùng" (autocomplete tạo công ty, quản lý nhân sự nền tảng) nên dựng như thế nào?

Phạm vi: 8 tính năng RBAC bạn yêu cầu (seed, danh sách role, tạo/sửa/xóa role tùy chỉnh, xem permission theo nhóm, gán/thu hồi role) + 3 phần bổ sung đã cài đặt thêm (gán role theo site, role tùy chỉnh cấp nền tảng, quản lý người dùng toàn hệ thống).

## 1. Kết luận nhanh: App hay Web?

Giống kết luận đã đưa ra cho tenant management: **toàn bộ 10 tính năng RBAC đều là Web-only, không có gì trên Mobile App.** Đây là nghiệp vụ back-office thuần túy (quản trị quyền hạn), app chỉ phục vụ nhân viên thao tác hàng ngày (chấm công, xem lịch, phản hồi random-check) và xử lý khóa tài khoản — không có màn "quản lý vai trò" nào hợp lý trên app, kể cả với owner khi họ dùng app thay vì web.

**Ngoại lệ nhỏ đáng cân nhắc**: `GET /roles/me` (xem quyền của chính mình) về mặt kỹ thuật app có thể gọi được — nhưng chỉ nên làm nếu app cần tự ẩn/hiện 1 nút chức năng nào đó dựa theo quyền thực tế của người dùng (ví dụ nút "Duyệt vi phạm" chỉ hiện nếu có quyền `violations:update`). Nếu app hiện tại chưa có màn nào cần phân quyền chi tiết như vậy, không cần tích hợp API này vào app.

## 2. Ba "mặt" giao diện (nhắc lại, áp dụng y hệt tenant management)

| Mặt giao diện | Người dùng | Đặc điểm |
|---|---|---|
| **Admin Console** (`fams-front-web-project`, route nội bộ) | Platform Admin, Platform Staff | Quản trị role hệ thống (read-only), role **cấp nền tảng** (CRUD, chỉ Platform Admin), xem role của tenant bất kỳ khi cần hỗ trợ |
| **Company Portal** (`fams-front-web-project`, route khách hàng) | Company Admin (chủ sở hữu hoặc người giữ quyền `roles:*`), nhân viên khác | Quản trị role tùy chỉnh của công ty mình, gán/thu hồi role cho nhân viên, xem quyền của chính mình |
| **Mobile App** | Mọi nhân viên | Không có màn RBAC nào (xem mục 1) |

## 3. Ma trận tổng hợp: Tính năng × Nền tảng × Vai trò

Ký hiệu: **Full** = thao tác đầy đủ · **View** = chỉ xem · **Ẩn** = không hiện trên UI (kể cả disable) · **—** = không áp dụng

| # | Tính năng | Endpoint chính | Admin Console (Platform Admin) | Admin Console (Platform Staff) | Company Portal — Company Admin | Company Portal — Nhân viên khác | Mobile App |
|---|---|---|---|---|---|---|---|
| 1 | Seed role/permission khi deploy | Flyway migration, không qua API | — (tự động lúc deploy, không có màn UI) | — | — | — | — |
| 2 | Danh sách role | `GET /roles` | Full (xem mọi tenant) | View (chỉ role hệ thống — không có `roles:*` mặc định) | Full (role hệ thống + role công ty mình) | Ẩn (trừ khi được cấp `roles:read`) | Ẩn |
| 3 | Xem chi tiết 1 role | `GET /roles/{id}` | Full | View (role hệ thống) | Full (role công ty mình) | Ẩn | Ẩn |
| 4 | Tạo role tùy chỉnh (công ty) | `POST /roles` (`tenantId` = công ty) | Full (hộ khi hỗ trợ) | Ẩn (không có `roles:create` mặc định) | Full | Ẩn | Ẩn |
| 5 | Sửa role tùy chỉnh (công ty) | `PUT /roles/{id}` | Full (hộ khi hỗ trợ) | Ẩn | Full | Ẩn | Ẩn |
| 6 | Xóa/Vô hiệu hóa role tùy chỉnh | `DELETE /roles/{id}`, `PUT ... isActive` | Full | Ẩn | Full | Ẩn | Ẩn |
| 7 | Xem permission theo nhóm | `GET /permissions` | Full | Full | Full (dùng để dựng form tạo/sửa role) | Ẩn (không cần — họ không tạo role) | Ẩn |
| 8 | Gán role cho user (tenant/site) | `POST /user-roles` | Full (hộ khi hỗ trợ) | Ẩn | Full | Ẩn | Ẩn |
| 9 | Thu hồi role | `DELETE /user-roles/{id}` | Full | Ẩn | Full | Ẩn | Ẩn |
| 10 | Role tùy chỉnh **cấp nền tảng** (`tenantId` bỏ trống) | `POST/PUT/DELETE /roles`, `/user-roles/platform` | Full | **Ẩn hoàn toàn** (không tự quản lý cấp quyền của chính mình) | Ẩn — không liên quan tới công ty | Ẩn | Ẩn |
| 11 | Danh mục người dùng toàn hệ thống (mới, mục 5) | `GET /users` | Full | Ẩn (không có quyền tương ứng mặc định) | Ẩn (không phải quy mô toàn hệ thống) | Ẩn | Ẩn |
| — | Xem quyền của chính mình ("Quyền của tôi") | `GET /roles/me` | View | View | View | **View** (mọi nhân viên đều nên thấy) | (tùy chọn, xem mục 1) |

## 4. Chi tiết ẩn/hiện theo vai trò

### 4.1 Role hệ thống (`isSystem = true`) — bất biến với TẤT CẢ mọi người

- Không bao giờ hiện nút Sửa/Xóa/Vô hiệu hóa, **kể cả Platform Admin** — backend chặn `400` ngay cả khi cố gọi API trực tiếp. FE nên disable/ẩn nút này dựa vào field `isSystem` trong response, không cần đoán theo role hiện tại của người dùng.
- Nên có 1 dòng chú thích nhỏ trong UI: "Vai trò hệ thống — áp dụng chung cho mọi công ty, chỉ thay đổi được qua bản cập nhật phần mềm."

### 4.2 Role tùy chỉnh của công ty (`isSystem = false`, có `tenantId`)

- Company Admin: toàn quyền CRUD trong công ty mình.
- Platform Admin: xem/sửa/xóa được (dùng khi hỗ trợ khách hàng) — nhưng **nên có cảnh báo UI rõ ràng** khi Platform Admin thao tác vào role của 1 công ty cụ thể (ví dụ banner "Bạn đang chỉnh sửa role của công ty X thay cho khách hàng"), tránh nhầm lẫn với role cấp nền tảng.
- Nhân viên khác trong công ty (không giữ `roles:*`): không thấy màn "Quản lý vai trò" trong menu — ẩn hẳn mục này, không chỉ disable.

### 4.3 Role tùy chỉnh cấp nền tảng (`isSystem = false`, `tenantId = null`)

- **Chỉ Platform Admin** — không phải Platform Staff, không phải Company Admin. Đây là màn quản trị cấu trúc quyền hạn nội bộ FAMS, không giao cho ai khác kể cả nhân viên vận hành cấp cao.
- Đặt trong 1 mục riêng của Admin Console, ví dụ "Quản lý vai trò nền tảng" — tách biệt hoàn toàn khỏi "Danh sách công ty" để tránh Platform Staff vô tình nhìn thấy cấu trúc phân quyền nội bộ.
- API tự động ẩn loại role này khỏi `GET /roles` với người không phải Platform Admin — nhưng FE vẫn nên tự kiểm tra `currentUser.isPlatformAdmin` trước khi render route, không dựa hoàn toàn vào việc "API không trả về nên không hiện" (defense in depth).

### 4.4 Gán role theo site — chỉ nên hiện khi thật sự cần

- Ô chọn "Phạm vi: Toàn công ty / Site cụ thể" chỉ nên hiện trong form gán role khi role được chọn có khả năng liên quan tới site (ví dụ `SITE_SUPERVISOR`, hoặc role tùy chỉnh mà công ty tự đặt cho mục đích site) — với `TENANT_ADMIN`/`HR_MANAGER` (vốn dĩ là toàn công ty), có thể ẩn ô này đi hoặc để mặc định "Toàn công ty" và disable, tránh gây hiểu nhầm là có thể giới hạn được các role này.
- Khi 1 user được gán NHIỀU role trong cùng công ty, có role không giới hạn — FE nên hiện rõ ràng: "Người này có quyền không giới hạn (qua vai trò X)" thay vì hiện danh sách site rời rạc gây hiểu nhầm là đang bị giới hạn.
- **Cập nhật 25/07/2026 (đợt 2)**: response giờ có sẵn field `sites: [{id, name}]` song song với `siteIds` — dùng field này để hiện tên site trực tiếp (ví dụ badge "Phụ trách: Site Quận 1, Site Quận 7"), không cần tự gọi thêm `GET /sites` rồi map tên theo id.

## 5. Gợi ý hiển thị quyền (permission) trên giao diện — cho từng đối tượng

Đây là phần bạn hỏi riêng. Có 3 màn hình khác nhau cần hiển thị quyền, phục vụ 3 mục đích khác nhau — không nên dùng chung 1 kiểu UI cho cả 3:

### 5.1 Form tạo/sửa role — chọn quyền (Company Admin + Platform Admin)

**Mục đích**: chọn tập hợp permission cho 1 role. Dữ liệu từ `GET /permissions` đã nhóm sẵn theo `resource`.

**Gợi ý UI**: danh sách **accordion/collapsible theo nhóm** (mỗi nhóm = 1 resource như "employees", "sites", "checkins"...), mỗi nhóm có:
- Checkbox "chọn tất cả trong nhóm" ở tiêu đề accordion (tick nếu tất cả permission trong nhóm đã được chọn, dấu gạch ngang nếu chọn dở).
- Bên trong là các checkbox riêng lẻ theo `action` (create/read/update/delete/list...), mỗi checkbox hiện kèm `description` từ API làm tooltip/phụ đề — không nên chỉ hiện tên kỹ thuật như `employees:create`, nên hiện nhãn dễ hiểu (ví dụ "Tạo nhân viên mới") lấy từ `description`.
- Mặc định đóng collapsible, mở nhóm nào có ít nhất 1 permission đã chọn (giúp người dùng thấy ngay role hiện có gì mà không cần mở hết ~15 nhóm).

Đây là màn phổ biến nhất trong thực tế (Notion "permission groups", GitHub "repository permissions" theo từng hạng mục) — không cần bảng ma trận phức tạp ở bước tạo/sửa vì lúc này chỉ thao tác trên **1 role**.

### 5.2 Trang so sánh vai trò (Company Admin) — tùy chọn, nên có nếu công ty có nhiều role tùy chỉnh

**Mục đích**: khi công ty có 3-4 role tùy chỉnh (ví dụ "Site Supervisor Miền Bắc", "Site Supervisor Miền Nam", "HR Trợ lý"...), Company Admin cần nhìn tổng quan role nào có quyền gì để tránh chồng chéo/thiếu sót — đây chính là mẫu "permission matrix" các hệ thống lớn hay dùng (Salesforce Permission Set comparison, Okta Application Assignment matrix).

**Gợi ý UI**: bảng ma trận — **hàng = permission** (gộp theo nhóm resource, có thể thu gọn), **cột = role** (mỗi role tùy chỉnh + có thể thêm cột role hệ thống để tham chiếu), ô giao nhau là dấu tick/trống. Dữ liệu lấy từ việc gọi `GET /roles/{id}` cho từng role rồi build bảng phía client (không cần API mới). Chỉ nên hiện trang này khi công ty có từ 2 role tùy chỉnh trở lên — với công ty chỉ dùng role hệ thống mặc định, trang này không có giá trị, không cần build ngay từ đầu (có thể làm sau).

### 5.3 "Quyền của tôi" — cho MỌI nhân viên (không phân biệt vai trò)

**Mục đích**: tự phục vụ (self-service) — nhân viên tự xem mình có quyền gì, đặc biệt hữu ích khi họ thấy 1 nút bị ẩn/disable và thắc mắc tại sao ("Sao tôi không sửa được thông tin nhân viên?"). Giảm số lượng yêu cầu hỗ trợ tới HR/Admin.

**Gợi ý UI**: 1 mục nhỏ trong "Hồ sơ cá nhân/Cài đặt tài khoản", gọi `GET /roles/me`, hiển thị:
```text
Vai trò của bạn tại [Tên công ty]: SITE_SUPERVISOR
Phạm vi: Site Quận 1, Site Quận 7   (hoặc "Toàn công ty" nếu siteIds rỗng)
Quyền được cấp:
  ✓ Xem danh sách nhân viên       ✓ Xem check-in
  ✓ Duyệt vi phạm                 ✗ Xóa nhân viên
  ...
```
- Nếu user thuộc nhiều công ty (multi-tenant), hiện dạng tab/dropdown chọn công ty rồi hiện quyền tương ứng — dữ liệu `GET /roles/me` đã trả về đủ cho mọi tenant trong 1 lần gọi, không cần gọi lại khi đổi tab.
- Đây nên là **read-only tuyệt đối** — không có nút sửa nào trên màn này, kể cả khi người xem là Company Admin xem quyền của chính họ.

## 6. Màn "Chọn người dùng" — autocomplete tạo công ty & quản lý nhân sự nền tảng (mới, 25/07/2026 đợt 2)

Đây là câu hỏi thứ 4 bạn hỏi riêng. API nền cho cả 2 nhu cầu dưới đây là **cùng 1 endpoint** `GET /api/v1/users` (chi tiết `docs/api/rbac-api.md` mục 7) — chỉ khác cách gọi.

### 6.1 Autocomplete chọn chủ sở hữu khi tạo công ty hộ khách hàng

**Vị trí**: Admin Console → "Tạo công ty" → ô "Chủ sở hữu".

**Gợi ý UI**: input dạng combobox/typeahead — gõ tới đâu gọi API tới đó (debounce ~300ms), gọi `GET /users?search=<text>&size=8`. Mỗi kết quả hiện: avatar (nếu có) + tên hiển thị + email làm phụ đề — giống hệt UI "mention" quen thuộc (Slack, Notion, Linear khi gõ `@`). Khi chọn xong, lưu `id` vào field `ownerUserId` của form, hiện lại tên đã chọn dạng "chip" thay vì để trống input.

**Xử lý trường hợp không tìm thấy**: nếu gõ hết mà không ra kết quả (người này chưa có tài khoản FAMS), hiện gợi ý rõ ràng: "Không tìm thấy tài khoản với từ khóa này — người được chỉ định làm chủ sở hữu phải đã đăng ký tài khoản trước" (đúng theo `resolveExistingOwner`, đây là gán trực tiếp chứ không phải lời mời — xem `tenant-api.md` mục 3).

### 6.2 Màn quản lý nhân sự nền tảng (Admin Console, Platform Admin only)

**Vị trí**: mục riêng trong Admin Console, ví dụ "Nhân sự FAMS" — tách biệt khỏi "Danh sách công ty" (đây là người của FAMS, không phải khách hàng).

**Gợi ý UI**: bảng danh sách chuẩn — cột: Tên, Email, Trạng thái tài khoản (`isActive`), Vai trò nền tảng hiện tại (cần gọi thêm hoặc hiển thị dạng badge nếu BE bổ sung sau), Lần đăng nhập gần nhất (`lastLoginAt`), nút hành động "Gán vai trò nền tảng".
- Bộ lọc mặc định: `isPlatformAdmin=true` (chỉ hiện người đã có cờ platform-admin) — nhưng nên có toggle "Hiện tất cả người dùng" gọi lại không kèm filter này, phòng trường hợp Platform Admin muốn tìm 1 nhân viên FAMS chưa từng được cấp gì để gán quyền lần đầu.
- Sắp xếp mặc định theo `lastLoginAt` giảm dần (người hoạt động gần nhất lên đầu) — giống Auth0/Okta Users table.
- Nút "Gán vai trò nền tảng" mở modal chọn 1 role cấp nền tảng (danh sách lấy từ `GET /roles` không kèm `tenantId`, gọi bằng Platform Admin nên thấy cả role tùy chỉnh — mục 8 tài liệu API), gọi `POST /user-roles/platform`.

**Lưu ý quan trọng**: bảng này liệt kê *tài khoản*, không phải *người đang giữ 1 role cụ thể*. Muốn biết "ai đang giữ role Support Tier 2", hiện tại phải vào chi tiết role đó xem `assignmentCount` rồi tra thủ công qua audit log/DB — **chưa có API "liệt kê user theo role"**. Nếu đây là nhu cầu thường xuyên (ví dụ cần audit định kỳ ai đang có quyền gì), báo lại để bổ sung một API riêng.

## 7. Sơ đồ nav đề xuất

```text
ADMIN CONSOLE (fams-front-web-project /admin) — Platform Admin
├── Danh sách công ty → chi tiết → tab "Vai trò & quyền" (view-only, hỗ trợ khách hàng)
├── Tạo công ty hộ khách hàng → ô "Chủ sở hữu" dùng autocomplete GET /users (mục 6.1)
├── Nhân sự FAMS (mới — mục 6.2)
│   ├── Danh sách người dùng nền tảng (GET /users?isPlatformAdmin=true, sort/filter)
│   └── Gán vai trò nền tảng cho 1 người (qua /user-roles/platform)
└── Quản lý vai trò nền tảng (chỉ Platform Admin thấy mục này)
    ├── Danh sách role cấp nền tảng (PLATFORM_ADMIN, PLATFORM_STAFF, + role tự tạo)
    ├── Tạo/Sửa role cấp nền tảng (form chọn quyền — mục 5.1)
    └── Gán role cấp nền tảng cho nhân viên FAMS (qua /user-roles/platform)

COMPANY PORTAL (fams-front-web-project, sau khi chọn công ty)
├── [Company Admin] Quản lý vai trò
│   ├── Danh sách role (hệ thống + tùy chỉnh của công ty)
│   ├── Tạo/Sửa role tùy chỉnh (form chọn quyền — mục 5.1)
│   ├── So sánh vai trò (ma trận — mục 5.2, tùy chọn)
│   └── Vô hiệu hóa/Xóa role
├── [Company Admin] Danh sách nhân viên → "Gán vai trò" / "Đổi vai trò" / "Thu hồi vai trò"
│   └── Nếu vai trò chọn là site-liên-quan: hiện ô chọn phạm vi site (mục 4.4)
└── [Mọi nhân viên] Hồ sơ cá nhân → "Quyền của tôi" (mục 5.3, read-only)

MOBILE APP (fams-front-app-project)
└── Không có màn RBAC nào (xem mục 1)
```

## 8. Checklist bàn giao frontend

- [ ] Route Admin Console (`/admin/*`) tự kiểm tra `currentUser.isPlatformAdmin` trước khi render, không chỉ dựa vào API trả rỗng.
- [ ] Nút Sửa/Xóa/Vô hiệu hóa ẩn hoàn toàn khi `role.isSystem === true`, với MỌI vai trò kể cả Platform Admin.
- [ ] Mục "Quản lý vai trò nền tảng" trong Admin Console **chỉ hiện với Platform Admin**, không hiện với Platform Staff dù họ đăng nhập được vào Admin Console.
- [ ] Form tạo/sửa role dùng accordion theo nhóm resource (mục 5.1), hiện `description` thay vì tên kỹ thuật permission.
- [ ] Ô chọn phạm vi site trong form gán role: ẩn/disable với role rõ ràng toàn-công-ty (`TENANT_ADMIN`, `HR_MANAGER`), chỉ hiện có ý nghĩa với `SITE_SUPERVISOR`/role tùy chỉnh liên quan site.
- [ ] Màn "Quyền của tôi" (mục 5.3) làm cho MỌI vai trò, kể cả nhân viên thường — đây là màn duy nhất trong toàn bộ RBAC mà nhân viên thường được thấy.
- [ ] Trang so sánh vai trò (mục 5.2) là tùy chọn — chỉ build nếu công ty có ≥ 2 role tùy chỉnh, có thể để version sau nếu ưu tiên thời gian.
- [ ] Không có gì trong RBAC lên Mobile App — nếu backlog app có nhắc tới màn "phân quyền", xác nhận lại với sản phẩm trước khi làm, vì hiện tại không có API/nhu cầu tương ứng ngoài `GET /roles/me` (tùy chọn, xem mục 1).
- [ ] Ô "Chủ sở hữu" trong form tạo công ty: đổi từ input UUID thô sang autocomplete `GET /users?search=` (mục 6.1) — vẫn gửi UUID lên BE, chỉ đổi trải nghiệm nhập liệu.
- [ ] Màn "Nhân sự FAMS" (mục 6.2): mặc định lọc `isPlatformAdmin=true`, có toggle xem tất cả; sắp xếp mặc định theo `lastLoginAt` giảm dần.
- [ ] Badge "Phụ trách: Site X, Site Y" ở màn nhân viên/quyền dùng field `sites[]` (đã có tên sẵn) thay vì tự gọi thêm API resolve `siteIds`.
- [ ] Nút Xóa role trong danh sách disable khi `assignmentCount > 0`, hiện tooltip số người đang giữ.
- [ ] Nếu công ty có supervisor bị giới hạn nhiều site cùng lúc, màn "Lịch random-check" (`scheduled-checks`) bắt buộc họ chọn 1 site cụ thể trước khi xem danh sách — không hiện được view gộp nhiều site trong 1 lần gọi (giới hạn API hiện tại, xem `rbac-api.md` mục 6.3).
