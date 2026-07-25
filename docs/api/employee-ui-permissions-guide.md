# Tài liệu bàn giao UI: Web vs App và ẩn/hiện theo vai trò — Quản lý Nhân viên

> Cập nhật theo code đang chạy ngày 25/07/2026. Đây **không phải** tài liệu API — mọi request/response chi tiết đã có ở `docs/api/employee-management-api.md`, tài liệu này chỉ trả lời 2 câu hỏi khi dựng giao diện:
> 1. Tính năng này thuộc **Web** hay **App**, và nếu Web thì thuộc **Admin Console** (nội bộ FAMS) hay **Company Portal** (khách hàng)?
> 2. Với từng vai trò đăng nhập, phần tử nào **ẩn hẳn**, phần tử nào **hiện nhưng disable/readonly**, phần tử nào **hiện đầy đủ**?

Phạm vi: 9 tính năng nhân viên bạn yêu cầu + 1 tính năng bổ sung (mời nhân viên nền tảng qua email).

## 1. Kết luận nhanh: App hay Web?

Khác với các đợt review trước (tenant management, RBAC — hoàn toàn Web-only), nhóm tính năng nhân viên lần này **có cả phần thuộc Web lẫn phần thuộc App** — vì bản thân người được mời/quản lý ở đây (nhân viên công ty) chính là đối tượng dùng App hàng ngày.

| Tính năng | Nền tảng |
|---|---|
| Mời nhân viên bằng email (gửi lời mời) | **Web only** — thao tác quản trị, HR/Admin dùng web |
| **Chấp nhận lời mời** | **CẢ Web lẫn App** — người được mời có thể bấm link trong email trên điện thoại và chấp nhận ngay trong app, hoặc mở trên trình duyệt |
| Hủy lời mời | **Web only** |
| Danh sách nhân viên (xem toàn bộ công ty) | **Web only** — màn quản trị nhân sự |
| Xem chi tiết nhân viên (của người khác) | **Web only** |
| **Xem hồ sơ của chính mình** (tự xem role/assignment/Face ID của bản thân) | **App** (chính) + Web (phụ) — nhân viên tự kiểm tra lịch làm việc/trạng thái Face ID của mình ngay trên app, không cần vào web |
| Tạo nhân viên thủ công | **Web only** |
| Cập nhật thông tin nhân viên (do HR sửa) | **Web only** |
| **Tự cập nhật hồ sơ cá nhân** (nhân viên tự sửa số điện thoại/avatar...) | **App** (chính) — đây là tính năng "hồ sơ cá nhân" đã có sẵn ở module Auth (`auth-api.md`), KHÔNG phải `PUT /employees/{id}` (API đó dành cho HR sửa, không dành cho nhân viên tự sửa hồ sơ chính mình) |
| Tạm ngừng/nghỉ việc nhân viên | **Web only** |
| Import Excel | **Web only** |
| Export Excel | **Web only** |
| Mời nhân viên nền tảng qua email (mới) | **Web only** (Admin Console) |

**Điểm quan trọng cần lưu ý cho App**: app **không có màn "Danh sách nhân viên"** kiểu bảng quản trị (đó là công cụ của HR/Admin) — app chỉ có 2 việc liên quan tới nhân viên: (1) chấp nhận lời mời lúc onboarding lần đầu, (2) xem/tự quản lý hồ sơ **của chính mình** sau khi đã là thành viên. Không nhầm với `GET /employees/{id}` (API đó là của HR xem người khác, cần quyền `employees:read`, nhân viên thường gọi sẽ bị `403`).

## 2. Ba "mặt" giao diện

| Mặt giao diện | Người dùng | Đặc điểm |
|---|---|---|
| **Admin Console** (`fams-front-web-project`, route nội bộ) | Platform Admin | Mời/quản lý nhân sự nền tảng FAMS |
| **Company Portal** (`fams-front-web-project`, route khách hàng) | HR/Admin công ty | Toàn bộ nghiệp vụ quản lý nhân viên: mời, danh sách, chi tiết, tạo, sửa, tạm ngừng, import/export |
| **Mobile App** (`fams-front-app-project`) | Nhân viên (kể cả người mới chưa vào công ty nào) | Chấp nhận lời mời, xem hồ sơ/lịch làm việc/Face ID của chính mình |

## 3. Ma trận tổng hợp: Tính năng × Nền tảng × Vai trò

Ký hiệu: **Full** = thao tác đầy đủ · **View** = chỉ xem · **Ẩn** = không hiện · **—** = không áp dụng

| # | Tính năng | Endpoint chính | Company Portal — HR/Admin | Company Portal — Nhân viên khác | Admin Console (Platform Admin) | Mobile App |
|---|---|---|---|---|---|---|
| 1 | Mời nhân viên qua email | `POST .../invitations` | Full | Ẩn | — | Ẩn |
| 2 | Chấp nhận lời mời | `POST /invitations/accept` | — | — (chưa phải thành viên) | — | **Full** (cùng Web) |
| 3 | Hủy lời mời | `DELETE .../invitations/{id}` | Full | Ẩn | — | Ẩn |
| 4 | Danh sách nhân viên | `GET .../employees` | Full (theo site nếu bị giới hạn) | Ẩn | — | Ẩn |
| 5 | Chi tiết nhân viên (người khác) | `GET .../employees/{id}` | Full | Ẩn | — | Ẩn |
| 5b | Hồ sơ của chính mình | `GET /auth/me`, `GET /roles/me` | View (xem được luôn qua chi tiết) | **View** (chỉ của mình) | — | **Full** (màn chính) |
| 6 | Tạo nhân viên thủ công | `POST .../employees` | Full | Ẩn | — | Ẩn |
| 7 | Cập nhật nhân viên (HR sửa người khác) | `PUT .../employees/{id}` | Full | Ẩn | — | Ẩn |
| 7b | Tự cập nhật hồ sơ cá nhân | `PUT /auth/me` (module Auth, không phải employees) | — | **Full** (của chính mình) | — | **Full** |
| 8 | Tạm ngừng/nghỉ việc | `PATCH .../employees/{id}/status` | Full | Ẩn | — | Ẩn |
| 9 | Import Excel | `POST .../employees/import` | Full | Ẩn | — | Ẩn |
| 10 | Export Excel | `GET .../employees/export` | Full (theo site nếu bị giới hạn) | Ẩn | — | Ẩn |
| 11 | Mời nhân viên nền tảng (mới) | `POST /platform/invitations` | Ẩn — không liên quan công ty | Ẩn | **Full** | Ẩn |

## 4. Chi tiết ẩn/hiện theo vai trò

### 4.1 Company Portal — HR/Admin

- Quyền dựa trên permission `employees:create/read/update/delete/list` — trong seed mặc định, `TENANT_ADMIN` và `HR_MANAGER` có đủ các quyền này, `SITE_SUPERVISOR` có `employees:read/list` (xem, không tạo/sửa/xóa).
- Nếu tài khoản bị **giới hạn theo site** (xem `rbac-api.md` mục 6): danh sách/chi tiết/export tự động chỉ hiện nhân viên thuộc site được giao — FE không cần tự lọc thêm, nhưng nên hiện chú thích nhỏ "Chỉ hiện nhân viên thuộc site bạn phụ trách" để tránh hiểu nhầm là "toàn bộ công ty chỉ có bấy nhiêu người".
- Nút "Mời qua email" và "Tạo thủ công" cùng hiện trong màn danh sách — xem `employee-management-api.md` mục 1.1 để biết dùng cái nào cho đối tượng nào.

### 4.2 Company Portal — Nhân viên khác (không phải HR/Admin)

- **Không thấy** menu "Quản lý nhân viên" — ẩn hẳn, không chỉ disable.
- Chỉ thấy được thông tin **của chính mình** thông qua màn "Hồ sơ cá nhân" (dùng API Auth, không phải Employee API).

### 4.3 Mobile App

- **Màn "Chấp nhận lời mời"**: nhân viên bấm link trong email mời trên điện thoại → mở app (deep link) hoặc trình duyệt → điền mật khẩu (nếu là tài khoản mới) → vào thẳng app. Đây là màn onboarding duy nhất liên quan tới nhân viên mà app cần dựng.
- **Màn "Hồ sơ của tôi"**: xem tên/vị trí/phòng ban, trạng thái Face ID, vai trò hiện tại — đều là dữ liệu **của chính người dùng**, gọi `GET /auth/me` + `GET /roles/me`, không gọi `GET /employees/{id}` (API đó dành cho HR, đòi quyền `employees:read` mà nhân viên thường không có, gọi sẽ bị `403`).
- **Không có** màn danh sách nhân viên, tạo/sửa/tạm ngừng nhân viên, import/export — đây đều là công cụ quản trị, không thuộc app.

## 5. Sơ đồ nav đề xuất

```text
COMPANY PORTAL (fams-front-web-project) — HR/Admin
├── Danh sách nhân viên
│   ├── Nút "Tạo thủ công" (hồ sơ không cần đăng nhập)
│   ├── Nút "Mời qua email" (gửi lời mời, tạo tài khoản khi họ chấp nhận)
│   ├── Nút "Import Excel" (bulk tạo thủ công, không gửi email)
│   ├── Nút "Export Excel" (theo bộ lọc đang áp dụng)
│   └── Click 1 dòng → Chi tiết: hồ sơ + workspace + assignment + role + Face ID
├── Danh sách lời mời đang chờ → "Hủy lời mời"
└── (Nhân viên khác không thấy mục này)

ADMIN CONSOLE (fams-front-web-project /admin) — Platform Admin
└── Nhân sự FAMS → "Mời nhân sự nền tảng qua email" (mục 11 — khác hoàn toàn nhân viên công ty)

MOBILE APP (fams-front-app-project)
├── Màn "Chấp nhận lời mời" (mở từ link email, 1 lần lúc onboarding)
└── "Hồ sơ của tôi" trong menu tài khoản — xem thông tin/vai trò/Face ID của chính mình
```

## 6. Checklist bàn giao frontend

- [ ] Route "Quản lý nhân viên" trên Company Portal **ẩn hoàn toàn** với ai không có quyền `employees:*` — không chỉ disable nút.
- [ ] Màn "Chấp nhận lời mời" phải hoạt động được ở **cả web lẫn app** — dùng chung 1 endpoint (`POST /invitations/accept`), chỉ khác giao diện.
- [ ] Không nhầm "Hồ sơ của tôi" (app, dữ liệu của chính người dùng, dùng API Auth) với "Chi tiết nhân viên" (web, HR xem người khác, dùng API Employee) — 2 API khác nhau dù nhìn tương tự nhau trên UI.
- [ ] Nút "Tạo thủ công" và "Mời qua email" cùng hiện, có label rõ ràng phân biệt mục đích (xem `employee-management-api.md` mục 1.1).
- [ ] Sau khi import Excel, cân nhắc gợi ý "Gửi lời mời cho các nhân viên vừa import có email" (chưa có bulk action, làm thủ công từng người qua nút Mời).
- [ ] Export/Danh sách/Chi tiết tự động theo site nếu tài khoản bị giới hạn — không cần FE tự lọc thêm.
- [ ] Danh sách lời mời không còn trả `token` — chỉ đọc token từ response lúc gửi lời mời (`POST`), không đọc từ danh sách.
- [ ] Màn "Mời nhân sự nền tảng" (Admin Console) và "Mời nhân viên công ty" (Company Portal) là 2 màn hoàn toàn tách biệt, không dùng chung component/API.
