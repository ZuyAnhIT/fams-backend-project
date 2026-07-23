# FAMS — Danh sách vấn đề hệ thống (2026-07-22)

Nguồn: người dùng mô tả trực tiếp (file `Một Số Vấn Đề Hệ Thống.txt`, bị lỗi font khi upload — đã đọc lại theo ngữ nghĩa tiếng Việt). Danh sách này **tạm thời được ưu tiên xử lý trước** thứ tự tuần tự của `docs/BACKLOG.md` (đang dở Sprint 1 #1). Sau khi xử lý xong cả 14 mục sẽ quay lại backlog từ #2.

Quy trình áp dụng giống backlog: sửa backend trước → test đủ trường hợp (tự động + roadmap Swagger/UI) → viết comment/doc → chờ người dùng tự test xác nhận → sang mục tiếp theo.

Trạng thái: ⬜ Chưa làm · 🔧 Đang làm · ✅ Đã xong (người dùng đã tự test xác nhận)

---

- [x] **#1 — Bắt buộc xác thực OTP khi đăng ký bằng số điện thoại** 🔧 *(đã code + test tự động, chờ bạn tự test xác nhận)*
  Hiện tại người dùng có thể hoàn tất đăng ký SĐT mà chưa xác thực OTP. Yêu cầu: làm theo chuẩn thông thường — gửi OTP, tài khoản chỉ được kích hoạt/hoàn tất đăng ký sau khi xác thực đúng OTP còn hạn.

  **Cách đã sửa** (backend, chi tiết kỹ thuật đầy đủ ở `docs/reviews/backend/issues-2026-07-22.md`): tái sử dụng đúng cơ chế Firebase Phone Auth đã có sẵn cho đăng nhập SĐT (`FirebasePhoneLoginService`), thay vì hồi sinh `OtpService` cũ (dead code, không có SMS provider thật). `POST /api/v1/auth/register` với `phone` (không có `email`) giờ **bắt buộc** thêm field `firebaseIdToken` — client phải tự hoàn tất luồng OTP với Firebase (nhận OTP thật qua SMS, xác thực) rồi mới gửi ID token lên; backend xác thực token đó (`FirebasePhoneTokenVerifier`, dùng chung cho cả login và register) và đối chiếu đúng số điện thoại trước khi tạo tài khoản. Thiếu token → `400 PHONE_NOT_VERIFIED`; token sai/hết hạn → `401 INVALID_OTP`; Firebase chưa cấu hình trên server → `503`.
  Thêm cột `users.phone_verified` (migration V57) để lưu lại trạng thái đã xác thực thật.
  **Đã cập nhật**: `tests/auth/test_register.sh` (Test 4/4b/5 sửa lại theo hành vi mới), test thủ công mới `tests/auth/test_register_phone_otp.sh` (đi qua Firebase thật, theo đúng mẫu `test_otp_login.sh`).

- [x] **#2 — Gửi lại mã xác thực khi đăng ký email** 🔧 *(đã code + test tự động, chờ bạn tự test xác nhận)*
  Đăng ký bằng email hiện chưa có chức năng gửi lại mã/link xác thực nếu người dùng không nhận được hoặc mã hết hạn.

  **Cách đã sửa** (chi tiết ở `docs/reviews/backend/issues-2026-07-22.md`): thêm `POST /api/v1/auth/resend-verification` (`{email}`), theo đúng mẫu bảo mật đã có ở `forgot-password` — luôn trả 200 bất kể email có tồn tại/đã verify/bị rate-limit hay không (tránh dò email đã đăng ký), rate-limit 3 lần/10 phút mỗi email.

- [x] **#3 — Người dùng thuộc nhiều công ty + gói mặc định khi tự tạo công ty** 🔧 *(đã code + test tự động, chờ bạn tự test xác nhận)*
  Người dùng mới có thể tự tạo công ty (không chỉ qua invitation), được phép là thành viên của nhiều công ty cùng lúc, và đăng ký gói dịch vụ cho công ty đó. Công ty vừa tạo mặc định nhận gói **Free**.

  **Cách đã sửa** (chi tiết ở `docs/reviews/backend/issues-2026-07-22.md`): `POST /api/v1/tenants` trước đây chỉ Platform Admin mới gọi được — giờ **bất kỳ user đã đăng nhập nào** cũng gọi được; nếu người gọi không phải Platform Admin, họ tự động được gán role `TENANT_ADMIN` cho công ty vừa tạo (trước đây hoàn toàn không có bước này — nếu user thường gọi được thì cũng không có quyền gì trên công ty vừa tạo). Không giới hạn số công ty một người có thể tạo/tham gia.
  ⚠️ **Lưu ý quan trọng**: hệ thống **không có plan tên "Free"** — chỉ có `trial` (giá 0, sort_order thấp nhất) được tự động gán mặc định (cơ chế này vốn đã có sẵn từ trước, không phải phần mình thêm mới). Mình đã dùng `trial` làm gói mặc định vì đó chính là gói miễn phí hiện có, thay vì tự ý thêm 1 plan "Free" mới trùng lặp. Nếu bạn muốn một gói Free *riêng biệt* với Trial (ví dụ Trial có giới hạn thời gian dùng thử còn Free thì dùng vĩnh viễn), báo lại để mình thêm plan mới — đây là quyết định nghiệp vụ nên mình chưa tự ý mở rộng.

- [x] **#4 — Bổ sung trường hồ sơ người dùng** 🔧 *(đã code + test tự động, chờ bạn tự test xác nhận)*
  Thêm các trường: quê quán, năm sinh, cho phép tải ảnh đại diện từ thiết bị cá nhân, và các thông tin hồ sơ khác cần thiết.

  **Cách đã sửa** (chi tiết ở `docs/reviews/backend/issues-2026-07-22.md`): thêm 4 trường `dateOfBirth, hometown, gender, address` vào profile (`PATCH /api/v1/auth/me`). Thêm **endpoint upload ảnh thật** `POST /api/v1/auth/profile/avatar` (multipart, tối đa 5MB, JPEG/PNG/WEBP) — trước đây `avatarUrl` chỉ nhận URL có sẵn do client tự host, không có cách nào upload file thật từ máy.
  ✅ **Đã cập nhật theo yêu cầu của bạn**: chuyển từ lưu local disk sang **S3-compatible object storage** (dùng chung code cho cả MinIO ở dev và AWS S3 thật ở production — chỉ khác biến môi trường, không đổi code). Dev/self-host dùng container MinIO đi kèm; khi triển khai thật chỉ cần bỏ trống `S3_ENDPOINT` và điền thông tin AWS S3 thật (bucket/region/access key) là chạy được ngay, không cần sửa gì thêm.



- [x] **#5 — Sửa lỗi bảo mật 2 lớp (2FA)** 🔧 *(đã code + test tự động, chờ bạn tự test xác nhận)*
  Đã khảo sát kỹ và tìm ra 3 lỗi bảo mật thật (không phải 1 lỗi mơ hồ như mô tả ban đầu):

  1. **Nghiêm trọng nhất**: `POST /totp/disable` trước đây **không yêu cầu xác thực lại** — chỉ cần JWT hợp lệ là tắt được 2FA. Nghĩa là nếu ai đó chiếm được token đăng nhập (XSS, rò rỉ, thiết bị bị mất), họ có thể tắt hẳn lớp bảo vệ 2FA mà không cần chứng minh gì thêm. Giờ bắt buộc phải cung cấp đúng 1 trong 3: mật khẩu hiện tại, mã TOTP còn hiệu lực, hoặc 1 backup code.
  2. `totp_secret` trước đây **lưu plaintext** trong DB — rò rỉ backup/DB là lộ toàn bộ seed 2FA của mọi người dùng vĩnh viễn (secret TOTP không xoay vòng được như mật khẩu). Giờ mã hóa AES-256-GCM khi lưu.
  3. **Không có backup codes** — nếu mất điện thoại cài authenticator, cách duy nhất để gỡ 2FA là... đăng nhập, mà không đăng nhập được nếu không có 2FA → khóa tài khoản vĩnh viễn, không có đường phục hồi. Giờ mỗi lần bật 2FA sẽ nhận 8 mã dự phòng dùng 1 lần.

  **Cách đã sửa** (chi tiết ở `docs/reviews/backend/issues-2026-07-22.md`): mã hóa secret bằng `TotpSecretCipher` (AES-256-GCM); `POST /totp/verify` (bật 2FA) giờ trả về 8 backup code (hiện đúng 1 lần, phải lưu lại); `POST /totp/disable` bắt buộc `password`/`code`/`backupCode`; `POST /login/totp` chấp nhận `backupCode` thay cho `code` khi mất thiết bị.
  ⚠️ Đã tìm và sửa luôn 1 sự cố phát sinh: khi test, tài khoản `admin@fams.com` bị kẹt ở trạng thái "2FA đang bật" từ 1 lần chạy test trước đó bị gián đoạn — đã reset lại và sửa bước cleanup của `test_login_totp.sh` để nó luôn tự dọn dẹp đúng cách với yêu cầu xác thực lại mới.

- [x] **#6 — Quản lý thiết bị/phiên đăng nhập** 🔧 *(đã code + test tự động, chờ bạn tự test xác nhận)*
  Xem danh sách thiết bị đang đăng nhập/phiên hoạt động, đăng xuất một thiết bị bất kỳ hoặc toàn bộ — tính năng đang lỗi, cần bổ sung hoàn thiện.

  **Thực tế phát hiện qua khảo sát**: tính năng này **chưa hề tồn tại**, không phải "đang lỗi" — không có endpoint nào xem danh sách phiên, không thể đăng xuất 1 thiết bị cụ thể (chỉ đăng xuất được chính phiên đang cầm), và "đăng xuất tất cả" luôn kèm đăng xuất cả phiên hiện tại (không có tùy chọn giữ lại). Không có dữ liệu hiển thị (không biết thiết bị nào, lần cuối hoạt động khi nào).
  **Đã thêm**: `GET /api/v1/auth/sessions` (danh sách phiên + user-agent/IP/lần hoạt động cuối, đánh dấu phiên hiện tại), `DELETE /api/v1/auth/sessions/{id}` (đăng xuất đúng 1 thiết bị bất kỳ, không cần đang cầm thiết bị đó), `POST /api/v1/auth/logout/others` (đăng xuất mọi nơi khác, giữ nguyên phiên hiện tại).

- [x] **#7 — Liên kết tài khoản Google 2 chiều** 🔧 *(đã code + test tự động, chờ bạn tự test xác nhận)*
  (a) Người dùng đăng ký/đăng nhập thường (email+password) có thể gắn thêm tài khoản Google để lần sau đăng nhập nhanh bằng Google.
  (b) Ngược lại, người dùng đăng nhập lần đầu bằng Google (chưa từng đặt mật khẩu) muốn chuyển sang đăng nhập thường — dùng chức năng "Quên mật khẩu" để đặt mật khẩu mới cho chính tài khoản đó.

  **Phát hiện qua khảo sát**: chiều (b) **đã hoạt động sẵn 100%**, không cần sửa gì — `PasswordResetService` vốn không phân biệt tài khoản có mật khẩu hay không, và Spring Security tự xử lý an toàn trường hợp mật khẩu chưa từng được đặt (không crash). Đã viết test xác nhận + chặn regression sau này. Chiều (a) cũng đã có sẵn dạng **ngầm** (đăng nhập Google với email trùng tài khoản cũ tự động gắn `googleId`) — bổ sung thêm 2 endpoint **chủ động**: `POST /api/v1/auth/link-google` (đang đăng nhập thường, chủ động gắn Google) và `POST /api/v1/auth/unlink-google` (gỡ liên kết, chặn nếu tài khoản chưa có mật khẩu để tránh mất hết cách đăng nhập). Thêm `googleLinked` vào profile để hiển thị trạng thái.

- [x] **#8 — Di chuyển công ty khi khóa/tắt gói dịch vụ** 🔧 *(đã code + test tự động, chờ bạn tự test xác nhận)*
  Khi Platform Admin khóa hoặc tắt một gói dịch vụ, phải chuyển các công ty đang dùng gói đó sang gói khác **trước**, tránh xung đột dữ liệu (ví dụ vượt hạn mức của gói mới nếu không kiểm tra).

  **Phát hiện qua khảo sát**: trước đây tắt gói (`isActive=false`) **không có tác động gì** tới các công ty đang dùng — họ tiếp tục hoạt động bình thường với hạn mức gói cũ vĩnh viễn, không hề bị "chuyển" hay "chặn" gì cả (bản thân việc tắt gói chỉ ẩn nó khỏi danh sách gói mới).
  **Cách đã sửa**: `PATCH /api/v1/plans/{id}` với `isActive=false` giờ kiểm tra còn công ty nào đang dùng gói này (`TRIAL`/`ACTIVE`) không — nếu có, **bắt buộc** phải kèm `migrateToPlanId` (gói chuyển đến) trong cùng request; hệ thống kiểm tra hạn mức hiện tại của từng công ty (số nhân viên, số công trình) so với gói mới, **từ chối toàn bộ thao tác** (không làm dở dang) nếu bất kỳ công ty nào sẽ vượt hạn mức gói mới, kèm thông báo rõ tên công ty + lý do; chỉ khi tất cả đều vừa hạn mức mới thực sự chuyển toàn bộ công ty sang gói mới rồi mới tắt gói cũ.
  **Test**: `tests/subscription/test_plan_deactivation_migration.sh` (8/8 pass) — bao gồm: tắt gói không còn công ty nào dùng (thành công ngay), thiếu `migrateToPlanId` (409), gói đến trùng gói đi (409), gói đến đang bị tắt (409), vượt hạn mức gói mới (409, không có tác dụng phụ), và chuyển thành công khi đủ điều kiện.

- [ ] **#9 — Phân trang: nhảy tới số trang** *(xác nhận backend đã hỗ trợ sẵn, việc còn lại thuần ở frontend)*
  Cho phép nhập/chọn số trang để nhảy tới thẳng, thay vì chỉ có nút Next từng trang một.

  **Phát hiện qua khảo sát**: backend (Spring Data `Pageable`, ví dụ `EmployeeController`/`EmployeeInvitationController`) đã nhận `page` là số bất kỳ qua query param — nhảy tới trang N chỉ cần gọi `?page=N`, không cần sửa gì ở backend. Đây thuần túy là thiếu control "nhập số trang" ở giao diện, để dành xử lý ở giai đoạn làm frontend.

- [x] **#10 — Thêm vai trò "Nhân viên nền tảng/hệ thống"** 🔧 *(đã code + test tự động, chờ bạn tự test xác nhận)*
  Vai trò mới dưới quyền Platform Admin, phạm vi quyền hẹp hơn — tự đề xuất bộ quyền hợp lý (ví dụ: xem/duyệt tenant, hỗ trợ vận hành, không có quyền xóa/thay đổi cấu hình nhạy cảm).

  **Phát hiện qua khảo sát**: hệ thống RBAC theo tenant (`roles`/`permissions`/`role_permissions`) đã có sẵn và đủ tổng quát, nhưng vai trò **cấp platform** (Platform Admin) lại là 1 cờ boolean riêng (`users.is_platform_admin`) hoàn toàn tách biệt khỏi hệ thống quyền — không có cách nào gán 1 vai trò "toàn nền tảng nhưng hẹp hơn" cho user, vì `user_roles.tenant_id` trước đây bắt buộc phải có (mọi vai trò đều phải gắn với 1 công ty cụ thể).
  **Bộ quyền đề xuất** (đã chọn theo đúng gợi ý trong yêu cầu — chỉ xem, không có quyền xóa/sửa cấu hình nhạy cảm): xem danh sách + chi tiết công ty (`tenants:list`, `tenants:read`), xem nhật ký audit toàn hệ thống (`audit:list`, `audit:read`), xem trạng thái vận hành hệ thống (`system:read`, quyền mới bổ sung). **Không** có quyền tạo/khóa/hủy công ty, không đổi gói dịch vụ, không sửa role/permission — các thao tác này vẫn chỉ Platform Admin làm được.
  **Cách đã làm**: cho phép `user_roles.tenant_id` được để trống (NULL) để biểu diễn "vai trò áp dụng toàn nền tảng, không thuộc công ty nào" — thêm index riêng đảm bảo không gán trùng vai trò platform 2 lần. Seed vai trò hệ thống mới `PLATFORM_STAFF`. Thêm endpoint riêng `POST /api/v1/user-roles/platform` (chỉ Platform Admin gọi được) để gán vai trò này — endpoint gán vai trò theo tenant hiện có không đổi, không ảnh hưởng. 3 endpoint xem (danh sách công ty, chi tiết công ty, trạng thái hệ thống) được nới thêm điều kiện "hoặc có quyền tương ứng" bên cạnh điều kiện Platform Admin cũ — **không xóa** điều kiện cũ nên không ảnh hưởng hành vi hiện tại của Platform Admin.
  **Test**: `tests/rbac/test_platform_staff_role.sh` (9/9 pass) — bao gồm: chưa gán thì bị từ chối, người thường không tự gán được, gán trùng bị chặn, sau khi gán xem được đúng 3 nhóm dữ liệu nhưng vẫn bị chặn thao tác nhạy cảm (khóa công ty), và sau khi thu hồi quyền bị chặn lại ngay (không có khoảng trễ do cache).

- [x] **#11 — Company Admin tự custom quyền nhân viên trong công ty** ✅ *(đã có sẵn từ trước, xác nhận qua test thật)*
  Company Admin có thể tùy chỉnh tập quyền cho từng nhân viên/role trong phạm vi công ty của họ (không ảnh hưởng tenant khác).

  **Phát hiện qua khảo sát**: tính năng này **đã được xây dựng đầy đủ từ trước**, không cần sửa gì thêm. `roles.tenant_id` (nullable) đã phân biệt sẵn role hệ thống vs role riêng từng công ty; `POST/PUT/DELETE /api/v1/roles` đã cho phép Company Admin tạo/sửa/xóa role tùy chỉnh + gán tập quyền tùy ý, có kiểm tra cách ly đúng theo tenant (role hệ thống như `TENANT_ADMIN` không sửa/xóa được, role tự tạo chỉ ảnh hưởng đúng công ty đó). Phần lớn các endpoint nghiệp vụ (Employee, Shift, Site, Assignment, Checkin...) đã kiểm tra theo chuỗi quyền cụ thể (`hasAuthority('resource:action')`) chứ không hard-code theo tên role, nên role tùy chỉnh hoạt động đúng ngay mà không cần sửa code ở nơi khác.
  **Test xác nhận lại**: `tests/rbac/test_create_role.sh` (10/10), `test_update_role.sh` (12/12), `test_delete_role.sh` (7/7), `test_assign_role.sh` (11/11), `test_revoke_role.sh` (6/6) — tất cả đã pass sẵn, không cần sửa.

- [x] **#12 — Sửa email mời làm chủ công ty gửi nhầm người** 🔧 *(đã code + test tự động, chờ bạn tự test xác nhận)*
  Khi tạo công ty và mời một người khác làm chủ (người được mời chưa phải là chủ), email mời hiện đang gửi nhầm vào hộp thư của người tạo (admin hệ thống) thay vì người được mời.

  **Phát hiện qua khảo sát**: đây **không phải bug** — tính năng "mời người khác làm chủ khi tạo công ty" **chưa hề tồn tại** trong code (không có field nào để chỉ định người khác làm chủ; `POST /api/v1/tenants` luôn coi người gọi API là chủ). Cơ chế mời nhân viên hiện có (`EmployeeInvitationService`) vốn đã gửi đúng email người được mời, không có lỗi gửi nhầm ở đó.
  **Cách đã làm** (xây mới, không phải sửa lỗi): thêm field tuỳ chọn `ownerEmail` vào `POST /api/v1/tenants`. Nếu để trống hoặc trùng chính email người tạo — hành vi y hệt trước đây (người tạo tự động thành admin). Nếu điền email người khác — người tạo **vẫn giữ quyền TENANT_ADMIN** như bình thường (để công ty không bao giờ "không ai quản lý" trong lúc chờ), **đồng thời** hệ thống gửi lời mời TENANT_ADMIN thật (tái dùng đúng `EmployeeInvitationService` đã có, đảm bảo gửi đúng email người được mời) cho người được chỉ định.
  **Test**: `tests/tenant/test_owner_invitation.sh` (6/6 pass) — xác nhận: người tạo vẫn có quyền, lời mời đúng gửi tới email người được mời (kiểm tra cả DB lẫn log gửi mail thật), trùng email tự bỏ qua không mời trùng, và không điền `ownerEmail` vẫn hoạt động y hệt trước (không hồi quy).

- [ ] **#13 — Xác nhận lời mời vào công ty trên Mobile App**
  Màn hình/luồng xác nhận (chấp nhận/từ chối) lời mời tham gia công ty hiện chưa có trên Mobile App.

- [x] **#14 — Tạo ca làm việc theo ngày cụ thể / đặt lịch lặp lại** 🔧 *(đã code + test tự động, chờ bạn tự test xác nhận)*
  Hiện tại tạo ca làm việc chưa hỗ trợ chọn ngày cụ thể hoặc thiết lập lịch lặp lại (recurring), chỉ có ca dùng chung.

  **Phát hiện qua khảo sát**: `Shift` (ca làm việc) là template thời gian thuần túy (`startTime`/`endTime`, không có khái niệm ngày/lặp lại) — dùng chung cho nhiều nhân viên/ngày khác nhau, **không phải** nơi hợp lý để thêm tính năng lặp lại. `Assignment` (phân công nhân viên vào công trình) mới là nơi gắn nhân viên↔công trình↔ca theo một khoảng ngày (`startDate`/`endDate`), nhưng coi mọi ngày trong khoảng đó như nhau (7 ngày/tuần), chưa có khái niệm "chỉ áp dụng các ngày trong tuần cụ thể". Đây cũng chính là cơ chế đang quyết định nhân viên có được check-in ở công trình nào hôm nay hay không (`CheckinService.getAvailableSites`), nên đây là điểm cần bổ sung, không phải Shift.
  **Cách đã làm**: thêm cột `assignments.days_of_week` (bitmask `SMALLINT`, migration V63) — bit 0 = Thứ Hai .. bit 6 = Chủ Nhật, giá trị 1-127; **NULL nghĩa là mọi ngày** (mặc định, giữ nguyên hành vi cũ 100% cho toàn bộ assignment hiện có, tương thích ngược tuyệt đối). API nhận/trả dạng dễ đọc `Set<DayOfWeek>` (ví dụ `["MONDAY","WEDNESDAY","FRIDAY"]`) ở cả `POST`/`PUT` assignment và response, tự chuyển đổi qua lại bitmask ở tầng service (`DayOfWeekBitmask`). Mảng rỗng bị từ chối (400) — không cho phép assignment "không hoạt động ngày nào"; dùng `clearDaysOfWeek=true` khi update để quay lại "mọi ngày".
  Cả 6 câu query đang lọc theo khoảng ngày trong `AssignmentRepository` (quyết định assignment có "đang hoạt động hôm nay" hay không — dùng bởi check-in, báo cáo, dashboard, chấm công random) được chuyển sang native SQL để dùng toán tử bitwise `&` của Postgres đối chiếu bitmask với ngày-trong-tuần của ngày truy vấn (`days_of_week IS NULL OR (days_of_week & :dayBit) <> 0`) — JPQL không có toán tử bitwise nên không thể giữ nguyên dạng JPQL cũ.
  **Test**: `tests/site/test_assignment_recurring_schedule.sh` (10/10 pass) — assignment giới hạn "mọi ngày trừ hôm nay" đúng là không xuất hiện trong danh sách công trình có thể check-in hôm nay; response trả về đúng tập ngày đã lưu; `clearDaysOfWeek` khôi phục lại "mọi ngày" và assignment xuất hiện lại ngay; assignment giới hạn "chỉ hôm nay" xuất hiện đúng hôm nay; bỏ trống `daysOfWeek` hoàn toàn vẫn mặc định là mọi ngày (chống hồi quy); mảng rỗng bị từ chối 400.
  Đã chạy lại toàn bộ test hồi quy liên quan, không hồi quy nào phát sinh: `test_list_assignments.sh` (13/13), `test_cancel_assignment.sh` (9/9), `test_shift_ot_config.sh` (12/12), `test_update_shift.sh` (14/14), `test_available_sites.sh` (6/6), và 7 bộ test check-in còn lại (`test_checkin_history`, `test_checkout`, `test_early_checkin`, `test_checkin_result`, `test_employee_explanation`, `test_hr_checkin_detail`, `test_hr_list_checkins`, `test_override_checkin`) đều pass 100%.
  4 file test khác (`test_create_assignment.sh`, `test_create_shift.sh`, `test_update_assignment.sh`, `test_basic_checkin.sh`) dừng giữa chừng ở đúng 1 bước fixture riêng của chính chúng ("tạo thêm 1 công trình thứ 2 để test") — đã xác minh trực tiếp bằng curl: đây là do gói dùng thử (`trial`) giới hạn **1 công trình/tenant** (`plan_limits.max_sites=1`) chặn `POST .../sites` lần 2 (`422 PLAN_LIMIT_EXCEEDED`), một giới hạn có sẵn từ trước (không phải do thay đổi lần này), khiến các script này (dùng `set -euo pipefail`) dừng sớm. Toàn bộ các bước test *trước* bước đó (bao gồm cập nhật ngày/vai trò/ca làm việc — cùng code path với thay đổi lần này) đều pass. Không sửa trong phạm vi #14 vì không liên quan tới tính năng lặp lịch; cần xử lý riêng nếu muốn (ví dụ: các test này tự tạo tenant dùng gói cao hơn thay vì mặc định `trial`).
