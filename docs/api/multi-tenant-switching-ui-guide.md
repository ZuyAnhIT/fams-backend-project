# Tài liệu bàn giao UI: Xem/chuyển công ty cho tài khoản đa công ty (multi-tenant)

> Cập nhật theo code đang chạy ngày 31/07/2026. Đây **không phải** tài liệu API đầy đủ (đã có sẵn Swagger cho `RoleController`/`AuthController`) — chỉ trả lời: dùng API nào, khi nào, và các điểm cần lưu ý khi dựng UI.

## 0. Bối cảnh

1 người có thể làm việc ở nhiều công ty (tenant) khác nhau dưới **cùng 1 tài khoản đăng nhập** (cùng email) — ví dụ seed data: chị Phạm Thị Dung (HR_MANAGER @ Hoàng Long + SITE_SUPERVISOR @ Bình Minh), anh Trương Văn Đạt (EMPLOYEE @ Phương Nam + EMPLOYEE @ Tia Sáng). Cơ chế này **đã có sẵn ở backend từ trước** (Issue #3, `docs/issues/ISSUES.md`) — tài liệu này chỉ hướng dẫn Web/App tích hợp đúng.

**Face ID, dữ liệu chấm công, hồ sơ nhân viên... đều tách biệt hoàn toàn theo từng công ty** — xem chi tiết mục 4.

## 1. API cần dùng

### 1.1 Xem danh sách công ty đang thuộc về

```http
GET /api/v1/roles/me
Authorization: Bearer {accessToken}
```

Trả về **mọi vai trò đang active** của người dùng, gộp cả nhiều tenant trong 1 lần gọi — đủ dữ liệu để dựng danh sách/dropdown chọn công ty, không cần gọi thêm API nào khác:

```json
[
  {
    "id": "...", "userId": "...", "roleId": "...",
    "roleName": "EMPLOYEE",
    "tenantId": "f0f65c02-...", "tenantName": "Công ty CP Logistics Phương Nam", "tenantSlug": "gamma-logistics",
    "assignedAt": "...",
    "permissions": ["attendance:read", "checkins:create", "checkins:read", ...],
    "siteIds": [], "sites": []
  },
  {
    "roleName": "EMPLOYEE",
    "tenantId": "82c61db5-...", "tenantName": "Công ty Khởi nghiệp Tia Sáng", "tenantSlug": "tia-sang-startup",
    ...
  }
]
```

**Cách dùng**: gom theo `tenantId` (1 người có thể có nhiều role/vai trò cùng 1 tenant trong 1 số trường hợp — nên group by tenantId trước khi hiện danh sách). Nếu response chỉ có **đúng 1 tenantId duy nhất** → không cần hiện UI chọn công ty, vào thẳng app như bình thường. Nếu có **≥ 2 tenantId khác nhau** → hiện màn "Chọn công ty" (login) hoặc nút "Chuyển công ty" (trong app).

### 1.2 Chuyển công ty đang hoạt động

```http
POST /api/v1/auth/switch-tenant
Authorization: Bearer {accessToken hiện tại}
Content-Type: application/json

{ "tenantId": "82c61db5-...", "refreshToken": "{refreshToken hiện tại}" }
```

**Lưu ý quan trọng — cần CẢ 2**: access token hiện tại trong header (`Authorization`) VÀ refresh token hiện tại trong body — thiếu 1 trong 2 sẽ trả `401`/`Unauthorized` không rõ ràng (đã tự test và bị nhầm 1 lần khi thiếu header).

Response giống hệt `POST /auth/login` — 1 cặp token MỚI đã gắn sẵn tenant vừa chọn:

```json
{
  "userId": "...", "activeTenantId": "82c61db5-...",
  "accessToken": "...", "refreshToken": "...",
  "tokenType": "Bearer", "expiresIn": 900
}
```

**Việc App/Web phải làm ngay sau khi switch thành công**:
1. **Ghi đè** cả `accessToken` VÀ `refreshToken` cũ bằng cặp mới (token cũ vẫn còn hạn dùng nhưng sẽ trỏ sai `tenantId`, dùng tiếp sẽ gây lẫn dữ liệu công ty).
2. **Xóa/refetch toàn bộ cache** đang hiển thị theo tenant cũ — danh sách site, ca làm, lịch sử chấm công, trạng thái Face ID, thông báo... Không có cơ chế tự động nào ở backend "thông báo" các màn hình cần load lại — trách nhiệm này thuộc về App/Web.
3. **Gọi lại `GET .../face-id` theo `employeeId` của tenant MỚI** — xem mục 4, đây là điểm dễ bị bỏ sót nhất.

### 1.3 Mã lỗi cần xử lý

| HTTP | Khi nào |
|---|---|
| 401 | Access token hoặc refresh token không hợp lệ/hết hạn — bắt đăng nhập lại từ đầu |
| 403 | Người dùng không còn vai trò active ở tenant muốn chuyển tới (ví dụ đã bị gỡ khỏi công ty đó) — ẩn công ty đó khỏi danh sách, gợi ý load lại `GET /roles/me` |
| 404 | `tenantId` không tồn tại (hiếm khi xảy ra nếu danh sách lấy đúng từ `/roles/me`) |

## 2. Luồng UI đề xuất

```text
Đăng nhập (POST /auth/login)
  → response có activeTenantId mặc định (tenant "cũ nhất" được gán vai trò, không nhất thiết là tenant người dùng muốn)
  → gọi GET /roles/me
      → chỉ 1 tenant  → vào thẳng app, không hỏi gì thêm
      → ≥ 2 tenant     → hiện màn "Chọn công ty làm việc" (danh sách tenantName + có thể hiện luôn roleName)
          → chọn 1 công ty → POST /auth/switch-tenant → lưu token mới → vào app

Trong lúc đang dùng app (không cần đăng xuất):
  → menu góc trên (thường cạnh tên/avatar) → "Chuyển công ty"
  → hiện lại danh sách từ GET /roles/me (gọi lại mỗi lần mở menu — vai trò có thể đã đổi)
  → chọn công ty khác → POST /auth/switch-tenant → lưu token mới → điều hướng về màn chính, refetch toàn bộ
```

## 3. Lưu ý kỹ thuật quan trọng — "switch" không phải là ranh giới bảo mật duy nhất

Đã tự kiểm chứng qua API thật: **quyền truy cập của mỗi API luôn được kiểm tra lại theo đúng `tenantId` trên URL path tại thời điểm gọi** (không chỉ dựa vào `tenantId` đang "active" trong token) — nghĩa là nếu người dùng có vai trò active ở CẢ 2 tenant, gọi thẳng API với `tenantId` khác trên URL (mà chưa từng bấm "chuyển công ty") **vẫn thành công**, vì mỗi truy vấn dữ liệu đều tự lọc lại theo đúng `(tenantId, employeeId)`/vai trò trong DB tại thời điểm gọi — đã test với tenant người dùng KHÔNG có vai trò gì (trả đúng `403`/`404`, không lộ dữ liệu).

**Ý nghĩa cho App/Web**: `switch-tenant` chủ yếu là tiện ích UX (đổi "công ty mặc định" gắn vào token, để không phải luôn truyền `tenantId` tường minh và để trải nghiệm nhất quán), **không phải** cơ chế duy nhất chặn truy cập chéo — bản thân backend đã tự chặn đúng theo từng tenant ở tầng dữ liệu rồi. App/Web vẫn nên dùng đúng `tenantId` hiện hành (từ token mới nhất) cho MỌI request để tránh trộn lẫn hiển thị dữ liệu 2 công ty trên cùng 1 màn hình — đây là lỗi UI dễ xảy ra nhất nếu không cẩn thận (ví dụ: 1 tab cũ vẫn giữ `tenantId` cũ trong state trong khi tab khác đã switch).

## 4. Face ID — nhắc lại: tách biệt hoàn toàn theo từng công ty

Đã xác nhận qua schema + code (`face_profiles` khóa `UNIQUE(tenant_id, employee_id)`, mọi truy vấn enroll/consent/checkin đều lọc theo cả 2 field):

- Đăng ký Face ID ở Công ty A **không có hiệu lực** ở Công ty B — người dùng phải đăng ký lại từ đầu (consent riêng, ảnh riêng, HR Công ty B duyệt riêng) khi làm ở công ty thứ 2.
- Sau khi `switch-tenant`, App **phải gọi lại `GET .../face-id`** (theo `employeeId` của tenant mới — `employeeId` cũng KHÁC nhau giữa 2 tenant, không dùng chung 1 `employeeId`) để biết đúng trạng thái Face ID ở công ty đang chọn — không được giữ nguyên trạng thái Face ID đã load từ công ty trước đó.
- Vì `employeeId` khác nhau giữa 2 tenant, mọi API cần `employeeId` (Face ID, chấm công, hồ sơ...) đều phải lấy lại `employeeId` MỚI sau khi switch — thường qua `GET /employees/me` hoặc tương đương của tenant mới, không tái sử dụng `employeeId` cũ.

## 5. Checklist bàn giao frontend

- [ ] Sau login: gọi `GET /roles/me`, nếu ≥ 2 `tenantId` khác nhau → hiện màn chọn công ty trước khi vào app.
- [ ] Dựng menu "Chuyển công ty" trong app (không chỉ lúc đăng nhập) — gọi lại `GET /roles/me` mỗi lần mở để luôn cập nhật.
- [ ] `POST /auth/switch-tenant` phải gửi CẢ access token (header) và refresh token (body) — thiếu 1 trong 2 sẽ lỗi `401` không rõ nguyên nhân.
- [ ] Sau khi switch: ghi đè token, xóa/refetch mọi cache theo tenant cũ (site, ca làm, lịch sử chấm công, thông báo), lấy lại `employeeId` mới, gọi lại trạng thái Face ID.
- [ ] Không giả định `employeeId` giữ nguyên giữa các công ty.
- [ ] Xử lý `403` khi switch (vai trò ở tenant đích đã bị gỡ) — ẩn công ty đó khỏi danh sách, không cho chọn lại cho tới khi `GET /roles/me` xác nhận lại.
