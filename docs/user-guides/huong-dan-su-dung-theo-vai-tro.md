# Hướng dẫn sử dụng FAMS theo vai trò

**Ngày viết:** 2026-08-06
**Đối tượng đọc:** Platform Admin, Company Admin/HR, Nhân viên (Employee) — và đội hỗ trợ khách hàng dùng tài liệu này để giảm chi phí trả lời câu hỏi lặp lại.
**Lưu ý về phạm vi**: Đây là tài liệu backend — mô tả **nghiệp vụ và luồng thao tác** đã được xác nhận hoạt động đúng qua các đợt audit, không mô tả giao diện cụ thể (giao diện do đội Web/App dựng dựa trên các API đã tài liệu hoá tại `docs/api/`). Ảnh chụp màn hình sẽ do đội FE bổ sung khi giao diện hoàn thiện.

---

## 0. Mô hình vai trò tổng quan

FAMS có 2 tầng vai trò tách biệt:

| Tầng | Vai trò | Phạm vi |
|---|---|---|
| **Nền tảng (Platform)** | Platform Admin, Platform Support Lead, Platform Billing Ops, Platform Security Auditor | Toàn hệ thống, xuyên suốt mọi công ty (tenant) khách hàng |
| **Công ty (Tenant)** | Company Admin/Owner, HR Manager, Site Supervisor, Employee | Chỉ trong phạm vi 1 công ty — 1 người có thể giữ vai trò khác nhau ở nhiều công ty khác nhau cùng lúc (ví dụ vừa là HR ở công ty A vừa là nhân viên ở công ty B), dùng chung 1 tài khoản đăng nhập |

Quyền được kiểm tra ở **2 lớp độc lập**: (1) vai trò/quyền cụ thể (ví dụ `employees:update`), VÀ (2) người dùng phải thực sự có vai trò active trong đúng công ty đang thao tác — không thể dùng quyền ở công ty A để thao tác dữ liệu công ty B dù giữ đúng tên quyền đó.

---

## 1. Hướng dẫn cho Platform Admin

Platform Admin quản lý toàn bộ hệ thống SaaS — không tham gia vận hành nghiệp vụ hàng ngày của từng công ty khách hàng (chấm công, vi phạm...), chỉ quản lý ở tầng "chủ nền tảng".

### 1.1 Quản lý khách hàng (Tenant)

- **Tạo công ty mới**: nhập tên công ty + email chủ sở hữu (owner) → hệ thống tự tạo tài khoản Owner đầu tiên, gửi thông tin đăng nhập. Bắt buộc phải có email chủ sở hữu hợp lệ, không được bỏ trống.
- **Xem chi tiết vận hành 1 công ty**: subscription hiện tại (gói/kỳ hạn), mức sử dụng thực tế (số nhân viên/site đang dùng) so với giới hạn gói — dùng để tư vấn nâng gói hoặc phát hiện công ty sắp chạm giới hạn.
- **Khóa/mở khóa công ty** (suspend/reactivate): dùng khi công ty vi phạm điều khoản hoặc chưa thanh toán — khóa KHÔNG xoá dữ liệu, chỉ chặn đăng nhập/thao tác; mở lại là khôi phục truy cập ngay lập tức, không mất dữ liệu.

### 1.2 Quản lý gói dịch vụ

- Xem danh sách gói (Trial/Basic/Pro/Enterprise), giới hạn từng gói (số nhân viên tối đa, số site tối đa, số lượt random-check/tháng).
- Gán/đổi gói cho 1 công ty cụ thể.
- **Lưu ý nghiệp vụ quan trọng**: giới hạn gói được kiểm tra ở đúng thời điểm tài nguyên thực sự được tạo (ví dụ: lúc nhân viên CHẤP NHẬN lời mời, không chỉ lúc HR GỬI lời mời) — tránh trường hợp công ty lách giới hạn bằng cách gửi tràn lời mời trước rồi mới bị hạ gói.

### 1.3 Audit Log — điều tra thao tác hệ thống

- Xem toàn bộ lịch sử thao tác của mọi công ty (chỉ Platform Admin xem được xuyên công ty — Company Admin/HR chỉ xem được đúng công ty mình).
- Lọc theo người thực hiện, loại hành động, khoảng thời gian.
- **Trace theo request_id**: khi cần điều tra sự cố "1 lần bấm nút gây ra chuỗi hành động nào", tra theo mã request duy nhất đó để xem toàn bộ log liên quan trong cùng 1 lần gọi.
- Xem diff dữ liệu trước/sau thay đổi (old value/new value) để biết chính xác ai đã sửa cái gì.

### 1.4 Giám sát vận hành hệ thống

Màn "Trạng thái hệ thống" (`system-status`) cho biết ngay lập tức:
- Database, Redis, hàng đợi random-check có đang hoạt động bình thường không.
- Dịch vụ gửi thông báo đẩy (FCM) có đang kết nối được không.
- **Dịch vụ AI (nhận diện khuôn mặt, chống giả mạo)** có đang phản hồi không — bổ sung 2026-08-06, trước đó không có cách nào biết dịch vụ này bị lỗi ngoài việc thấy lỗi rải rác ở từng lượt đăng ký/chấm công Face ID.
- Trạng thái từng job chạy nền (tính lại bảng công mỗi đêm, dọn dữ liệu cũ hàng tuần, gửi random check...) — job nào lỗi sẽ tự động gửi cảnh báo đẩy tới mọi Platform Admin, không cần chủ động vào xem mới biết.

### 1.5 Dọn dẹp dữ liệu quá hạn (tự động)

Không cần thao tác tay — hệ thống tự động mỗi tuần: xoá log gửi thông báo cũ, xoá thông báo đã đọc cũ, xoá ảnh chấm công/kiểm tra ngẫu nhiên cũ theo đúng chính sách lưu trữ. Ảnh đăng ký khuôn mặt gốc bị xoá **ngay lập tức** khi hồ sơ Face ID của 1 nhân viên bị thu hồi, không cần chờ job hàng tuần.

---

## 2. Hướng dẫn cho Company Admin / HR

Đây là người dùng vận hành nghiệp vụ hàng ngày trong phạm vi 1 công ty.

### 2.1 Thiết lập ban đầu (thường làm 1 lần khi mới triển khai)

1. **Công trình (Site)**: tạo từng công trình/văn phòng, vẽ vùng địa lý cho phép chấm công (geofence) quanh vị trí thật.
2. **Ca làm việc (Shift)**: tạo ca theo khung giờ thực tế của công ty (ví dụ 08:00–17:00), có thể tạo nhiều ca khác nhau cho các site khác nhau.
3. **Phòng ban/Vùng làm việc (Workspace)**: nhóm nhân viên theo phòng ban để quản lý và phân quyền theo nhóm.

### 2.2 Quản lý nhân viên

- **Mời nhân viên**: gửi lời mời qua email — ứng viên tự chấp nhận và tạo tài khoản, không cần Admin/HR tạo mật khẩu hộ.
- **Xem danh sách/chi tiết nhân viên**: thông tin liên hệ (email/số điện thoại) hiển thị **che một phần** (ví dụ `a***@congty.vn`) trừ khi người xem là Platform Admin hoặc giữ quyền quản trị tài khoản cấp cao — bảo vệ thông tin cá nhân nhân viên khỏi bị xem tràn lan trong nội bộ.
- **Xuất danh sách nhân viên ra Excel**: áp dụng đúng quy tắc che thông tin như trên — file Excel xuất ra không "lộ" nhiều hơn những gì màn hình danh sách đã hiển thị.
- **Phân công nhân viên** vào site + ca làm cụ thể, có thể đặt ngày bắt đầu/kết thúc phân công.

### 2.3 Đăng ký Face ID cho nhân viên

1. Nhân viên đồng ý cho phép dùng Face ID (bước đồng ý bắt buộc, ghi nhận thời điểm đồng ý).
2. Nhân viên chụp ảnh đăng ký (nhiều ảnh để tăng độ chính xác).
3. **HR/Admin duyệt thủ công** trước khi Face ID được kích hoạt — hệ thống chỉ tự động kiểm tra sơ bộ (phát hiện ảnh giả/chụp lại màn hình), không tự động xác nhận "đúng là người này" — quyết định cuối luôn cần con người, giống hầu hết hệ thống chấm công sinh trắc học thực tế.
4. Có thể từ chối kèm lý do (ảnh không rõ, nghi ngờ giả mạo...) — nhân viên đăng ký lại từ đầu.
5. Khi nhân viên nghỉ việc hoặc cần thu hồi Face ID: thao tác thu hồi xoá ngay embedding VÀ ảnh gốc, không lưu lại.

### 2.4 Theo dõi chấm công

- Xem chấm công theo ngày/theo nhân viên/theo site.
- Bảng công tổng hợp theo tháng (tự động tính lại mỗi đêm, đảm bảo luôn khớp với chấm công thực tế — không cần HR tự tính tay).
- Trường hợp chấm công có vấn đề (ngoài vùng địa lý cho phép, Face ID không khớp...) sẽ ở trạng thái "chờ duyệt" — HR xem xét và quyết định chấp nhận hay từ chối.
- **Không được xuất bảng công tháng nếu còn dòng chờ duyệt/bị từ chối chưa xử lý** (trừ khi chủ động xác nhận bỏ qua cảnh báo) — tránh chốt lương trên dữ liệu chưa sạch.

### 2.5 Kiểm tra ngẫu nhiên (Random Check)

- Cấu hình tần suất kiểm tra ngẫu nhiên theo site (ví dụ 2 lần/ca).
- Hệ thống tự động gửi yêu cầu kiểm tra tới nhân viên đang trong ca, nhân viên phải phản hồi trong thời gian quy định.
- Không phản hồi kịp → tự động ghi nhận vi phạm "không phản hồi".
- Có thể kích hoạt kiểm tra thủ công ngay lập tức cho 1 nhân viên cụ thể khi cần (ví dụ nghi ngờ vắng mặt).

### 2.6 Xử lý vi phạm

- Xem danh sách vi phạm (không phản hồi kiểm tra, chấm công ngoài vùng cho phép...).
- Nhân viên có thể gửi giải trình kèm ảnh minh chứng trước khi HR xử lý.
- HR xác nhận hoặc bác bỏ vi phạm, có ghi chú lý do.
- **Xuất danh sách vi phạm ra Excel** theo bộ lọc đang xem (theo site/nhân viên/loại vi phạm/trạng thái đã xử lý hay chưa) — file xuất khớp đúng bộ lọc trên màn hình, không cần lo dữ liệu dư ngoài ý muốn.

### 2.7 Báo cáo

- Báo cáo chấm công (ngày/tháng), báo cáo vi phạm, báo cáo hiện diện theo site, báo cáo trạng thái đăng ký Face ID toàn công ty.
- **Lưu bộ lọc thường dùng**: với các màn danh sách lớn (vi phạm, chấm công...), có thể lưu lại bộ lọc hay dùng (ví dụ "Vi phạm chưa xử lý tháng này") để không phải nhập lại mỗi lần, đặt 1 bộ lọc làm mặc định tự áp dụng khi mở màn hình. Bộ lọc là của riêng từng người dùng, không chia sẻ cho đồng nghiệp khác.

### 2.8 Cấu hình thông báo

- Tuỳ chỉnh nội dung (tiêu đề/nội dung) thông báo gửi cho nhân viên theo từng loại sự kiện và ngôn ngữ — ví dụ đổi hẳn văn phong thông báo kiểm tra ngẫu nhiên cho phù hợp văn hoá công ty. Thay đổi có hiệu lực ngay từ lần gửi tiếp theo.

### 2.9 Đội ngũ triển khai (Go-live)

Xem `docs/deployment/go-live-checklist.md` — checklist đầy đủ cấu hình cần kiểm tra trước khi công ty khách hàng chính thức đi vào vận hành thật.

---

## 3. Hướng dẫn cho Nhân viên (Employee)

### 3.1 Đăng ký Face ID

1. Đọc và đồng ý điều khoản sử dụng Face ID.
2. Chụp ảnh theo hướng dẫn (đủ ánh sáng, nhìn thẳng camera).
3. Chờ HR/Admin duyệt — trong lúc chờ vẫn có thể chấm công bằng phương thức khác (vị trí GPS) nếu site cho phép.
4. Nếu bị từ chối, xem lý do và đăng ký lại.

### 3.2 Chấm công

- Chỉ chấm công được khi đang ở trong vùng địa lý cho phép của site được phân công (geofence).
- Tuỳ cấu hình site: chỉ cần vị trí GPS, hoặc cần thêm xác thực khuôn mặt (Face ID), hoặc cần thêm bước chống giả mạo (liveness — yêu cầu quay mặt/chớp mắt để chứng minh là người thật, không phải ảnh tĩnh).
- Xem bản đồ site và vị trí hiện tại trước khi chấm công để biết còn cách vùng cho phép bao xa.
- Chấm công ngoài vùng cho phép vẫn được ghi nhận nhưng ở trạng thái "chờ HR duyệt", không tự động tính công cho tới khi được duyệt.

### 3.3 Kiểm tra ngẫu nhiên

- Nhận thông báo khi có yêu cầu kiểm tra ngẫu nhiên trong ca — phải phản hồi trong thời gian quy định (thường vài phút).
- Không phản hồi kịp sẽ bị ghi nhận vi phạm — nếu có lý do chính đáng (mất sóng, thiết bị lỗi...), có thể gửi giải trình sau đó để HR xem xét.

### 3.4 Xem chấm công của bản thân

- Xem lại lịch sử chấm công, giờ vào/ra, tổng giờ làm.
- Xem trạng thái các vi phạm liên quan tới mình và gửi giải trình kèm ảnh nếu cần.

### 3.5 Cài đặt cá nhân

- Bật/tắt từng loại thông báo (trong app và/hoặc đẩy) theo nhu cầu — ví dụ tắt thông báo trong app nhưng vẫn muốn nhận đẩy, hoặc ngược lại. Mỗi loại thông báo có 2 công tắc độc lập.
- Cập nhật hồ sơ cá nhân (ngày sinh, quê quán, giới tính, địa chỉ).
- Bật xác thực 2 lớp (2FA) cho tài khoản để tăng bảo mật đăng nhập.

---

## 4. Câu hỏi thường gặp (giảm chi phí hỗ trợ)

| Câu hỏi | Trả lời ngắn |
|---|---|
| Tại sao tôi không chấm công được? | Kiểm tra: (1) có đang trong vùng địa lý cho phép của site không, (2) có đang trong khung giờ ca làm không, (3) Face ID đã được duyệt chưa (nếu site yêu cầu Face ID) |
| Tại sao thông tin liên hệ của tôi bị che khi HR xem? | Đây là tính năng bảo vệ dữ liệu cá nhân có chủ đích — chỉ Platform Admin hoặc người giữ quyền quản trị tài khoản cấp cao mới xem được đầy đủ, không phải lỗi hiển thị |
| Chấm công ngoài vùng cho phép có bị tính là vi phạm không? | Không tự động — ở trạng thái chờ HR duyệt, HR có thể chấp nhận nếu có lý do hợp lý |
| Không phản hồi kiểm tra ngẫu nhiên vì lý do khách quan thì sao? | Vẫn bị ghi nhận vi phạm tự động, nhưng có thể gửi giải trình kèm ảnh sau đó — HR sẽ xem xét và có thể bác bỏ vi phạm |
| Xoá tài khoản/thu hồi Face ID thì ảnh có bị xoá thật không? | Có — ảnh gốc bị xoá ngay lập tức khi thu hồi, không lưu lại, không chờ job dọn dẹp định kỳ |
| Bộ lọc tôi lưu có bị đồng nghiệp thấy không? | Không — bộ lọc là riêng tư theo từng tài khoản |
