# Đặc tả kết nối các Use Case Diagram FAMS

Tài liệu này chuyển toàn bộ các sơ đồ PlantUML thành mô tả dạng text để dựng lại thủ công.

## Quy ước đọc kết nối

- `A — kết nối — UC`: actor A tham gia hoặc khởi tạo use case UC; vẽ association bằng đường liền, không cần mũi tên.
- `A — kế thừa — B`: A là actor chuyên biệt của B; vẽ tam giác rỗng hướng về actor tổng quát B.
- `UC A — <<include>> → UC B`: A luôn sử dụng hành vi B trong ngữ cảnh được ghi chú; mũi tên nét đứt hướng về B.
- `UC A — <<extend>> → UC B`: A bổ sung có điều kiện cho B; mũi tên nét đứt hướng về use case cơ sở B.
- Nhãn `theo quyền`, `theo site`, `của bản thân` là constraint đặt cạnh association, không phải use case mới.

---

# Mức 1 — Use Case tổng quan theo nhóm actor

Tổng quan toàn hệ thống được tách thành hai sơ đồ theo ranh giới vận hành. Sơ đồ 1A chỉ có actor và mục tiêu cấp nền tảng; sơ đồ 1B chỉ có actor và mục tiêu của công ty sử dụng FAMS. `Người dùng đã xác thực` được lặp lại làm actor tổng quát cho quan hệ kế thừa, không làm hai phía bị gộp lại. Các use case dùng chung về tài khoản, xác thực và thông báo tiếp tục được giữ nguyên tại Mức 2.1 và Mức 2.8.

## Mức 1A — Phía nền tảng

Nguồn: `01a-platform-overview.puml`

### Actor

- Người dùng đã xác thực — actor tổng quát.
- Platform Admin.
- Platform Staff.

### Use case trong ranh giới FAMS

- Quản trị tenant và gói dịch vụ.
- Quản lý vai trò và quyền cấp nền tảng.
- Tra cứu audit và giám sát hệ thống.

### Quan hệ kế thừa actor

- Platform Admin — kế thừa — Người dùng đã xác thực.
- Platform Staff — kế thừa — Người dùng đã xác thực.

### Kết nối Platform Admin

- Platform Admin — kết nối — Quản trị tenant và gói dịch vụ.
- Platform Admin — kết nối — Quản lý vai trò và quyền cấp nền tảng.
- Platform Admin — kết nối — Tra cứu audit và giám sát hệ thống.

### Kết nối Platform Staff

- Platform Staff — kết nối — Quản trị tenant và gói dịch vụ — constraint: theo quyền được cấp.
- Platform Staff — kết nối — Tra cứu audit và giám sát hệ thống — constraint: theo quyền được cấp.

## Mức 1B — Phía công ty sử dụng

Nguồn: `01b-company-overview.puml`

### Actor

- Người dùng đã xác thực — actor tổng quát.
- Tenant Admin / Owner.
- HR Manager.
- Site Supervisor.
- Employee.
- Object Storage — hệ thống ngoài.

### Use case trong ranh giới FAMS

- Quản lý hồ sơ và cấu hình công ty.
- Quản lý vai trò và quyền trong công ty.
- Quản lý tổ chức và nhân sự.
- Quản lý site, geofence, ca và phân công.
- Quản lý Face ID.
- Chấm công và quản lý bảng công.
- Kiểm tra ngẫu nhiên và xử lý vi phạm.
- Theo dõi dashboard và lập báo cáo.
- Tra cứu audit tenant.

### Quan hệ kế thừa actor

- Tenant Admin / Owner — kế thừa — Người dùng đã xác thực.
- HR Manager — kế thừa — Người dùng đã xác thực.
- Site Supervisor — kế thừa — Người dùng đã xác thực.
- Employee — kế thừa — Người dùng đã xác thực.

Các vai trò phía công ty không kế thừa lẫn nhau; quan hệ với `Người dùng đã xác thực` chỉ biểu diễn các hành vi dùng chung sau đăng nhập.

### Kết nối Tenant Admin / Owner

- Tenant Admin / Owner — kết nối — Quản lý hồ sơ và cấu hình công ty.
- Tenant Admin / Owner — kết nối — Quản lý vai trò và quyền trong công ty.
- Tenant Admin / Owner — kết nối — Quản lý tổ chức và nhân sự.
- Tenant Admin / Owner — kết nối — Quản lý site, geofence, ca và phân công.
- Tenant Admin / Owner — kết nối — Quản lý Face ID.
- Tenant Admin / Owner — kết nối — Chấm công và quản lý bảng công.
- Tenant Admin / Owner — kết nối — Kiểm tra ngẫu nhiên và xử lý vi phạm.
- Tenant Admin / Owner — kết nối — Theo dõi dashboard và lập báo cáo.
- Tenant Admin / Owner — kết nối — Tra cứu audit tenant.

### Kết nối HR Manager

- HR Manager — kết nối — Quản lý tổ chức và nhân sự.
- HR Manager — kết nối — Quản lý site, geofence, ca và phân công.
- HR Manager — kết nối — Quản lý Face ID.
- HR Manager — kết nối — Chấm công và quản lý bảng công.
- HR Manager — kết nối — Kiểm tra ngẫu nhiên và xử lý vi phạm.
- HR Manager — kết nối — Theo dõi dashboard và lập báo cáo.
- HR Manager — kết nối — Tra cứu audit tenant — constraint: theo quyền được cấp.

### Kết nối Site Supervisor

- Site Supervisor — kết nối — Quản lý site, geofence, ca và phân công — constraint: chỉ đọc theo site.
- Site Supervisor — kết nối — Chấm công và quản lý bảng công — constraint: theo site được giao.
- Site Supervisor — kết nối — Kiểm tra ngẫu nhiên và xử lý vi phạm — constraint: theo site được giao.
- Site Supervisor — kết nối — Theo dõi dashboard và lập báo cáo — constraint: dashboard/báo cáo site.

### Kết nối Employee

- Employee — kết nối — Quản lý Face ID — constraint: dữ liệu cá nhân.
- Employee — kết nối — Chấm công và quản lý bảng công — constraint: chấm công/bảng công cá nhân.
- Employee — kết nối — Kiểm tra ngẫu nhiên và xử lý vi phạm — constraint: phản hồi/giải trình cá nhân.
- Employee — kết nối — Theo dõi dashboard và lập báo cáo — constraint: dashboard cá nhân.

### Hệ thống ngoài

- Object Storage — hỗ trợ — Quản lý Face ID.
- Object Storage — hỗ trợ — Kiểm tra ngẫu nhiên và xử lý vi phạm.

Hai sơ đồ tổng quan không dùng `include` hoặc `extend`; các quan hệ đó được phân rã ở Mức 2. Các sơ đồ `02-auth-account.puml` và `09-notification.puml` vẫn giữ nguyên vì mô tả năng lực dùng chung của toàn hệ thống.

---

# Mức 2.1 — Tài khoản và xác thực

Nguồn: `02-auth-account.puml`

## Actor

- Người dùng.
- Người dùng đã xác thực.
- Google Identity.
- Firebase Phone Auth.
- Dịch vụ Email.
- Object Storage.

## Kết nối actor với use case

### Người dùng

- Người dùng — kết nối — Đăng ký tài khoản.
- Người dùng — kết nối — Xác thực email.
- Người dùng — kết nối — Đăng nhập bằng email/mật khẩu.
- Người dùng — kết nối — Đăng nhập bằng Google.
- Người dùng — kết nối — Đăng nhập bằng số điện thoại.
- Người dùng — kết nối — Khôi phục mật khẩu.

### Người dùng đã xác thực

- Người dùng đã xác thực — kết nối — Đăng xuất thiết bị hiện tại.
- Người dùng đã xác thực — kết nối — Đăng xuất mọi thiết bị.
- Người dùng đã xác thực — kết nối — Xem/cập nhật hồ sơ.
- Người dùng đã xác thực — kết nối — Cập nhật ảnh đại diện.
- Người dùng đã xác thực — kết nối — Đổi mật khẩu.
- Người dùng đã xác thực — kết nối — Thiết lập/tắt TOTP 2FA.
- Người dùng đã xác thực — kết nối — Liên kết/hủy liên kết Google.
- Người dùng đã xác thực — kết nối — Cập nhật email/số điện thoại.
- Người dùng đã xác thực — kết nối — Chọn/chuyển tenant đang active.

### Hệ thống ngoài

- Google Identity — hỗ trợ — Xác minh Google ID token.
- Google Identity — hỗ trợ — Liên kết/hủy liên kết Google.
- Firebase Phone Auth — hỗ trợ — Xác minh Firebase ID token.
- Firebase Phone Auth — hỗ trợ — Cập nhật email/số điện thoại — phần xác minh số điện thoại.
- Dịch vụ Email — hỗ trợ — Xác thực email.
- Dịch vụ Email — hỗ trợ — Khôi phục mật khẩu.
- Dịch vụ Email — hỗ trợ — Cập nhật email/số điện thoại — phần xác minh email.
- Object Storage — hỗ trợ — Cập nhật ảnh đại diện.

## Quan hệ include

- Đăng nhập bằng Google — `<<include>>` → Xác minh Google ID token.
- Đăng nhập bằng số điện thoại — `<<include>>` → Xác minh Firebase ID token.

## Quan hệ extend

- Xác thực email — `<<extend>>` → Đăng ký tài khoản — điều kiện: đăng ký bằng email.
- Hoàn tất xác thực 2FA — `<<extend>>` → Đăng nhập bằng email/mật khẩu — điều kiện: tài khoản đã bật 2FA.
- Cập nhật ảnh đại diện — `<<extend>>` → Xem/cập nhật hồ sơ.

Ghi chú: client làm việc trực tiếp với Firebase để gửi và nhập OTP; FAMS chỉ nhận Firebase ID token cuối cùng để xác minh.

---

# Mức 2.2 — Governance tách theo hai phía

## Mức 2.2A — Quản trị phía nền tảng

Nguồn: `03a-platform-governance.puml`

### Actor

- Platform Admin.
- Platform Staff.
- Người được mời vào platform.
- Dịch vụ Email — hệ thống ngoài.

### Kết nối Platform Admin

- Platform Admin — kết nối — Tạo tenant.
- Platform Admin — kết nối — Xem danh sách/chi tiết tenant.
- Platform Admin — kết nối — Khóa, mở hoặc hủy tenant.
- Platform Admin — kết nối — Quản lý gói dịch vụ.
- Platform Admin — kết nối — Gán/thay đổi subscription.
- Platform Admin — kết nối — Quản lý role cấp nền tảng.
- Platform Admin — kết nối — Xem danh mục permission.
- Platform Admin — kết nối — Gán/thu hồi role cấp nền tảng.
- Platform Admin — kết nối — Mời/quản lý Platform Staff.
- Platform Admin — kết nối — Tra cứu audit log toàn hệ thống.
- Platform Admin — kết nối — Giám sát trạng thái hệ thống.
- Platform Admin — kết nối — Quản lý hồ sơ go-live.

### Kết nối Platform Staff và người được mời

- Platform Staff — kết nối — Tạo tenant — constraint: nếu được cấp quyền.
- Platform Staff — kết nối — Xem danh sách/chi tiết tenant — constraint: nếu được cấp quyền.
- Platform Staff — kết nối — Tra cứu audit log toàn hệ thống — constraint: nếu được cấp quyền.
- Platform Staff — kết nối — Quản lý hồ sơ go-live — constraint: nếu được cấp quyền.
- Người được mời vào platform — kết nối — Chấp nhận lời mời platform.

### Hệ thống ngoài

- Dịch vụ Email — hỗ trợ — Mời/quản lý Platform Staff.
- Dịch vụ Email — hỗ trợ — Chấp nhận lời mời platform.

### Quan hệ include

- Gán/thay đổi subscription — `<<include>>` → Kiểm tra giới hạn gói.

### Quan hệ extend

- Xem diff và trace request — `<<extend>>` → Tra cứu audit log toàn hệ thống.

Không nối “Mời Platform Staff” với “Chấp nhận lời mời” bằng `include`: đây là hai mục tiêu của hai actor khác nhau, xảy ra ở hai thời điểm khác nhau.

## Mức 2.2B — Quản trị phía công ty

Nguồn: `03b-company-governance.puml`

### Actor

- Tenant Admin / Owner.
- HR Manager.
- Thành viên tenant.

### Kết nối actor với use case

- Tenant Admin / Owner — kết nối — Tạo tenant self-service.
- Tenant Admin / Owner — kết nối — Cập nhật hồ sơ/cấu hình tenant.
- Tenant Admin / Owner — kết nối — Quản lý IP whitelist.
- Tenant Admin / Owner — kết nối — Quản lý role tùy chỉnh của tenant.
- Tenant Admin / Owner — kết nối — Xem danh mục permission.
- Tenant Admin / Owner — kết nối — Gán/thu hồi role trong tenant.
- Tenant Admin / Owner — kết nối — Tra cứu audit log trong tenant.
- Tenant Admin / Owner — kết nối — Quản lý hồ sơ go-live.
- HR Manager — kết nối — Tra cứu audit log trong tenant — constraint: nếu được cấp quyền.
- Thành viên tenant — kết nối — Xem quyền/vai trò của tôi.

### Quan hệ extend

- Xem diff và trace request — `<<extend>>` → Tra cứu audit log trong tenant.

Role hệ thống là bất biến. Tenant chỉ quản lý role tùy chỉnh và phép gán role trong tenant đang active; role cấp nền tảng không xuất hiện trong sơ đồ này.

---

# Mức 2.3 — Tổ chức, nhân sự, site và lịch làm

Nguồn: `04-workforce-organization.puml`

## Actor

- Tenant Admin / Owner.
- HR Manager.
- Site Supervisor.
- Employee.
- Người được mời.
- Dịch vụ Email.

## Kết nối actor với use case

### Tenant Admin / Owner

- Tenant Admin / Owner — kết nối — Quản lý hồ sơ nhân viên.
- Tenant Admin / Owner — kết nối — Tạo nhân viên thủ công.
- Tenant Admin / Owner — kết nối — Mời nhân viên.
- Tenant Admin / Owner — kết nối — Hủy lời mời.
- Tenant Admin / Owner — kết nối — Import nhân viên.
- Tenant Admin / Owner — kết nối — Export nhân viên.
- Tenant Admin / Owner — kết nối — Tạm ngừng/chấm dứt nhân viên.
- Tenant Admin / Owner — kết nối — Quản lý workspace.
- Tenant Admin / Owner — kết nối — Gán/chuyển thành viên workspace.
- Tenant Admin / Owner — kết nối — Xem cây tổ chức.
- Tenant Admin / Owner — kết nối — Quản lý site.
- Tenant Admin / Owner — kết nối — Quản lý geofence và lịch sử.
- Tenant Admin / Owner — kết nối — Quản lý ca và cấu hình OT.
- Tenant Admin / Owner — kết nối — Quản lý phân công site/ca.

### HR Manager

- HR Manager — kết nối — Quản lý hồ sơ nhân viên.
- HR Manager — kết nối — Tạo nhân viên thủ công.
- HR Manager — kết nối — Mời nhân viên.
- HR Manager — kết nối — Hủy lời mời.
- HR Manager — kết nối — Import nhân viên.
- HR Manager — kết nối — Export nhân viên.
- HR Manager — kết nối — Tạm ngừng/chấm dứt nhân viên.
- HR Manager — kết nối — Quản lý workspace.
- HR Manager — kết nối — Gán/chuyển thành viên workspace.
- HR Manager — kết nối — Xem cây tổ chức.
- HR Manager — kết nối — Quản lý site.
- HR Manager — kết nối — Quản lý geofence và lịch sử.
- HR Manager — kết nối — Quản lý ca và cấu hình OT.
- HR Manager — kết nối — Quản lý phân công site/ca.

### Site Supervisor, Employee và người được mời

- Site Supervisor — kết nối — Quản lý hồ sơ nhân viên — constraint: chỉ đọc.
- Site Supervisor — kết nối — Quản lý site — constraint: chỉ đọc.
- Site Supervisor — kết nối — Quản lý phân công site/ca — constraint: chỉ đọc.
- Site Supervisor — kết nối — Giới hạn dữ liệu theo site.
- Employee — kết nối — Xem site có thể chấm công hôm nay.
- Employee — kết nối — Xem lịch/phân công của tôi.
- Người được mời — kết nối — Chấp nhận lời mời.
- Dịch vụ Email — hỗ trợ — Mời nhân viên.
- Dịch vụ Email — hỗ trợ — Chấp nhận lời mời.

## Quan hệ include

- Mời nhân viên — `<<include>>` → Kiểm tra giới hạn gói.
- Chấp nhận lời mời — `<<include>>` → Kiểm tra giới hạn gói.
- Tạo nhân viên thủ công — `<<include>>` → Kiểm tra giới hạn gói.
- Quản lý site — `<<include>>` → Kiểm tra giới hạn gói — chỉ khi tạo site mới.
- Quản lý geofence và lịch sử — `<<include>>` → Quản lý site.
- Gán/chuyển thành viên workspace — `<<include>>` → Quản lý workspace.
- Quản lý phân công site/ca — `<<include>>` → Quản lý site.
- Quản lý phân công site/ca — `<<include>>` → Quản lý ca và cấu hình OT.

## Quan hệ extend

- Tạo nhân viên thủ công — `<<extend>>` → Quản lý hồ sơ nhân viên.
- Mời nhân viên — `<<extend>>` → Quản lý hồ sơ nhân viên.
- Tạm ngừng/chấm dứt nhân viên — `<<extend>>` → Quản lý hồ sơ nhân viên.

---

# Mức 2.4 — Face ID và Liveness

Nguồn: `05-face-id.puml`

## Actor

- Employee.
- Tenant Admin / Owner.
- HR Manager.
- Object Storage.

## Kết nối actor với use case

### Employee

- Employee — kết nối — Ghi nhận đồng ý sử dụng Face ID.
- Employee — kết nối — Đăng ký khuôn mặt.
- Employee — kết nối — Xem trạng thái Face ID.
- Employee — kết nối — Thu hồi Face ID — constraint: hồ sơ của mình.

### Tenant Admin / Owner

- Tenant Admin / Owner — kết nối — Xem trạng thái Face ID.
- Tenant Admin / Owner — kết nối — Duyệt đăng ký Face ID.
- Tenant Admin / Owner — kết nối — Từ chối đăng ký Face ID.
- Tenant Admin / Owner — kết nối — Thu hồi Face ID.

### HR Manager

- HR Manager — kết nối — Xem trạng thái Face ID.
- HR Manager — kết nối — Duyệt đăng ký Face ID.
- HR Manager — kết nối — Từ chối đăng ký Face ID.
- HR Manager — kết nối — Thu hồi Face ID.

### Hệ thống ngoài

- Object Storage — hỗ trợ — Đăng ký khuôn mặt.
- Object Storage — hỗ trợ — Xóa dữ liệu sinh trắc học.

## Quan hệ include

- Đăng ký khuôn mặt — `<<include>>` → Ghi nhận đồng ý sử dụng Face ID.
- Đăng ký khuôn mặt — `<<include>>` → Kiểm tra chất lượng ảnh.
- Đăng ký khuôn mặt — `<<include>>` → Kiểm tra liveness.
- Đăng ký khuôn mặt — `<<include>>` → Tạo face embedding.
- Thu hồi Face ID — `<<include>>` → Xóa dữ liệu sinh trắc học.

## Quan hệ extend

- Duyệt đăng ký Face ID — `<<extend>>` → Xem trạng thái Face ID — điều kiện: hồ sơ đang chờ duyệt.
- Từ chối đăng ký Face ID — `<<extend>>` → Xem trạng thái Face ID — điều kiện: hồ sơ đang chờ duyệt.

## Use case dùng lại ở module khác

- Xác minh khuôn mặt được gọi trong phân hệ Check-in và Random Check.
- Không nối Employee trực tiếp với Xác minh khuôn mặt trong sơ đồ này vì mục tiêu của Employee là check-in hoặc phản hồi random check, không phải tự khởi tạo một phiên xác minh độc lập.
- AI service nằm bên trong FAMS nên không vẽ thành actor.

---

# Mức 2.5 — Check-in/out và bảng công

Nguồn: `06-checkin-attendance.puml`

## Actor

- Employee.
- Tenant Admin / Owner.
- HR Manager.
- Site Supervisor.

## Kết nối actor với use case

### Employee

- Employee — kết nối — Xem site có thể chấm công.
- Employee — kết nối — Check-in.
- Employee — kết nối — Check-out.
- Employee — kết nối — Đồng bộ check-in offline.
- Employee — kết nối — Xem kết quả/lịch sử chấm công.
- Employee — kết nối — Gửi giải trình check-in lỗi.
- Employee — kết nối — Xem bảng công cá nhân.
- Employee — kết nối — Xem việc cần xử lý của tôi.

### Tenant Admin / Owner

- Tenant Admin / Owner — kết nối — Xem danh sách/chi tiết check-in.
- Tenant Admin / Owner — kết nối — Duyệt/từ chối check-in bất thường.
- Tenant Admin / Owner — kết nối — Xem bảng công tổng hợp.
- Tenant Admin / Owner — kết nối — Điều chỉnh bảng công.

### HR Manager

- HR Manager — kết nối — Xem danh sách/chi tiết check-in.
- HR Manager — kết nối — Duyệt/từ chối check-in bất thường.
- HR Manager — kết nối — Xem bảng công tổng hợp.
- HR Manager — kết nối — Điều chỉnh bảng công.

### Site Supervisor

- Site Supervisor — kết nối — Xem danh sách/chi tiết check-in — constraint: theo site được giao.
- Site Supervisor — kết nối — Xem bảng công tổng hợp — constraint: theo site được giao.

## Quan hệ include

- Check-in — `<<include>>` → Kiểm tra phân công và thời gian.
- Check-in — `<<include>>` → Kiểm tra GPS/geofence.
- Check-out — `<<include>>` → Kiểm tra phân công và thời gian.
- Check-out — `<<include>>` → Kiểm tra GPS/geofence.
- Check-in — `<<include>>` → Tính phút công, đi muộn, về sớm, OT, thiếu checkout.
- Check-out — `<<include>>` → Tính phút công, đi muộn, về sớm, OT, thiếu checkout.

## Quan hệ extend

- Xác minh Face ID — `<<extend>>` → Check-in — điều kiện: site yêu cầu Face ID.
- Kiểm tra liveness — `<<extend>>` → Xác minh Face ID — điều kiện: site yêu cầu liveness.
- Đồng bộ check-in offline — `<<extend>>` → Check-in — điều kiện: thiết bị mất kết nối khi chấm công.
- Duyệt/từ chối check-in bất thường — `<<extend>>` → Xem danh sách/chi tiết check-in — điều kiện: check-in ở trạng thái chờ duyệt.
- Gửi giải trình check-in lỗi — `<<extend>>` → Xem kết quả/lịch sử chấm công — điều kiện: check-in có lỗi hoặc cần giải trình.
- Điều chỉnh bảng công — `<<extend>>` → Xem bảng công tổng hợp.

Ghi chú: "Xem việc cần xử lý của tôi" gộp check-in đang chờ duyệt và vi phạm chưa giải quyết của chính nhân viên (endpoint `/me/exceptions`, self-service, không thuộc controller riêng của module nào) — đặt ở sơ đồ Check-in/Attendance vì đa số dữ liệu nguồn là check-in.

---

# Mức 2.6 — Random Check và Vi phạm

Nguồn: `07-random-check-violation.puml`

## Actor

- Employee.
- Tenant Admin / Owner.
- HR Manager.
- Site Supervisor.
- Firebase FCM.
- Object Storage.

## Kết nối actor với use case

### Tenant Admin / Owner

- Tenant Admin / Owner — kết nối — Cấu hình random check mặc định.
- Tenant Admin / Owner — kết nối — Cấu hình override theo site.
- Tenant Admin / Owner — kết nối — Xem cấu hình hiệu lực.
- Tenant Admin / Owner — kết nối — Theo dõi scheduled checks.
- Tenant Admin / Owner — kết nối — Kích hoạt kiểm tra thủ công.
- Tenant Admin / Owner — kết nối — Hủy lượt kiểm tra.
- Tenant Admin / Owner — kết nối — Xem danh sách/chi tiết vi phạm.
- Tenant Admin / Owner — kết nối — Xác nhận/bỏ qua vi phạm.
- Tenant Admin / Owner — kết nối — Cập nhật ảnh hưởng bảng công.

### HR Manager

- HR Manager — kết nối — Cấu hình random check mặc định.
- HR Manager — kết nối — Cấu hình override theo site.
- HR Manager — kết nối — Xem cấu hình hiệu lực.
- HR Manager — kết nối — Theo dõi scheduled checks.
- HR Manager — kết nối — Kích hoạt kiểm tra thủ công.
- HR Manager — kết nối — Hủy lượt kiểm tra.
- HR Manager — kết nối — Xem danh sách/chi tiết vi phạm.
- HR Manager — kết nối — Xác nhận/bỏ qua vi phạm.
- HR Manager — kết nối — Cập nhật ảnh hưởng bảng công.

### Site Supervisor

- Site Supervisor — kết nối — Cấu hình random check mặc định — constraint: theo quyền (randomchecks:configure).
- Site Supervisor — kết nối — Cấu hình override theo site — constraint: theo quyền/site.
- Site Supervisor — kết nối — Xem cấu hình hiệu lực — constraint: theo quyền/site.
- Site Supervisor — kết nối — Theo dõi scheduled checks — constraint: theo site được giao.
- Site Supervisor — kết nối — Kích hoạt kiểm tra thủ công — constraint: theo quyền và site.
- Site Supervisor — kết nối — Hủy lượt kiểm tra — constraint: theo quyền/site.
- Site Supervisor — kết nối — Xem danh sách/chi tiết vi phạm — constraint: theo site được giao.
- Site Supervisor — kết nối — Xác nhận/bỏ qua vi phạm — constraint: theo quyền.

Ghi chú: seed data gán permission `randomchecks:configure` cho role `SITE_SUPERVISOR`, không riêng cho thao tác kích hoạt thủ công — vì vậy site supervisor thực tế cấu hình được cả `RandomCheckConfig` trong site được giao, không chỉ theo dõi/kích hoạt.

### Employee

- Employee — kết nối — Nhận yêu cầu kiểm tra.
- Employee — kết nối — Xem lượt kiểm tra đang chờ.
- Employee — kết nối — Phản hồi random check.
- Employee — kết nối — Xem kết quả kiểm tra.
- Employee — kết nối — Xem danh sách/chi tiết vi phạm — constraint: chỉ vi phạm của bản thân.
- Employee — kết nối — Gửi giải trình và minh chứng.

### Hệ thống ngoài

- Firebase FCM — hỗ trợ — Nhận yêu cầu kiểm tra.
- Object Storage — hỗ trợ — Gửi giải trình và minh chứng.

## Quan hệ include

- Phản hồi random check — `<<include>>` → Xác minh vị trí.

## Quan hệ extend

- Cấu hình override theo site — `<<extend>>` → Cấu hình random check mặc định — điều kiện: site cần cấu hình riêng.
- Kích hoạt kiểm tra thủ công — `<<extend>>` → Theo dõi scheduled checks.
- Hủy lượt kiểm tra — `<<extend>>` → Theo dõi scheduled checks.
- Xác minh Face ID — `<<extend>>` → Phản hồi random check — điều kiện: mode yêu cầu Face ID.
- Kiểm tra liveness — `<<extend>>` → Xác minh Face ID — điều kiện: mode yêu cầu liveness.
- Ghi nhận vi phạm tự động — `<<extend>>` → Phản hồi random check — điều kiện: kết quả xác minh thất bại.
- Gửi giải trình và minh chứng — `<<extend>>` → Xem danh sách/chi tiết vi phạm — điều kiện: nhân viên cần giải trình.
- Xác nhận/bỏ qua vi phạm — `<<extend>>` → Xem danh sách/chi tiết vi phạm.
- Cập nhật ảnh hưởng bảng công — `<<extend>>` → Xác nhận/bỏ qua vi phạm.

Ghi chú: vi phạm `no_response` cũng được hệ thống tạo khi lượt kiểm tra hết hạn. Frontend không có use case tạo vi phạm thủ công.

---

# Mức 2.7 — Analytics và Reporting tách theo hai phía

## Mức 2.7A — Tra cứu dữ liệu phía nền tảng

Nguồn: `08a-platform-analytics.puml`

### Actor và kết nối

- Platform Admin — kết nối — Tìm kiếm nhân viên/site/check-in — constraint: dữ liệu được phép.
- Platform Staff — kết nối — Tìm kiếm nhân viên/site/check-in — constraint: theo permission.

Sơ đồ này chỉ thể hiện năng lực analytics hiện có cho actor nền tảng, không suy diễn thêm dashboard hoặc báo cáo chưa có trong backend.

## Mức 2.7B — Dashboard và báo cáo phía công ty

Nguồn: `08b-company-analytics-reporting.puml`

### Actor

- Tenant Admin / Owner.
- HR Manager.
- Site Supervisor.
- Employee.

### Kết nối Employee

- Employee — kết nối — Xem dashboard cá nhân.

### Tenant Admin / Owner

- Tenant Admin / Owner — kết nối — Xem dashboard HR.
- Tenant Admin / Owner — kết nối — Xem dashboard giám sát site.
- Tenant Admin / Owner — kết nối — Xem báo cáo chấm công ngày.
- Tenant Admin / Owner — kết nối — Xem báo cáo chấm công tháng.
- Tenant Admin / Owner — kết nối — Xem báo cáo vi phạm.
- Tenant Admin / Owner — kết nối — Xem báo cáo hiện diện theo site.
- Tenant Admin / Owner — kết nối — Xem báo cáo trạng thái Face ID.
- Tenant Admin / Owner — kết nối — Xuất báo cáo/bảng công.
- Tenant Admin / Owner — kết nối — Xuất danh sách vi phạm.
- Tenant Admin / Owner — kết nối — Tìm kiếm nhân viên/site/check-in.
- Tenant Admin / Owner — kết nối — Lưu và áp dụng bộ lọc cá nhân.

### HR Manager

- HR Manager — kết nối — Xem dashboard HR.
- HR Manager — kết nối — Xem báo cáo chấm công ngày.
- HR Manager — kết nối — Xem báo cáo chấm công tháng.
- HR Manager — kết nối — Xem báo cáo vi phạm.
- HR Manager — kết nối — Xem báo cáo hiện diện theo site.
- HR Manager — kết nối — Xem báo cáo trạng thái Face ID.
- HR Manager — kết nối — Xuất báo cáo/bảng công.
- HR Manager — kết nối — Xuất danh sách vi phạm.
- HR Manager — kết nối — Tìm kiếm nhân viên/site/check-in.
- HR Manager — kết nối — Lưu và áp dụng bộ lọc cá nhân.

### Site Supervisor

- Site Supervisor — kết nối — Xem dashboard giám sát site.
- Site Supervisor — kết nối — Xem báo cáo chấm công ngày.
- Site Supervisor — kết nối — Xem báo cáo vi phạm.
- Site Supervisor — kết nối — Xem báo cáo hiện diện theo site.
- Site Supervisor — kết nối — Áp dụng tenant/site scope.

### Quan hệ include

- Xem dashboard giám sát site — `<<include>>` → Áp dụng tenant/site scope.
- Xem báo cáo chấm công ngày — `<<include>>` → Áp dụng tenant/site scope.
- Xem báo cáo chấm công tháng — `<<include>>` → Áp dụng tenant/site scope.
- Xem báo cáo vi phạm — `<<include>>` → Áp dụng tenant/site scope.
- Xem báo cáo hiện diện theo site — `<<include>>` → Áp dụng tenant/site scope.

### Quan hệ extend

- Xuất báo cáo/bảng công — `<<extend>>` → Xem báo cáo chấm công ngày.
- Xuất báo cáo/bảng công — `<<extend>>` → Xem báo cáo chấm công tháng.
- Xuất danh sách vi phạm — `<<extend>>` → Xem báo cáo vi phạm.
- Lưu và áp dụng bộ lọc cá nhân — `<<extend>>` → Xem báo cáo chấm công ngày.
- Lưu và áp dụng bộ lọc cá nhân — `<<extend>>` → Xem báo cáo chấm công tháng.
- Lưu và áp dụng bộ lọc cá nhân — `<<extend>>` → Xem báo cáo vi phạm.

---

# Mức 2.8 — Thông báo

Nguồn: `09-notification.puml`

## Actor

- Người dùng đã xác thực.
- Tenant Admin / Owner.
- HR Manager.
- Platform Admin.
- Hệ thống tích hợp được ủy quyền.
- Firebase FCM.
- Dịch vụ Email.

## Kết nối actor với use case

### Người dùng đã xác thực

- Người dùng đã xác thực — kết nối — Xem hộp thư thông báo.
- Người dùng đã xác thực — kết nối — Đánh dấu đã đọc.
- Người dùng đã xác thực — kết nối — Cấu hình kênh nhận cá nhân.
- Người dùng đã xác thực — kết nối — Đăng ký/hủy thiết bị nhận push.

### Vai trò quản trị

- Tenant Admin / Owner — kết nối — Quản lý template thông báo.
- HR Manager — kết nối — Quản lý template thông báo — constraint: theo permission.
- Platform Admin — kết nối — Quản lý template thông báo.

### Hệ thống ngoài

- Hệ thống tích hợp được ủy quyền — kết nối — Tạo thông báo qua internal API.
- Firebase FCM — hỗ trợ — Gửi push notification.
- Dịch vụ Email — hỗ trợ — Gửi email fallback.

## Quan hệ include

- Gửi push notification — `<<include>>` → Theo dõi và retry kết quả gửi.
- Gửi email fallback — `<<include>>` → Theo dõi và retry kết quả gửi.
- Tạo thông báo qua internal API — `<<include>>` → Tạo thông báo từ sự kiện nghiệp vụ.

## Quan hệ extend

- Đánh dấu đã đọc — `<<extend>>` → Xem hộp thư thông báo.
- Gửi push notification — `<<extend>>` → Tạo thông báo từ sự kiện nghiệp vụ — điều kiện: người dùng bật kênh push.
- Gửi email fallback — `<<extend>>` → Tạo thông báo từ sự kiện nghiệp vụ — điều kiện: cần fallback email.

Ghi chú: endpoint internal notification có thật và dùng `X-Internal-Secret`, nhưng repository chưa định danh một hệ thống gọi cụ thể; vì vậy actor được đặt tên khái quát và có stereotype `external system`.

---

# Kiểm tra trước khi tự dựng hình

Với mỗi sơ đồ:

1. Vẽ actor bên ngoài hình chữ nhật ranh giới FAMS.
2. Vẽ use case hình oval bên trong đúng ranh giới module.
3. Dùng đường liền cho actor–use case; không cần mũi tên thể hiện “gọi API”.
4. Dùng đường nét đứt và mũi tên rỗng cho `include`/`extend`.
5. Với `include`, đầu mũi tên hướng vào use case được bao gồm.
6. Với `extend`, đầu mũi tên hướng vào use case cơ sở.
7. Với kế thừa actor, tam giác rỗng hướng vào actor tổng quát `Người dùng đã xác thực`.
8. Không nối trực tiếp hai actor, ngoại trừ quan hệ kế thừa.
9. Không nối các hệ thống ngoài trực tiếp với nhau.
10. Không đưa database, Redis, AI service hoặc controller vào Use Case Diagram.
