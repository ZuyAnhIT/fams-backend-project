# Tài liệu tích hợp Quản lý Nhân viên — Review, sửa lỗi, bổ sung và API tham chiếu

> Cập nhật theo code đang chạy ngày 25/07/2026. Base path: `/api/v1/tenants/{tenantId}/employees`, `/api/v1/tenants/{tenantId}/invitations`, `/api/v1/invitations`, `/api/v1/platform/invitations`, `/api/v1/platform-invitations`.

## 0. Tóm tắt kết quả

**9 tính năng bạn liệt kê đã được xây dựng gần như đầy đủ từ trước** — mời/chấp nhận/hủy lời mời, danh sách/chi tiết/tạo/sửa/đổi trạng thái/import/export nhân viên đều đã có API thật, không phải khung sườn rỗng. Qua review, tôi tìm thấy **2 lỗi thật** (1 bảo mật — lộ token lời mời; 1 dữ liệu — tạo trùng hồ sơ nhân viên khi chuyển từ "tạo thủ công" sang "mời qua email"), **1 khoảng trống rõ ràng so với yêu cầu** (chi tiết nhân viên thiếu workspace/assignment dù cả 2 module đã tồn tại), **1 điểm thiếu nhất quán** (export không tôn trọng giới hạn site như list/detail), và **xây mới hoàn toàn 1 tính năng** (mời nhân viên nền tảng qua email — trước đây chưa hề tồn tại).

| # | Tính năng bạn yêu cầu | Trạng thái trước | Việc đã làm |
|---|---|---|---|
| 1 | Mời nhân viên bằng email (công ty) | ✅ Đã có, đúng nghiệp vụ | Xác nhận lại — có **lỗi bảo mật**, xem mục 2 |
| 1b | Mời nhân viên nền tảng bằng email | ❌ Chưa tồn tại | **✅ Xây mới hoàn toàn** — mục 4 |
| 2 | Chấp nhận lời mời | ✅ Đã có, đúng nghiệp vụ (cả 2 luồng: đã có tài khoản / chưa có) | Xác nhận lại, không cần sửa |
| 3 | Hủy lời mời | ✅ Đã có, đúng nghiệp vụ | Xác nhận lại, không cần sửa |
| 4 | Danh sách nhân viên (tìm/lọc/sort/phân trang, tách biệt theo tenant) | ✅ Đã có, đúng nghiệp vụ, đã áp site-scope | Xác nhận lại, không cần sửa |
| 5 | Chi tiết nhân viên (hồ sơ, workspace, role, assignment, Face ID) | ⚠️ Role + Face ID thật, **workspace/assignment giả** (luôn rỗng) | **Đã sửa** — mục 2.2 |
| 6 | Tạo nhân viên thủ công | ✅ Đã có, đúng nghiệp vụ — **nhưng có lỗi tạo trùng khi chuyển sang mời sau đó** | **Đã sửa** — mục 2.4; trả lời câu hỏi "có trùng với mời không" — mục 1.1 |
| 7 | Cập nhật nhân viên | ✅ Đã có, đúng nghiệp vụ | Xác nhận lại, không cần sửa |
| 8 | Tạm ngừng/nghỉ việc nhân viên | ✅ Đã có, đúng nghiệp vụ | Xác nhận lại, không cần sửa |
| 9 | Import Excel | ✅ Đã có, đúng nghiệp vụ | Xác nhận lại + trả lời câu hỏi "có trùng với mời không" — mục 3.2 |
| 10 | Export Excel | ⚠️ Có lỗ hổng site-scope | **Đã sửa** — mục 2.3 |

**Kết quả test**: build lại, test sống từng lỗi/tính năng mới, chạy lại toàn bộ `tests/employee/*.sh` (13 file, 92 test) + `tests/rbac/*.sh` (11 file) + `tests/tenant/*.sh` (9 file) — **100% pass**, không hồi quy.

## 1. Trả lời câu hỏi nghiệp vụ: các tính năng có trùng nhau không?

### 1.1 "Tạo nhân viên thủ công" vs "Mời nhân viên qua email" — KHÔNG trùng, nên giữ cả hai

Đối chiếu các hệ thống thực tế cùng ngành (Deputy, Connecteam, Employment Hero — đều là workforce management cho lao động phổ thông/công trường, giống FAMS):

| | Tạo thủ công | Mời qua email |
|---|---|---|
| Tạo ra gì | Chỉ hồ sơ nhân viên (`Employee` record) | Hồ sơ nhân viên **+** tài khoản đăng nhập (`User`) |
| Nhân viên có đăng nhập được không | **Không** — `userId` để trống, không có mật khẩu | **Có** — họ tự đặt mật khẩu khi chấp nhận |
| Dùng khi nào | Công nhân/lao công trường không cần tự chấm công qua app, HR chỉ cần lưu hồ sơ để lên lịch/theo dõi | Nhân viên văn phòng/giám sát cần tự đăng nhập xem lịch, chấm công qua app |
| Có email/SĐT bắt buộc không | Không — cả hai đều optional | Bắt buộc có email (SĐT optional) |

**Đây chính là 2 luồng riêng biệt mà Deputy/Connecteam đều làm** — không phải trùng lặp, mà là "worker record without login" và "invite to self-service" phục vụ 2 nhóm nhân sự khác nhau (rất phổ biến trong ngành xây dựng/công trường: quản lý cần thấy toàn bộ công nhân trên hệ thống, nhưng không phải ai cũng cần/có khả năng tự dùng app). **Khuyến nghị: giữ nguyên cả hai, không gộp.**

Code hiện tại đã tách đúng: `CreateEmployeeRequest` không có field mật khẩu nào; nhân viên tạo thủ công có `userId = null` cho tới khi được mời và tự chấp nhận riêng (dùng chung API mời — hệ thống tự nhận diện qua email đã có hồ sơ hay chưa).

### 1.2 "Import Excel" vs "Mời hàng loạt" — KHÔNG trùng, nhưng nên bổ sung 1 hành động nối tiếp

Import hiện tại **hoàn toàn không đụng tới tài khoản đăng nhập** — chỉ tạo hàng loạt `Employee` record thô, giống hệt "tạo thủ công" nhưng nhiều dòng cùng lúc (không gửi email, không tạo `User`). Đây đúng là hành vi hệ thống thực tế hay làm (BambooHR/Deputy: import CSV/Excel = nạp dữ liệu nhanh, KHÔNG tự động gửi lời mời hàng loạt trong cùng thao tác — gửi lời mời luôn là hành động tách biệt, có kiểm soát, tránh gửi nhầm hàng trăm email cùng lúc chỉ vì 1 file import sai).

**Không trùng nhau, nhưng có 1 khoảng trống thực tế đáng cân nhắc** (chưa làm trong đợt này, chỉ đề xuất): sau khi import xong danh sách nhân viên có email, HR thường muốn "gửi lời mời cho tất cả những người vừa import có email nhưng chưa có tài khoản" — hiện tại phải làm thủ công từng người qua `POST /invitations`. Nếu bạn cần, đây là bổ sung nhỏ (lặp lại `sendInvitation` cho từng dòng có email), báo tôi làm tiếp một bulk action riêng.

### 1.3 "Nhân viên nền tảng" — khái niệm khác hoàn toàn "nhân viên công ty"

Bạn dùng chung từ "nhân viên" cho cả 2 khái niệm khác nhau trong hệ thống:
- **Nhân viên công ty** = `Employee` entity, thuộc 1 tenant cụ thể, có thể có hoặc không có tài khoản đăng nhập.
- **Nhân viên nền tảng** = nhân sự nội bộ FAMS, không thuộc tenant nào, chỉ là 1 `User` + role cấp nền tảng (`tenantId IS NULL`) — không có `Employee` record, không có "workspace/assignment/Face ID" vì các khái niệm đó chỉ có ý nghĩa trong phạm vi 1 công ty khách hàng.

Do đó "danh sách nhân viên nền tảng" **không phải** là 1 tenant đặc biệt trong `GET /tenants/{id}/employees` — mà là `GET /api/v1/users?isPlatformAdmin=true` (đã có từ đợt review RBAC trước, xem `rbac-api.md` mục 7).

## 2. Chi tiết các lỗi đã sửa

### 2.1 [Đã sửa — bảo mật] Token lời mời bị lộ qua danh sách

**Trước khi sửa**: `GET /tenants/{tenantId}/invitations` (danh sách lời mời) trả về **cùng DTO** với `POST .../invitations` (gửi lời mời) — bao gồm cả field `token`. Bất kỳ ai có quyền `employees:read` (thấp hơn `employees:create` — có thể là 1 role chỉ-xem) đều đọc được token của MỌI lời mời đang chờ, và tự dùng token đó để chấp nhận thay cho người được mời thật sự — chiếm quyền onboard vào công ty.

**Đã sửa**: token giờ chỉ xuất hiện trong response của `POST` (lúc tạo) — `GET` (danh sách) và `DELETE` (hủy) luôn trả `token: null`. Test sống xác nhận: gửi lời mời → thấy token; liệt kê lại → token đã về `null`.

### 2.2 [Đã sửa — thiếu tính năng] Chi tiết nhân viên: workspace và assignment giờ là dữ liệu thật

**Trước khi sửa**: `GET /employees/{id}` trả `workspaces: []` và `assignments: []` **luôn luôn rỗng**, bất kể nhân viên đó thực sự có tham gia workspace hay có assignment nào hay không — mã nguồn hardcode `Collections.emptyList()` dù cả module Workspace lẫn Assignment đều đã tồn tại và hoạt động đầy đủ ở nơi khác trong hệ thống. Đây là khoảng trống trực tiếp so với yêu cầu của bạn: *"tôi muốn xem hồ sơ, workspace, role, assignment và Face ID của nhân viên để nắm đầy đủ thông tin nhân sự"*.

**Đã sửa**: 2 field này giờ trả dữ liệu thật —
- `workspaces`: danh sách workspace nhân viên tham gia, kèm tên workspace đã resolve sẵn (`workspaceName`), vai trò trong workspace, và ngày tham gia.
- `assignments`: toàn bộ lịch sử assignment (site/shift) của nhân viên, mới nhất trước, không giới hạn theo ngày/trạng thái như các API "đang active" khác.

Test sống xác nhận: tạo 1 nhân viên, thêm vào 1 workspace, tạo 1 assignment vào 1 site → gọi lại chi tiết nhân viên thấy đầy đủ cả 2, đúng tên/thông tin.

### 2.3 [Đã sửa — thiếu nhất quán] Export không tôn trọng giới hạn theo site

**Trước khi sửa**: `GET /employees/export` chỉ kiểm tra quyền `employees:list` cấp tenant, **không** áp dụng giới hạn site như `GET /employees` (danh sách) và `GET /employees/{id}` (chi tiết) đã làm từ đợt review RBAC trước. Một `SITE_SUPERVISOR` bị giới hạn chỉ 1 site vẫn có thể export **toàn bộ** nhân viên của cả công ty ra file Excel — biến export thành đường vòng qua giới hạn site chỉ vì nó không phân trang.

**Đã sửa**: áp dụng đúng cơ chế `SiteScopeService` như list/detail. Test sống: tạo 2 site, 1 supervisor giới hạn Site A, export ra file chỉ thấy nhân viên Site A, không thấy nhân viên Site B.

### 2.4 [Đã sửa — dữ liệu] Tạo trùng hồ sơ khi "tạo thủ công" rồi "mời" cùng 1 người

**Phát hiện qua chính câu hỏi của bạn** về ý nghĩa của "tạo thủ công": khi HR tạo thủ công 1 nhân viên (chưa có tài khoản), rồi sau đó mời đúng email đó và người này chấp nhận, hệ thống **tạo ra một `Employee` record hoàn toàn mới** thay vì nối vào record cũ — vì lúc đó chỉ kiểm tra "user này đã có record chưa" (theo `userId`), không kiểm tra "email này đã có record thủ công nào đang chờ chưa" (theo `email`). Hậu quả: record thủ công ban đầu (cùng mọi assignment/workspace/Face ID đã gắn vào nó) bị bỏ lại mồ côi, hệ thống nhìn thành 2 người khác nhau — làm hỏng chính lý do nên giữ cả "tạo thủ công" lẫn "mời" (chuyển đổi từ cái này sang cái kia phải liền mạch).

Đáng chú ý: comment sẵn có trong code (`EmployeeController`) đã ghi rõ ý định ban đầu — *"not linked to an auth user account until they accept an invitation **or are matched manually**"* — nghĩa là việc đối chiếu này được dự tính từ đầu nhưng chưa từng cài đặt.

**Đã sửa**: khi chấp nhận lời mời, nếu đã có 1 `Employee` record cùng tenant + cùng email (không phân biệt hoa/thường) mà `userId` còn trống → **nối** tài khoản mới vào đúng record đó (giữ nguyên mọi dữ liệu HR đã nhập — tên, vị trí, phòng ban...), chỉ điền thêm `userId`. Chỉ tạo record mới khi thật sự chưa từng có ai giữ email đó.

Test sống xác nhận: tạo thủ công 1 nhân viên (có `position="Mason"`), mời đúng email đó, chấp nhận → chỉ còn **đúng 1** record, cùng `id` ban đầu, giữ nguyên `position="Mason"`, đã có `userId` mới.

### 2.5 [Cập nhật 2026-08-06] Che PII: đổi permission + thêm field `piiMasked`

`email`/`phone` trong `EmployeeResponse` (danh sách) và `EmployeeDetailResponse` (chi tiết) bị **che tự động** (`a***@congty.vn`, `***001`) trừ khi người gọi là `PLATFORM_ADMIN` hoặc giữ quyền **`employees:pii:read`** (permission chuyên biệt mới — trước 2026-08-06 dùng `users:create`, đã đổi theo phản hồi FE về nguyên tắc least-privilege; role nào từng có `users:create` đã được tự động cấp thêm `employees:pii:read` qua migration, không mất quyền đột ngột). File Excel export (`GET /employees/export`) áp dụng **đúng cùng quy tắc** — không còn lệch giữa JSON và Excel như trước.

Cả 2 response giờ có thêm field boolean **`piiMasked`** — `true` nếu `email`/`phone` trong response này đang bị che cho người gọi hiện tại. **Dùng field này để quyết định UI (ví dụ hiện icon "🔒 Cần quyền xem đầy đủ" cạnh field bị che), không tự suy luận bằng cách kiểm tra chuỗi có chứa `***` hay không** — quyết định che/không che luôn nằm ở Backend, field này chỉ là metadata tường minh để FE không phải đoán.

```json
{ "email": "a***@hoanglong.vn", "phone": "***001", "piiMasked": true, ... }
```

## 3. Tính năng mới: Mời nhân viên nền tảng qua email

### 3.1 Vì sao phải xây tách biệt, không dùng chung bảng với lời mời công ty

Bảng `employee_invitations` có cột `tenant_id NOT NULL` — không thể tái sử dụng cho lời mời không thuộc tenant nào mà không sửa toàn bộ query hiện có. Đã xây bảng/luồng riêng `platform_invitations`, kiến trúc giống hệt lời mời công ty (token, hạn dùng, trạng thái pending/accepted/cancelled/expired) nhưng không gắn tenant.

### 3.2 API

| Endpoint | Method | Ai gọi được | Mục đích |
|---|---|---|---|
| `/api/v1/platform/invitations` | GET | Chỉ Platform Admin | Danh sách lời mời (lọc theo status/email, phân trang) |
| `/api/v1/platform/invitations` | POST | Chỉ Platform Admin | Gửi lời mời qua email |
| `/api/v1/platform/invitations/{id}` | DELETE | Chỉ Platform Admin | Hủy lời mời đang chờ |
| `/api/v1/platform-invitations/validate` | GET | Công khai (không cần đăng nhập) | Kiểm tra token trước khi hiện form chấp nhận |
| `/api/v1/platform-invitations/accept` | POST | Công khai (không cần đăng nhập) | Chấp nhận lời mời — token là bằng chứng xác thực |

**Request gửi lời mời**:
```json
{
  "email": "newstaff@fams.com",
  "firstName": "An",
  "lastName": "Nguyen",
  "roleId": "<uuid role cấp nền tảng, bỏ trống = mặc định PLATFORM_STAFF>"
}
```

- `roleId` phải là role cấp nền tảng (`tenantId = null`) — gán nhầm role của 1 công ty cụ thể sẽ bị `400`.
- Giống hệt lời mời công ty: nếu email đã có tài khoản → chỉ gán thêm role, không cần mật khẩu; nếu email chưa có tài khoản → bắt buộc `password` khi chấp nhận để tạo tài khoản mới.
- **Không** làm luồng liên kết qua số điện thoại (có ở lời mời công ty) — không cần thiết cho onboarding nhân sự nội bộ, giữ đơn giản.
- Cùng áp dụng: token chỉ xuất hiện lúc tạo, ẩn khi liệt kê/hủy (học từ lỗi mục 2.1, làm đúng ngay từ đầu).

### 3.3 Test đã chạy

Test sống đầy đủ: user thường cố gửi lời mời → `403`; Platform Admin gửi → `201` kèm token; liệt kê lại → token ẩn; validate token công khai → đúng; chấp nhận tạo tài khoản mới → nhận JWT hợp lệ; tài khoản mới `GET /roles/me` thấy đúng `PLATFORM_STAFF`; dùng token đó gọi `GET /tenants` (quyền `tenants:list` của PLATFORM_STAFF) → `200`; gửi trùng email khi còn pending → `409`; hủy lời mời → `200`; hủy lại lần 2 → `422`.

## 4. Checklist bàn giao frontend

- [ ] Màn "Mời nhân viên" (Company Portal) và "Mời nhân sự nền tảng" (Admin Console) là 2 màn **riêng biệt**, dùng 2 nhóm endpoint khác nhau — không dùng chung 1 form.
- [ ] Nút "Tạo nhân viên thủ công" và "Mời qua email" cùng tồn tại trong màn Danh sách nhân viên — không gộp, xem mục 1.1 để biết khi nào dùng cái nào (gợi ý: label rõ "Thêm hồ sơ (chưa cần đăng nhập)" vs "Mời tham gia (gửi email)").
- [ ] Sau khi import Excel thành công, cân nhắc hiện gợi ý "Gửi lời mời cho N nhân viên vừa import có email" — hiện phải làm thủ công từng người, xem mục 1.2 nếu muốn làm bulk action.
- [ ] Màn chi tiết nhân viên giờ hiện đúng workspace/assignment thật — bỏ mọi placeholder "chưa hỗ trợ" nếu FE từng dựng tạm.
- [ ] Export nhân viên với tài khoản bị giới hạn site sẽ tự động chỉ xuất đúng phạm vi — không cần FE tự lọc thêm, nhưng nên hiện chú thích nhỏ "File chỉ gồm nhân viên thuộc site bạn phụ trách" nếu người dùng là site-scoped.
- [ ] Danh sách lời mời (cả 2 loại) không còn trả `token` — nếu FE có code cũ đọc `token` từ response list để tự dựng link, phải sửa lại chỉ dùng token từ response tạo mới (`POST`).
