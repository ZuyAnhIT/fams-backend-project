# Tài liệu tích hợp Tenant (Company) Management cho Web và Admin App

> Cập nhật theo code và API đang chạy ngày 24/07/2026 (bao gồm: settings owner-only, IP whitelist có enforce thật, tạo tenant luôn gán gói trial). Base path chính: `/api/v1/tenants` và `/api/v1/plans`.
> Xem `docs/api/auth-api.md` cho đăng ký/đăng nhập/hồ sơ cá nhân — tài liệu này chỉ nói về công ty (tenant), gói dịch vụ, và chính sách khóa tài khoản liên quan tới đăng nhập sai nhiều lần.

Tài liệu này là hợp đồng bàn giao cho:

1. Màn hình "Tạo công ty của tôi" (self-service, mọi user).
2. Màn hình quản trị nền tảng: tạo công ty hộ khách hàng, gán chủ sở hữu (gói luôn mặc định trial), quản lý gói dịch vụ.
3. Màn hình danh sách/chi tiết công ty (Platform Admin/Staff).
4. Màn hình cập nhật hồ sơ công ty, cấu hình giao diện/định dạng, IP whitelist (chỉ chủ sở hữu).
5. Xử lý khóa tài khoản khi đăng nhập sai (áp dụng cho màn đăng nhập, tham chiếu `auth-api.md` mục 4).

## 1. Quy ước chung

Giống hệt `auth-api.md` mục 1: mọi request JSON gửi `Content-Type: application/json`; API cần đăng nhập gửi `Authorization: Bearer <accessToken>`; cấu trúc response thành công/lỗi giống nhau (`success/message/data/errorCode/userMessage`).

**Vai trò liên quan trong tài liệu này:**

| Vai trò | Mô tả |
|---|---|
| **Regular user** | Bất kỳ user đã đăng nhập, không có quyền platform |
| **Tenant Owner** | User có `ownerId` trùng với `tenants.owner_id` của một công ty cụ thể — chủ sở hữu công ty đó |
| **Platform Admin** | `isPlatformAdmin = true` trên tài khoản — toàn quyền nền tảng |
| **Platform Staff** | Role `PLATFORM_STAFF` — xem được danh sách/chi tiết mọi công ty, và (từ 24/07/2026) tạo được công ty hộ khách hàng, nhưng KHÔNG sửa được hồ sơ công ty và KHÔNG có các quyền quản trị khác (suspend/cancel/subscription) |

## 2. Khóa tài khoản khi đăng nhập sai nhiều lần

Đây là hành vi của `POST /api/v1/auth/login` (xem `auth-api.md` mục 4) — nhắc lại chi tiết vì ảnh hưởng trực tiếp đến UX màn đăng nhập.

### 2.1 Quy tắc

- Sai mật khẩu **5 lần liên tiếp** (tính theo tài khoản, không theo IP) → tài khoản bị khóa **1 tiếng**, kể cả khi sau đó nhập đúng mật khẩu.
- Đăng nhập thành công sẽ reset bộ đếm sai về 0.
- Ngay khi bị khóa, nếu tài khoản có email, hệ thống **tự động gửi một email cảnh báo** chứa thời điểm mở khóa và link "quên mật khẩu".
- **Đặt lại mật khẩu thành công qua email = mở khóa ngay lập tức** — không cần đợi hết 1 tiếng. Đây là lối thoát chính thức dành cho chủ tài khoản thật.

### 2.2 Response khi bị khóa

`POST /api/v1/auth/login` trả **`423 Locked`**:

```json
{
  "success": false,
  "message": "Account locked until 2026-07-24T09:57:36Z",
  "data": null,
  "errorCode": "ACCOUNT_LOCKED",
  "userMessage": "Tài khoản bị khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau 2026-07-24T09:57:36Z."
}
```

### 2.3 Xử lý UI đề xuất

```text
Login trả 423 ACCOUNT_LOCKED
        |
        v
Hiện màn "Tài khoản tạm khóa": parse thời điểm mở khóa từ message,
hiển thị đếm ngược + nút "Quên mật khẩu / Mở khóa ngay"
        |
        v
Bấm nút -> POST /auth/forgot-password (auth-api.md mục 6.2)
        |
        v
Người dùng mở link trong email -> đặt mật khẩu mới (mục 6.3)
        |
        v
Login lại thành công NGAY (không cần đợi đếm ngược về 0)
```

Frontend **không cần** gọi API nào khác để "biết" tài khoản đã hết khóa — cứ thử login lại là biết (423 nếu còn khóa, 200/401 tùy mật khẩu nếu đã hết khóa hoặc vừa được reset).

### 2.4 Lưu ý khi test

- Không có API riêng để tra "còn khóa bao lâu" — thông tin nằm trong `message`/`userMessage` của chính response 423 lúc thử login.
- Nếu tài khoản chỉ đăng ký bằng phone (không có email), sẽ **không** nhận được email cảnh báo và **không** có lối thoát "quên mật khẩu" — bắt buộc phải đợi hết 1 tiếng. Nếu sản phẩm cần lối thoát nhanh hơn cho tài khoản phone-only, đó là việc cần bàn thêm (chưa có trong scope hiện tại).

## 3. Tạo công ty (Tenant)

`POST /api/v1/tenants` — có **Bearer token**, không có `@PreAuthorize` chặn ở tầng route (ai đăng nhập cũng gọi được), nhưng **hành vi rẽ nhánh theo quyền của người gọi**.

### 3.1 Hai chế độ tạo — phân biệt bằng quyền của caller

| | Self-service | Platform provisioning |
|---|---|---|
| Ai gọi được | Bất kỳ user nào | Platform Admin, hoặc user có quyền `tenants:create` (Platform Staff) |
| `ownerUserId`/`ownerEmail` | **Không được gửi** (403 nếu gửi) | **Bắt buộc** một trong hai (400 nếu thiếu cả hai) |
| Ai thành chủ sở hữu | Chính người gọi | Người được chỉ định qua `ownerUserId`/`ownerEmail` — **phải đã có tài khoản FAMS** |
| Người gọi có được thêm vào công ty không | Có — tự động thành `TENANT_ADMIN` | **Không** — chỉ người được chỉ định làm chủ mới có `TENANT_ADMIN` |
| `status` ban đầu | `"trial"` | `"active"` |
| Gói dịch vụ | Luôn mặc định gói rẻ nhất/miễn phí (`trial`) | Luôn mặc định gói rẻ nhất/miễn phí (`trial`) — **không thể chọn gói khác lúc tạo** |

**Quan trọng:** `ownerUserId`/`ownerEmail` ở chế độ platform provisioning là **gán quyền trực tiếp**, không phải gửi lời mời — người được chỉ định phải **đã có tài khoản** trên hệ thống từ trước (tự đăng ký, hoặc do admin tạo sẵn). Nếu email/id đó chưa tồn tại tài khoản nào → lỗi `404`, không có email mời nào được gửi.

**Cập nhật 24/07/2026 — bỏ `planId` khỏi tạo tenant:** trước đây platform provisioning có thể chọn gói (`planId`) ngay lúc tạo. Do hệ thống **chưa có thanh toán online**, mọi tenant mới — bất kể tạo qua đường nào — giờ **luôn** được gán gói `trial`/miễn phí mặc định (subscription status `TRIAL`). Muốn nâng gói cho một tenant đã tồn tại (kể cả ngay sau khi vừa tạo), dùng `PATCH /tenants/{id}/subscription` (mục 6, chỉ Platform Admin) — đây là hành động nâng cấp tách biệt, có chủ đích, không lẫn vào bước tạo công ty.

### 3.2 Request — Self-service (tự tạo công ty của mình)

```json
{
  "name": "Acme Corporation",
  "slug": "acme-corp",
  "domain": "acme.example.com",
  "industry": "Construction",
  "countryCode": "VN",
  "timezone": "Asia/Ho_Chi_Minh",
  "locale": "vi",
  "currencyCode": "VND"
}
```

| Trường | Bắt buộc | Ghi chú |
|---|---:|---|
| `name` | Có | 2–255 ký tự |
| `slug` | Có | 2–100 ký tự, chỉ chữ thường/số/gạch ngang, duy nhất toàn hệ thống |
| `domain` | Không | Duy nhất toàn hệ thống nếu có |
| `industry`, `countryCode`, `timezone`, `locale`, `currencyCode` | Không | Có giá trị mặc định nếu bỏ qua (`UTC`, `en`, `USD`...) |

Thành công — `201 Created`:

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "id": "b9c47c3c-8d1c-4891-a1ec-0390fbc0f144",
    "name": "Acme Corporation",
    "slug": "acme-corp",
    "status": "trial",
    "ownerId": "<chính user gọi>",
    "ownerName": null,
    "ownerEmail": null,
    "...": "các field khác giống UpdateTenantRequest"
  }
}
```

*(Lưu ý: `ownerName`/`ownerEmail` chỉ được điền khi lấy qua danh sách/chi tiết — response ngay lúc tạo không kèm theo, phải gọi `GET .../detail` hoặc `GET /tenants` để có đủ.)*

Một user có thể tự tạo **nhiều công ty** — không giới hạn số lượng, mỗi công ty một `TENANT_ADMIN` riêng.

### 3.3 Request — Platform provisioning (tạo hộ khách hàng)

```json
{
  "name": "Provisioned Corp",
  "slug": "provisioned-corp",
  "industry": "Retail",
  "countryCode": "VN",
  "ownerEmail": "owner@example.com"
}
```

| Trường thêm | Kiểu | Ghi chú |
|---|---|---|
| `ownerUserId` | UUID | Cách 1 — chỉ định chủ sở hữu bằng UUID (ưu tiên nếu có cả hai) |
| `ownerEmail` | string | Cách 2 — chỉ định bằng email, phải là email của tài khoản đã tồn tại |

Thành công `201` giống cấu trúc trên, nhưng `status: "active"` và `ownerId` = id của người được chỉ định (không phải id của platform admin gọi API). Gói dịch vụ vẫn luôn là `trial` — không có field nào để chọn gói khác ở bước này (xem ghi chú 24/07/2026 ở mục 3.1).

### 3.4 Các lỗi khi tạo

| HTTP | `errorCode` | Khi nào | Ghi chú |
|---:|---|---|---|
| 400 | `VALIDATION_ERROR` | Sai định dạng `name`/`slug`/`countryCode`... | Hiện lỗi tại field |
| 400 | `INVALID_ARGUMENT` | Platform provisioning nhưng thiếu cả `ownerUserId` lẫn `ownerEmail` | "ownerUserId or ownerEmail is required..." |
| 401 | (không có `errorCode`) | Thiếu/hết hạn Bearer token | Về màn đăng nhập |
| 403 | `ACCESS_DENIED` | Self-service caller cố gửi `ownerUserId`/`ownerEmail` | Ẩn hẳn các field này khỏi form self-service để tránh gọi nhầm |
| 404 | `RESOURCE_NOT_FOUND` | `ownerEmail`/`ownerUserId` không khớp tài khoản nào đang tồn tại | Gợi ý: "Người này chưa có tài khoản — mời họ đăng ký trước" |
| 409 | `DUPLICATE_RESOURCE` | `slug` hoặc `domain` đã được dùng | Gợi ý slug khác |

### 3.5 Lấy danh sách gói dịch vụ để hiển thị dropdown

`GET /api/v1/plans` — Bearer token, phân trang.

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "b95ba69e-cb21-4783-826f-2664572b4402",
        "name": "trial",
        "displayName": "Trial",
        "description": "Free trial with limited features. No credit card required.",
        "priceMonthly": 0.00,
        "priceYearly": 0.00,
        "sortOrder": 1,
        "isActive": true
      }
    ],
    "totalElements": 4
  }
}
```

Frontend nên lọc `isActive == true` khi hiển thị dropdown chọn gói — hiện tại dropdown này chỉ dùng để **nâng cấp gói cho tenant đã tồn tại** (`PATCH /tenants/{id}/subscription`, mục 6), không còn dùng lúc tạo tenant nữa (gói inactive vẫn có thể còn hiệu lực với tenant cũ, nhưng không nên gán mới).

### 3.6 Quản trị gói dịch vụ (Plan) — Platform Admin

Bộ CRUD đầy đủ cho gói dịch vụ, dùng để dựng màn "Quản lý gói" trong admin console:

| Endpoint | Method | Ai gọi được | Mục đích |
|---|---|---|---|
| `/api/v1/plans` | GET | Bearer token (public, mọi user đăng nhập) | Danh sách gói, hỗ trợ `search`/`sortBy`/`sortDir`/phân trang |
| `/api/v1/plans/{id}` | GET | Bearer token | Chi tiết 1 gói |
| `/api/v1/plans` | POST | Chỉ Platform Admin | Tạo gói mới (`name` phải là `trial`/`basic`/`pro`/`enterprise` hoặc tên tùy chỉnh, `sortOrder`, `priceMonthly`, `priceYearly`, `isActive`) |
| `/api/v1/plans/{id}` | PATCH | Chỉ Platform Admin | Sửa gói. Nếu đổi `isActive: false` cho gói đang có tenant dùng, có thể kèm `migrateToPlanId` để tự động chuyển các tenant đó sang gói khác an toàn |
| `/api/v1/plans/{id}/limits` | GET | Bearer token | Xem giới hạn gói (`maxEmployees`, `maxSites`, `maxStorageGb`, `maxRandomChecksPerMonth`) |
| `/api/v1/plans/{id}/limits` | PATCH | Chỉ Platform Admin | Sửa giới hạn — bỏ trống/`null` một field = **không giới hạn** cho field đó |

**Enforcement (giới hạn có thật sự được áp dụng không):**

| Giới hạn | Có enforce không | Chỗ enforce |
|---|---|---|
| `maxEmployees` | ✅ Có | Chặn khi thêm nhân viên vượt giới hạn gói (`422`) |
| `maxSites` | ✅ Có | Chặn khi tạo site vượt giới hạn gói (`422`) |
| `maxRandomChecksPerMonth` | ✅ Có | Chặn khi số lần random-check trong tháng vượt giới hạn gói |
| `maxStorageGb` | ⚠️ **Chưa enforce** | Field có thể cấu hình qua API trên nhưng **không có chỗ nào trong code kiểm tra dung lượng thực tế đã dùng so với giới hạn này**. Ảnh hưởng: tenant có thể upload avatar/ảnh chấm công vượt xa `maxStorageGb` mà không bị chặn. Lý do kỹ thuật: kho lưu trữ avatar (`AvatarStorageService`, S3/MinIO) tính theo `userId` chứ không theo `tenantId` nên không thể cộng dồn theo tenant; ảnh Face ID (nguồn chiếm dung lượng chính) lại nằm ở service Python AI riêng, chưa có API tổng hợp dung lượng theo tenant để service Java gọi sang. Đây là việc cần làm riêng (cross-service), chưa nằm trong scope đợt này — flag lại để backlog theo dõi, **không tự nhận là đã hoàn thành**. |

## 4. Danh sách và chi tiết Tenant (Platform Admin/Staff)

### 4.1 Danh sách

`GET /api/v1/tenants` — cần quyền `hasRole('PLATFORM_ADMIN')` hoặc `hasAuthority('tenants:list')`.

| Query param | Kiểu | Mặc định | Ghi chú |
|---|---|---|---|
| `search` | string | — | Tìm theo tên hoặc slug (chứa, không phân biệt hoa thường) |
| `status` | string | — | `trial`, `active`, `suspended`, `cancelled` |
| `industry` | string | — | Khớp chính xác |
| `countryCode` | string | — | ISO 3166-1 alpha-2 |
| `sortBy` | string | `createdAt` | Chỉ nhận: `name`, `slug`, `status`, `createdAt`, `updatedAt` — giá trị khác tự rơi về `createdAt`, không lỗi |
| `sortDir` | string | `desc` | `asc` hoặc `desc` — giá trị khác tự rơi về `desc` |
| `page` | int | `0` | 0-based |
| `size` | int | `20` | 1–100 |

Ví dụ: `GET /api/v1/tenants?search=acme&status=active&sortBy=name&sortDir=asc&page=0&size=20`

Response — `PageResponse<TenantResponse>`:

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "3990a7a7-7e0b-4451-9a15-11a72f682598",
        "name": "Provisioned Corp",
        "slug": "provisioned-corp",
        "domain": null,
        "logoUrl": null,
        "industry": "Retail",
        "countryCode": "VN",
        "timezone": "UTC",
        "locale": "en",
        "currencyCode": "USD",
        "status": "active",
        "ownerId": "dd33c525-34f6-4de9-8891-ec87607560ff",
        "ownerName": "Self Owner",
        "ownerEmail": "owner@example.com",
        "createdAt": "2026-07-24T08:09:03Z",
        "updatedAt": "2026-07-24T08:09:36Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 42,
    "totalPages": 3,
    "first": true,
    "last": false
  }
}
```

Đây là danh sách "toàn quyền" (internal), không phải danh sách public — mọi field kể cả `ownerEmail` đều hiển thị. Nếu sau này cần một trang "company directory" công khai, sẽ là endpoint riêng, không tái dùng endpoint này.

### 4.2 Chi tiết vận hành (Operational Detail)

`GET /api/v1/tenants/{id}/detail` — Platform Admin/Staff (`tenants:read`) gọi được cho **bất kỳ** tenant nào, **hoặc** (cập nhật 24/07/2026) **chính chủ sở hữu tenant đó** gọi cho công ty của mình — dùng để dựng màn "Gói dịch vụ & Mức sử dụng" tự phục vụ trong Company Portal, không cần đợi admin cung cấp. Chủ sở hữu gọi cho tenant KHÔNG phải của mình vẫn nhận `403`.

Trả về hồ sơ công ty + gói dịch vụ + giới hạn gói + số liệu sử dụng hiện tại trong **một lần gọi** (tránh phải gọi 3-4 API riêng):

```json
{
  "success": true,
  "data": {
    "id": "3990a7a7-7e0b-4451-9a15-11a72f682598",
    "name": "Provisioned Corp",
    "slug": "provisioned-corp",
    "status": "active",
    "ownerId": "dd33c525-34f6-4de9-8891-ec87607560ff",
    "createdAt": "2026-07-24T08:09:03Z",

    "planName": "basic",
    "planDisplayName": "Basic",
    "subscriptionStatus": "ACTIVE",
    "billingCycle": "MONTHLY",
    "subscriptionStartedAt": "2026-07-24T08:09:03Z",
    "subscriptionExpiresAt": null,

    "maxEmployees": 50,
    "maxSites": 5,
    "maxStorageGb": 10,
    "maxRandomChecksPerMonth": 100,

    "currentEmployeeCount": 0,
    "currentSiteCount": 0,
    "currentMonthRandomChecks": 0
  }
}
```

`maxXxx = null` nghĩa là **không giới hạn** cho field đó — frontend nên hiển thị "Không giới hạn" thay vì "0" hay để trống.

### 4.3 Lỗi chung cho danh sách/chi tiết

| HTTP | `errorCode` | Khi nào |
|---:|---|---|
| 401 | (không có `errorCode`) | Thiếu/hết hạn Bearer token |
| 403 | `ACCESS_DENIED` | Danh sách: user không có role `PLATFORM_ADMIN`/quyền `tenants:list`. Chi tiết: user không có role `PLATFORM_ADMIN`/quyền `tenants:read` **và** không phải chủ sở hữu tenant đó |
| 404 | `RESOURCE_NOT_FOUND` | (chỉ ở detail) tenant không tồn tại hoặc đã xóa |

## 5. Cập nhật hồ sơ công ty — chỉ chủ sở hữu

`PATCH /api/v1/tenants/{id}` — Bearer token.

```json
{
  "name": "Acme Corporation Ltd.",
  "domain": "new.acme.com",
  "logoUrl": "https://cdn.example.com/logos/acme.png",
  "industry": "Construction",
  "countryCode": "VN",
  "timezone": "Asia/Ho_Chi_Minh",
  "locale": "vi-VN",
  "currencyCode": "VND"
}
```

Mọi field optional — chỉ gửi field muốn đổi. `domain`/`logoUrl` gửi chuỗi rỗng `""` để xóa giá trị hiện tại.

**Quy tắc quyền — quan trọng, khác với trước 24/07/2026:**

> Chỉ **chủ sở hữu** (`userId` gọi API trùng `tenants.owner_id`) mới sửa được. **Platform Admin/Staff KHÔNG còn được sửa** hồ sơ công ty nữa, kể cả tenant do chính họ tạo hộ (mục 3.3) — quyền tạo/gán owner/gán gói tách biệt hoàn toàn khỏi quyền chỉnh sửa thông tin sau này.

Ý nghĩa cho frontend: màn "Chỉnh sửa hồ sơ công ty" **chỉ hiện cho chủ sở hữu đang đăng nhập**, không hiện (hoặc disable) với tài khoản platform admin/staff dù họ có quyền xem chi tiết ở mục 4.2.

Thành công `200`, trả `TenantResponse` (giống mục 3.2) với dữ liệu đã cập nhật.

| HTTP | `errorCode` | Khi nào |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Sai định dạng field |
| 401 | (không có `errorCode`) | Thiếu/hết hạn Bearer token |
| 403 | `ACCESS_DENIED` | Người gọi không phải chủ sở hữu — **kể cả platform admin/staff** |
| 404 | `RESOURCE_NOT_FOUND` | Tenant không tồn tại |
| 409 | `DUPLICATE_RESOURCE` | `domain` mới đã được tenant khác dùng |

## 6. Quản trị vòng đời Tenant

Các endpoint sau **không đổi** so với trước, liệt kê lại để đầy đủ. Khác với mục 5 (owner-only), nhóm này **đa số vẫn cho Platform Admin** thao tác (và vài API còn cho cả chủ sở hữu xem) — không bị ảnh hưởng bởi thay đổi quyền sửa hồ sơ ở mục 5:

| Endpoint | Method | Ai gọi được | Mục đích | Lỗi 400 khi |
|---|---|---|---|---|
| `/tenants/{id}/suspend` | POST | Chỉ Platform Admin | Tạm dừng tenant, chặn toàn bộ user không phải admin | Đã suspended/cancelled |
| `/tenants/{id}/reactivate` | POST | Chỉ Platform Admin | Khôi phục từ suspended về trạng thái trước đó | Không đang suspended |
| `/tenants/{id}/cancel` | POST | Chỉ Platform Admin | Hủy vĩnh viễn, không hoàn tác được | Đã cancelled |
| `/tenants/{id}/subscription` | GET | Chủ sở hữu **hoặc** Platform Admin | Xem subscription hiện tại | — |
| `/tenants/{id}/subscription` | POST | Chỉ Platform Admin | Gán subscription **mới** (chỉ khi tenant CHƯA có subscription nào — hiếm khi cần vì tạo tenant đã tự động gán) | Tenant đã có subscription active (409) |
| `/tenants/{id}/subscription` | PATCH | Chỉ Platform Admin | Đổi gói/chu kỳ billing/trạng thái/ngày hết hạn của subscription đang có | — |

`PATCH .../subscription` (`UpdateSubscriptionRequest`) là cách đúng để **đổi gói cho tenant đã tồn tại** (upsell/downgrade), ví dụ:

```json
{"planId": "<uuid gói pro>", "billingCycle": "YEARLY"}
```

hoặc hủy hẳn:

```json
{"status": "CANCELLED"}
```

## 7. Cấu hình giao diện & định dạng công ty (Tenant Settings) — xem: mọi thành viên, sửa: chỉ chủ sở hữu

`GET/PATCH /api/v1/tenants/{id}/settings` — ngôn ngữ, định dạng ngày/giờ, tiền tệ, màu thương hiệu riêng cho từng công ty.

- **GET** (cập nhật 24/07/2026): cho phép **bất kỳ thành viên nào của tenant** (nhân viên thường, không chỉ chủ sở hữu) hoặc Platform Admin — vì mỗi client (web/mobile của từng nhân viên) đều cần các giá trị này để hiển thị ngày/giờ/màu thương hiệu đúng theo công ty, không riêng gì owner.
- **PATCH**: vẫn **chỉ chủ sở hữu** — kể cả platform admin từng tạo hộ tenant cũng bị `403`, kể cả nhân viên thường khác trong tenant cũng bị `403`.

Request PATCH (mọi field optional, chỉ gửi field muốn đổi):

```json
{
  "dateFormat": "MM/DD/YYYY",
  "timeFormat": "h:mm a",
  "brandPrimaryColor": "#3B82F6",
  "brandSecondaryColor": "#10B981",
  "brandAccentColor": "#F59E0B"
}
```

Giá trị mặc định khi chưa cấu hình gì: `dateFormat="DD/MM/YYYY"`, `timeFormat="HH:mm"`. Màu thương hiệu (`brandPrimaryColor`/`brandSecondaryColor`/`brandAccentColor`) phải đúng định dạng hex `#RRGGBB`, sai định dạng trả `400`.

| HTTP | `errorCode` | Khi nào |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Sai định dạng màu hex |
| 401 | (không có `errorCode`) | Thiếu/hết hạn Bearer token |
| 403 | `ACCESS_DENIED` | GET: người gọi không phải thành viên tenant và không phải Platform Admin. PATCH: người gọi không phải chủ sở hữu — kể cả platform admin hoặc thành viên khác trong tenant |
| 404 | `RESOURCE_NOT_FOUND` | Tenant không tồn tại |

## 8. Quản lý IP whitelist — kiểm soát truy cập theo mạng (thực sự enforce)

`GET/POST/PATCH/DELETE /api/v1/tenants/{id}/ip-whitelist` — chủ sở hữu hoặc Platform Admin quản lý (cùng quyền như mục 5/7: xem+sửa đều cho owner, Platform Admin cũng quản lý được ở đây — khác với Settings/Profile vì đây là công cụ bảo mật platform cần hỗ trợ được).

**Đây là whitelist có enforce thật**, không chỉ là CRUD lưu trong bảng: mọi request đã đăng nhập, có `tenantId`, đi qua `JwtAuthFilter` đều bị kiểm tra IP so với whitelist đang active của tenant đó (đọc `X-Forwarded-For` trước, fallback về IP kết nối trực tiếp).

- **Opt-in theo tenant**: tenant chưa cấu hình entry active nào (0 entry) = **không giới hạn**, mọi IP đều vào được — giống GitHub/GitLab IP allow list. Chỉ cần có ≥1 entry active là tenant chuyển sang chế độ "chỉ các mạng trong danh sách".
- Entry hỗ trợ IP đơn (`203.0.113.5`) hoặc CIDR (`172.18.0.0/24`), cả IPv4 và IPv6.
- **Platform Admin luôn bypass** kiểm tra này (không bị chặn bởi whitelist của bất kỳ tenant nào) — để đảm bảo hỗ trợ kỹ thuật không bao giờ bị tự khóa ngoài.
- IP không nằm trong whitelist → **`403`**, `errorCode: "IP_NOT_WHITELISTED"`.

**Chống tự khóa chính mình (self-lockout guard):** khi chủ sở hữu (không áp dụng cho Platform Admin) thêm/sửa/xóa một entry mà kết quả sẽ khiến **IP hiện tại của chính họ** rơi ra ngoài whitelist đang active — hệ thống **chặn thao tác đó** với `400`/`INVALID_ARGUMENT` và thông báo rõ lý do, thay vì để họ tự khóa mình khỏi chính API cần dùng để sửa lại. Ngoại lệ: nếu thao tác khiến tenant về 0 active entry (tức là về trạng thái không giới hạn) thì luôn an toàn, không bị chặn.

```json
{
  "success": false,
  "message": "This change would remove your own current IP (203.0.113.9) from the whitelist and lock you out. Keep or add an entry that covers your current IP, or ask a Platform Admin to make this change instead.",
  "errorCode": "INVALID_ARGUMENT"
}
```

Frontend nên hiển thị rõ cảnh báo này thay vì thông báo lỗi validate chung chung, vì đây là tình huống người dùng thực sự cần hiểu để tránh lặp lại.

## 9. State machine đề xuất cho frontend

```text
Màn "Tạo công ty" (self-service)
  |-- POST /tenants (không owner) --> 201 --> chuyển vào Dashboard công ty vừa tạo
  |                                            (user đã là TENANT_ADMIN ngay, gói = trial)

Màn "Provisioning" (Platform Admin/Staff)
  |-- POST /tenants (ownerEmail/ownerUserId) --> 201 (gói luôn = trial)
  |       |-- 404 --> hiện "chưa có tài khoản, mời đăng ký trước"
  |       |-- 400 --> hiện "chưa chọn người sở hữu"
  |-- Muốn nâng gói ngay? --> PATCH /tenants/{id}/subscription (hành động riêng, có chủ đích)
  |-- Danh sách hiển thị tenant mới, KHÔNG chuyển vào dashboard (admin không phải member)

Màn "Danh sách công ty" (Platform Admin/Staff)
  |-- GET /tenants?search=&status=&sortBy=&page= --> hiển thị bảng + filter + phân trang
  |-- Click 1 dòng --> GET /tenants/{id}/detail --> màn chi tiết
  |       |-- Nút "Sửa hồ sơ": ẨN/DISABLE (admin không có quyền)
  |       |-- Nút "Tạm dừng/Hủy/Đổi gói": hiện (vẫn thuộc quyền platform admin)

Màn "Hồ sơ công ty của tôi" (Tenant Owner)
  |-- GET /tenants/{id}/detail --> hiển thị đầy đủ
  |-- PATCH /tenants/{id} --> sửa được (chính chủ)
```

## 10. Checklist bàn giao frontend

### Tạo công ty (self-service)
- [ ] Form KHÔNG có field owner/plan — ẩn hoàn toàn, không chỉ disable.
- [ ] Sau 201, tự hiểu user đã là `TENANT_ADMIN`, không cần gọi thêm API xác nhận quyền.
- [ ] Test tạo 2 công ty liên tiếp bằng cùng 1 tài khoản — phải cho phép.

### Tạo công ty (provisioning)
- [ ] Bắt buộc chọn/nhập owner trước khi submit (validate phía client trước khi gọi API).
- [ ] Xử lý riêng lỗi 404 (chưa có tài khoản) khác với 400 (thiếu field) — thông điệp khác nhau.
- [ ] KHÔNG hiện dropdown chọn gói ở form tạo — gói luôn mặc định `trial`, không có field nào gửi lên để đổi.
- [ ] Sau tạo xong, **không** điều hướng admin vào trong công ty đó (họ không phải thành viên).
- [ ] Nếu cần nâng gói ngay sau khi tạo, dẫn sang màn "Đổi gói" riêng (`PATCH /tenants/{id}/subscription`), không gộp vào form tạo.

### Cấu hình giao diện & định dạng (Settings)
- [ ] Nút "Sửa cấu hình" chỉ hiện khi `currentUser.id === tenant.ownerId` — ẩn/disable với cả platform admin.
- [ ] Validate định dạng hex màu ở client trước khi submit, nhưng vẫn phải xử lý được `400` từ server.

### IP whitelist
- [ ] Hiện rõ cảnh báo khi tenant có ≥1 entry active: "Chỉ các mạng trong danh sách mới truy cập được".
- [ ] Khi thêm/sửa/xóa gặp lỗi tự khóa (`INVALID_ARGUMENT` kèm message nhắc IP hiện tại), hiển thị nguyên văn gợi ý — đừng thay bằng thông báo lỗi chung.
- [ ] Test: xóa hết toàn bộ entry active phải luôn thành công (về trạng thái không giới hạn), không bị chặn bởi guard tự khóa.

### Danh sách/chi tiết
- [ ] Có ô tìm kiếm nhanh (search), bộ lọc (status/industry/countryCode), sort theo cột, phân trang — dùng đúng tên param ở mục 4.1.
- [ ] Trang chi tiết hiển thị luôn giới hạn gói + usage hiện tại (không cần gọi thêm API subscription/employee-count riêng).
- [ ] `max* = null` hiển thị "Không giới hạn".

### Sửa hồ sơ công ty
- [ ] Nút "Sửa" chỉ hiện khi `currentUser.id === tenant.ownerId` — kể cả tài khoản đang xem là platform admin cũng phải ẩn/disable nút này.
- [ ] Test: platform admin cố PATCH qua Postman/console vẫn phải nhận 403 (double-check backend, không chỉ ẩn UI).

### Khóa tài khoản
- [ ] Màn login xử lý riêng `423 ACCOUNT_LOCKED`, parse thời điểm mở khóa từ message.
- [ ] Có nút tắt dẫn sang "Quên mật khẩu" ngay tại màn báo khóa.
- [ ] Không tự ý thử login lặp lại nhiều lần khi đang 423 (tránh spam, dù backend không cộng dồn thêm lock).

### URL kiểm tra local

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
