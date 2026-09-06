# Tài liệu bàn giao UI: Web vs App và ẩn/hiện theo vai trò — Quản lý công ty (Tenant)

> Cập nhật theo code đang chạy ngày 24/07/2026. Đây **không phải** tài liệu API mới — mọi request/response chi tiết đã có ở `docs/api/tenant-api.md`, tài liệu này chỉ trả lời 2 câu hỏi khi dựng giao diện:
> 1. Tính năng này thuộc **Web** hay **App**, và nếu Web thì thuộc **Admin Console** (nội bộ FAMS) hay **Company Portal** (khách hàng)?
> 2. Với từng vai trò đăng nhập, phần tử nào trên giao diện phải **ẩn hẳn**, phần tử nào **hiện nhưng disable/readonly**, phần tử nào **hiện đầy đủ**?

Phạm vi: 6 tính năng tenant vừa rà soát/bổ sung — tạo công ty, sửa hồ sơ, cấu hình giao diện/định dạng, IP whitelist, quản lý gói dịch vụ, gán subscription lúc tạo.

## 1. Ba "mặt" giao diện — nguyên tắc phân bổ

Giống cách các SaaS B2B thật (Slack, Notion, HubSpot, GitHub Enterprise) tách biệt 3 mặt, **không dùng chung 1 layout ẩn/hiện menu bằng CSS** — vì đó là cách dễ lộ thao tác nhạy cảm nếu FE có bug hoặc user đọc network tab:

| Mặt giao diện | Repo | Người dùng | Đặc điểm |
|---|---|---|---|
| **Admin Console** | `fams-front-web-project` (khu vực route riêng, ví dụ `/admin/*`) | Platform Admin, Platform Staff — **nhân viên nội bộ FAMS** | Toàn quyền nhìn thấy mọi tenant; không phải "khách hàng" nên không có khái niệm "công ty của tôi" |
| **Company Portal** | `fams-front-web-project` (route khách hàng, ví dụ `/company/*` hoặc `/settings/*`) | Tenant Owner (chủ công ty) + nhân viên khác trong tenant (tùy tính năng) | Chỉ thấy dữ liệu của **chính tenant mình thuộc về** |
| **Mobile App** | `fams-front-app-project` | Mọi nhân viên (kể cả owner khi dùng app) | Chỉ phục vụ tác vụ hàng ngày: chấm công, xem lịch, phản hồi random-check, đăng nhập/khóa tài khoản |

**Kết luận nhanh cho cả 6 tính năng đợt này: không có tính năng nào thuộc Mobile App.** Toàn bộ là nghiệp vụ back-office (tạo/cấu hình/quản trị công ty), đúng tinh thần đã chốt trước đó — app chỉ cần xử lý khóa tài khoản (xem `account-lockout-api.md`), không có màn quản trị công ty nào trên app. Nếu sau này mobile cần *đọc* một phần dữ liệu này (ví dụ áp màu thương hiệu/định dạng ngày giờ công ty vào giao diện app), đó là một quyết định sản phẩm riêng — phần "Khoảng trống & việc cần bàn thêm" ở mục 6 có nói rõ API đã sẵn sàng cho việc đó nếu cần.

## 2. Ma trận tổng hợp: Tính năng × Nền tảng × Vai trò

Ký hiệu: **Full** = thao tác đầy đủ · **View** = chỉ xem/đọc · **Ẩn** = không hiện trên UI (kể cả disable) · **—** = không áp dụng

| Tính năng | Endpoint chính | Admin Console (Platform Admin/Staff) | Company Portal — Owner | Company Portal — Nhân viên khác | Mobile App |
|---|---|---|---|---|---|
| Tạo công ty (self-service) | `POST /tenants` | — | Full (tạo công ty của chính mình) | Full (ai cũng tạo được công ty riêng) | Ẩn |
| Tạo công ty hộ khách (provisioning) | `POST /tenants` | Full (Platform Staff cần quyền `tenants:create`) | Ẩn | Ẩn | Ẩn |
| Danh sách/chi tiết mọi tenant | `GET /tenants`, `GET /tenants/{id}/detail` | Full | — (owner dùng bản "của tôi" riêng, xem dòng dưới) | Ẩn | Ẩn |
| **Xem** gói + mức sử dụng công ty mình | `GET /tenants/{id}/detail` | Full (xem hộ khi support) | **View** (mới mở 24/07/2026) | Ẩn | Ẩn |
| Sửa hồ sơ công ty | `PATCH /tenants/{id}` | **Ẩn nút Sửa** (chỉ Xem ở Admin Console) | Full | Ẩn | Ẩn |
| **Xem** cấu hình giao diện/định dạng | `GET /tenants/{id}/settings` | View | View | **View** (mới mở 24/07/2026) | Ẩn (chưa có nhu cầu) |
| **Sửa** cấu hình giao diện/định dạng | `PATCH /tenants/{id}/settings` | Ẩn nút Sửa | Full | Ẩn | Ẩn |
| IP whitelist — xem/sửa | `GET/POST/PATCH/DELETE .../ip-whitelists` | Full, nhưng **ẩn mặc định** (xem mục 4.4) | Full | Ẩn | Ẩn |
| Quản lý **định nghĩa** gói dịch vụ (trial/basic/pro/enterprise) | `POST/PATCH /plans`, `/plans/{id}/limits` | Full | Ẩn hoàn toàn | Ẩn | Ẩn |
| Xem danh sách gói (để tham khảo) | `GET /plans` | Full | View (màn "So sánh gói") | Ẩn | Ẩn |
| Gán/đổi subscription thủ công | `PATCH /tenants/{id}/subscription` | Full | Ẩn — Owner dùng luồng billing PayOS | Ẩn | Ẩn |
| Mua/gia hạn gói qua PayOS | `POST/GET /tenants/{id}/billing-orders` | Xem/đối soát | Full — chỉ Owner | Ẩn | Ẩn |
| Tạm dừng / Hủy tenant | `POST .../suspend`, `/reactivate`, `/cancel` | Full | Ẩn | Ẩn | Ẩn |

## 3. Chi tiết ẩn/hiện theo từng tính năng

### 3.1 Tạo công ty

- **Form self-service** (bất kỳ user nào, Company Portal, ví dụ nút "Tạo công ty mới" trong menu tài khoản): **không được có field** `ownerUserId`/`ownerEmail` — ẩn hẳn khỏi form, không chỉ disable, vì gửi lên sẽ bị `403`. Không có field chọn gói — gói luôn là `trial`, không cần UI nào cho việc này.
- **Form provisioning** (Admin Console, chỉ Platform Admin/Staff có quyền `tenants:create`): bắt buộc ô nhập `ownerEmail` hoặc chọn `ownerUserId` từ danh sách user hệ thống. **Không hiện dropdown chọn gói** — đã bỏ khỏi API từ 24/07/2026, gán gói khác đi qua bước riêng (xem 3.5).
- Sau khi tạo xong ở Admin Console: **không điều hướng Platform Admin vào trong công ty vừa tạo** — họ không phải thành viên (`ownerId` là người được chỉ định, không phải admin). Chỉ nên hiện thông báo thành công + link "Xem chi tiết" (đọc-only).

### 3.2 Sửa hồ sơ công ty — chỉ Owner

- Company Portal: nút "Sửa hồ sơ công ty" chỉ hiện khi `currentUser.id === tenant.ownerId`.
- Admin Console (trang chi tiết tenant): **hiện toàn bộ thông tin nhưng KHÔNG có nút Sửa** — kể cả với tenant do chính Platform Admin đó tạo hộ. Nên có 1 dòng ghi chú nhỏ kiểu "Chỉ chủ sở hữu (owner) mới chỉnh sửa được thông tin công ty" để tránh nhân viên support thắc mắc tại sao không sửa được.
- Test bắt buộc phía FE: gọi thử PATCH bằng tài khoản platform admin (qua Postman/console) vẫn phải nhận `403` — không chỉ ẩn ở UI, vì đây là chính sách bảo mật, không phải chỉ UX.

### 3.3 Cấu hình giao diện & định dạng (ngôn ngữ, ngày giờ, tiền tệ, màu thương hiệu)

- **GET** (đọc để hiển thị): mở cho **mọi thành viên tenant** kể từ 24/07/2026 — không chỉ owner. Lý do: mỗi client (web) của từng nhân viên trong công ty đều cần áp định dạng ngày/giờ và màu thương hiệu đúng theo cấu hình công ty đó, không riêng gì owner. Vì vậy màn hình chính (dashboard, layout chung) của Company Portal nên gọi GET này ngay sau khi đăng nhập/chọn tenant, áp dụng cho mọi role, kể cả nhân viên thường.
- **PATCH** (sửa): vẫn **chỉ Owner** — nút "Sửa cấu hình" ẩn/disable với mọi vai trò khác, kể cả Platform Admin và kể cả nhân viên khác trong cùng tenant.
- Admin Console: chỉ nên hiện dạng "Xem nhanh" (read-only) trong trang chi tiết tenant khi support cần kiểm tra cấu hình khách đang dùng, không cần màn sửa riêng.

### 3.4 IP whitelist — công cụ bảo mật, không phải cấu hình thường

- Company Portal: đây là màn "Bảo mật công ty" — nên đặt tách khỏi "Cấu hình giao diện" (mục 3.3) vì mức độ nhạy cảm khác hẳn (sai 1 IP có thể tự khóa cả công ty). Chỉ Owner thấy menu này.
- **Cảnh báo bắt buộc hiện trên UI khi tenant có ≥1 entry active**: "Chỉ các địa chỉ IP/mạng trong danh sách mới truy cập được API/hệ thống — hãy chắc chắn IP hiện tại của bạn nằm trong danh sách trước khi rời trang này."
- Backend có **chặn tự khóa mình** (self-lockout guard): nếu owner thao tác khiến IP hiện tại của họ bị loại khỏi danh sách active, API trả `400`/`INVALID_ARGUMENT` kèm message rõ ràng — **FE phải hiện nguyên văn message này** (không thay bằng lỗi validate chung chung), vì đây là tình huống người dùng cần hiểu rõ để tránh lặp lại.
- Admin Console: **nên ẩn khỏi menu chính**, chỉ để trong mục "Công cụ hỗ trợ" (support tools) ở trang chi tiết tenant — vì Platform Admin bypass hoàn toàn whitelist (không bao giờ tự bị khóa), lý do duy nhất họ cần vào đây là để **gỡ khóa hộ khách hàng** khi khách tự cấu hình sai và bị khóa ngoài chính API cần dùng để sửa.
- Không có trên Mobile App — đây là chính sách mạng cho truy cập API/web admin, không phải thứ nhân viên vận hành hàng ngày cần thấy.

### 3.5 Quản lý gói dịch vụ & subscription

Đây là tính năng **duy nhất trong 6 tính năng hoàn toàn thuộc Admin Console**, Company Portal chỉ có phần đọc:

- **Định nghĩa gói** (tạo/sửa trial/basic/pro/enterprise, đặt `maxEmployees`/`maxSites`/`maxStorageGb`/`maxRandomChecksPerMonth`): chỉ Platform Admin, chỉ Admin Console. Owner **không bao giờ** thấy màn này — họ không được tự định nghĩa gói cho chính mình.
- **Gán/đổi gói thủ công** (`PATCH .../subscription`) vẫn chỉ dành cho Platform Admin để xử lý ngoại lệ. Owner mua/gia hạn qua `POST .../billing-orders`; subscription chỉ được cập nhật sau webhook PayOS đã xác minh hoặc kết quả đối soát trực tiếp với PayOS.
- **Xem gói + mức sử dụng hiện tại của công ty mình** (`GET /tenants/{id}/detail`, mới mở 24/07/2026): Owner xem được ngay trong Company Portal — dựng màn "Gói dịch vụ & Mức sử dụng" hiển thị tên gói, giới hạn (`maxEmployees`/`maxSites`/`maxStorageGb`/`maxRandomChecksPerMonth`), và số đã dùng hiện tại (`currentEmployeeCount`/`currentSiteCount`/`currentMonthRandomChecks`). `max* = null` → hiển thị "Không giới hạn".
- Nhân viên thường trong tenant: **không** thấy màn "Gói dịch vụ" — đây là thông tin billing, chỉ owner cần.

## 4. Sơ đồ nav đề xuất theo vai trò

```text
ADMIN CONSOLE (fams-front-web-project /admin) — Platform Admin/Staff
├── Danh sách công ty            (GET /tenants)
├── Chi tiết công ty
│   ├── Hồ sơ (chỉ xem, không nút Sửa)
│   ├── Cấu hình giao diện (chỉ xem)
│   ├── Gói & mức sử dụng (xem, + nút "Đổi gói")
│   ├── Tạm dừng / Hủy
│   └── Công cụ hỗ trợ → IP whitelist (gỡ khóa hộ khách)
├── Quản lý gói dịch vụ           (CRUD Plan + Plan Limits)
└── Tạo công ty hộ khách hàng     (provisioning, cần quyền tenants:create)

COMPANY PORTAL (fams-front-web-project, sau khi đăng nhập + chọn tenant)
├── [Mọi thành viên] Dashboard — áp dụng dateFormat/timeFormat/màu brand từ GET settings
├── [Chỉ Owner] Hồ sơ công ty     (xem + sửa)
├── [Chỉ Owner] Cấu hình giao diện & định dạng (xem + sửa)
├── [Chỉ Owner] Bảo mật → IP whitelist (xem + sửa, kèm cảnh báo tự khóa)
├── [Chỉ Owner] Gói dịch vụ & Mức sử dụng (xem + thanh toán PayOS + lịch sử)
└── [Ai cũng tạo được] "+ Tạo công ty mới" (self-service, không có field owner/plan)

MOBILE APP (fams-front-app-project)
└── Không có menu quản trị công ty nào trong phạm vi 6 tính năng này
    (chỉ có: đăng nhập + xử lý 423 ACCOUNT_LOCKED, xem account-lockout-api.md)
```

## 5. Checklist bàn giao FE

- [ ] Admin Console và Company Portal **dùng chung 1 codebase** (`fams-front-web-project`) nhưng route và layout tách biệt rõ — không render menu Admin Console cho user không phải Platform Admin/Staff dù có ẩn bằng CSS, vì route vẫn phải tự chặn (kiểm tra `isPlatformAdmin`/quyền, không chỉ dựa vào menu ẩn).
- [ ] Company Portal: mọi nút "Sửa" (hồ sơ, cấu hình, IP whitelist) đều check `currentUser.id === tenant.ownerId` trước khi render — không chỉ disable mà nên **ẩn hẳn** nút để tránh hiểu lầm "có quyền nhưng bị lỗi".
- [ ] Test 403 thật (không chỉ test UI ẩn) cho: platform admin PATCH hồ sơ/settings, nhân viên thường PATCH bất kỳ mục owner-only nào, user ngoài tenant GET settings/detail của tenant không thuộc về mình.
- [ ] Dashboard chung của Company Portal gọi `GET /tenants/{id}/settings` 1 lần lúc vào (không phải chỉ màn cấu hình) để áp `dateFormat`/`timeFormat`/màu brand toàn app — áp dụng cho **mọi vai trò**, không riêng owner.
- [ ] Màn "Gói dịch vụ & Mức sử dụng" phía owner dùng `GET /tenants/{id}/detail` (không phải `GET .../subscription` — endpoint đó thiếu số liệu usage).
- [ ] IP whitelist: hiện rõ cảnh báo + xử lý message lỗi tự khóa nguyên văn từ backend (mục 3.4).
- [ ] Nút "Nâng cấp/gia hạn" tạo billing order và mở `checkoutUrl`; tuyệt đối không gọi thẳng API quản trị subscription.

## 6. Khoảng trống & việc cần bàn thêm (chưa nằm trong scope hiện tại)

- **`maxStorageGb` chưa được enforce ở backend** — dựng UI hiển thị giới hạn này là được (đọc từ API bình thường), nhưng **đừng dựa vào nó để tự tin rằng hệ thống sẽ chặn khi khách vượt dung lượng** — hiện tại không có gì chặn cả. Xem chi tiết kỹ thuật ở `tenant-api.md` mục 3.6.
- **Mobile App hiện chưa đọc `dateFormat`/`timeFormat`/màu brand của công ty** — API `GET /tenants/{id}/settings` đã mở cho mọi thành viên tenant (không chỉ owner) nên về mặt quyền, app gọi được ngay nếu sau này cần đồng bộ giao diện app theo từng công ty. Đây là quyết định sản phẩm cần bạn xác nhận, không tự ý thêm vào scope app.
- Owner đã có luồng tự mua/gia hạn qua PayOS. Phạm vi MVP chưa gồm tự động trừ tiền định kỳ,
  prorate, hoàn tiền và phát hành hóa đơn điện tử qua nhà cung cấp hợp pháp. Hệ thống đã hiển thị
  chi tiết giao dịch, phiếu xác nhận thanh toán nội bộ cho đơn `PAID` và trạng thái chờ phát hành
  hóa đơn; phiếu nội bộ không được gọi hoặc sử dụng thay hóa đơn VAT.
