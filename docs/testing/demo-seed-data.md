# Bộ dữ liệu demo chuẩn

`scripts/seed.sh` tạo bộ dữ liệu nhỏ, nhất quán để phát triển và kiểm thử local/staging. Không chạy seed này trên production.

## Cách chạy

```bash
make seed
```

Hoặc:

```bash
bash scripts/seed.sh
```

Seed có tính idempotent: chạy lại sẽ dựng lại đúng các bản ghi demo định danh sẵn, không nhân bản dữ liệu. Lịch sử do seed quản lý được nhận diện bằng UUID ổn định; dữ liệu thử nghiệm được người dùng tự tạo trong tenant demo không bị xóa. Sau khi nạp, `scripts/verify_demo_seed.sql` tự kiểm tra số lượng, trạng thái tài khoản, vai trò, tenant isolation, phân bổ tình huống chấm công và vòng đời thanh toán.

Mật khẩu chung của tài khoản demo: `Admin@1234`.

## Phạm vi dữ liệu

Có năm công ty demo để thể hiện đủ vòng đời khách hàng, nhưng dữ liệu vận hành chi tiết vẫn tập trung ở An Phát:

| Công ty | Slug | Mức dữ liệu | Gói/thuê bao |
|---|---|---|---|
| Công ty CP Xây dựng An Phát | `demo-an-phat` | Đầy đủ | Doanh nghiệp / Active |
| Công ty TNHH Logistics Minh Long | `demo-minh-long` | Tối giản | Khởi đầu / Active |
| Công ty TNHH Dịch vụ Sao Việt | `demo-sao-viet` | Tối giản | Chuyên nghiệp / Cancelled |
| Công ty TNHH Nội thất Phúc Hưng | `demo-phuc-hung` | Tối giản | Dùng thử / Trial |
| Công ty CP Cơ điện Bắc Nam | `demo-bac-nam` | Tối giản | Cơ bản / Expired |

Tất cả công ty dùng `Asia/Ho_Chi_Minh`, locale `vi-VN` và tiền tệ `VND`. Trạng thái tenant/thuê bao được tạo có chủ đích để báo cáo nền tảng thể hiện Active, Trial, Expired và Cancelled.

Các tenant demo v2 cũ được lưu trữ bằng soft delete khi chạy seed v3. Dữ liệu do người dùng tự tạo và tenant không thuộc danh sách demo cũ không bị tác động.

## Quản trị nền tảng

| Email | Vai trò | Trạng thái |
|---|---|---|
| `admin@fams.com` | `PLATFORM_ADMIN` | Hoạt động, email đã xác thực |

Platform Admin không phải chủ công ty, không có hồ sơ nhân viên và không mang bất kỳ role công ty nào.

## Công ty An Phát — 15 thành viên

Mọi tài khoản dưới đây đều hoạt động, đã xác thực email và số điện thoại, có đúng một hồ sơ nhân viên và đúng một role công ty.

| Mã NV | Họ tên | Email | Vai trò | Phòng ban | Công trình/phạm vi |
|---|---|---|---|---|---|
| AP001 | Nguyễn Hoàng Nam | `admin.anphat@fams.test` | `TENANT_ADMIN` | Ban Giám đốc | Không phân ca |
| AP002 | Trần Thu Hà | `hr.anphat@fams.test` | `HR_MANAGER` | Hành chính - Nhân sự | Không phân ca |
| AP003 | Lê Minh Anh | `hr.support.anphat@fams.test` | `HR_MANAGER` | Hành chính - Nhân sự | Không phân ca |
| AP004 | Phạm Quốc Huy | `supervisor.hq@fams.test` | `SITE_SUPERVISOR` | Kỹ thuật | Chỉ Trụ sở Hà Nội |
| AP005 | Nguyễn Đức Long | `supervisor.tayho@fams.test` | `SITE_SUPERVISOR` | An toàn - Chất lượng | Chỉ Công trình Tây Hồ |
| AP006 | Võ Thị Mai | `supervisor.caugiay@fams.test` | `SITE_SUPERVISOR` | Thi công | Chỉ Công trình Cầu Giấy |
| AP007 | Đỗ Thành Công | `supervisor.donganh@fams.test` | `SITE_SUPERVISOR` | Thi công | Chỉ Công trình Đông Anh |
| AP008 | Nguyễn Bá Duy Anh | `duy.anh@fams.test` | `EMPLOYEE` | Kỹ thuật | Tây Hồ, ca sáng |
| AP009 | Nguyễn Minh Quân | `minh.quan@fams.test` | `EMPLOYEE` | Kỹ thuật | Tây Hồ, ca chiều |
| AP010 | Bùi Văn Khoa | `van.khoa@fams.test` | `EMPLOYEE` | Kỹ thuật | Tây Hồ, ca đêm |
| AP011 | Phan Thị Lan | `thi.lan@fams.test` | `EMPLOYEE` | An toàn - Chất lượng | Tây Hồ, ca sáng |
| AP012 | Hoàng Gia Bảo | `gia.bao@fams.test` | `EMPLOYEE` | An toàn - Chất lượng | Cầu Giấy, ca công trường |
| AP013 | Trịnh Ngọc Mai | `ngoc.mai@fams.test` | `EMPLOYEE` | Thi công | Cầu Giấy, ca công trường |
| AP014 | Vũ Thanh Tùng | `thanh.tung@fams.test` | `EMPLOYEE` | Thi công | Đông Anh, ca công trường |
| AP015 | Đặng Thu Trang | `thu.trang@fams.test` | `EMPLOYEE` | Thi công | Đông Anh, ca công trường |

Phân bố role: 1 `TENANT_ADMIN`, 2 `HR_MANAGER`, 4 `SITE_SUPERVISOR`, 8 `EMPLOYEE`.

## Phòng ban và công trình

Năm phòng ban:

- Ban Giám đốc
- Hành chính - Nhân sự
- Kỹ thuật
- An toàn - Chất lượng
- Thi công

Bốn địa điểm:

| Mã | Địa điểm | Chính sách check-in | Chính sách kiểm tra ngẫu nhiên |
|---|---|---|---|
| AP-HQ | Trụ sở Hà Nội | GPS | Không tự động; cho phép kiểm tra thủ công |
| AP-TH | Công trình Tây Hồ | GPS + Face ID + liveness | Kế thừa mặc định công ty, toàn ca |
| AP-CG | Công trình Cầu Giấy | GPS + Face ID | Ghi đè khung 08:00–15:00 |
| AP-DA | Công trình Đông Anh | GPS | Tắt tự động ở cấp ca; vẫn cho phép thủ công |

Tây Hồ có ca sáng, chiều và qua đêm để kiểm thử nhiều khung giờ. Các địa điểm khác dùng ca hành chính/công trường và đều tuân theo giờ Việt Nam.

## Dữ liệu vận hành từ 15/07 đến 05/09/2026

Seed tạo cố định 524 phiên chấm công cho 12 người có phân ca. Trụ sở làm thứ Hai–thứ Sáu; công trường làm thứ Hai–thứ Bảy. Trong 544 lượt được phân công có 20 lượt vắng mặt thực tế, thể hiện bằng việc không phát sinh phiên chấm công.

Phân bổ dữ liệu hiện có:

- 17 lượt đi muộn sau khi trừ thời gian ân hạn;
- 8 lượt về sớm;
- 26 lượt có OT, trong đó có một lượt vượt ngưỡng OT ngày;
- 3 lượt quên checkout đã được hệ thống đóng đúng nghiệp vụ, không để phiên mở treo;
- 42 phiên được đồng bộ từ chế độ offline;
- một phiên `pending_review` và một phiên `rejected` để thử quy trình HR;
- 20 lượt không đến làm/không chấm công;
- tổng giờ làm thay đổi theo ca, thời gian đi muộn, về sớm và OT;
- ngày công bình thường chiếm đa số để tỷ lệ báo cáo không bị méo.

Seed còn tạo 18 lần kiểm tra ngẫu nhiên từ tháng 7 đến tháng 9, gồm:

- kiểm tra tự động và hai kiểm tra thủ công bất chợt;
- hoàn thành hợp lệ;
- không phản hồi;
- sai vị trí;
- Face ID không khớp;
- liveness thất bại;
- AI xác minh khuôn mặt timeout;
- vi phạm đã xác nhận, chấp nhận giải trình, chưa xử lý và xử lý quá hạn;
- vi phạm có/không ảnh hưởng bảng công;
- hồ sơ Face ID demo cho toàn bộ nhân sự vận hành;
- thông báo phân công, kiểm tra ngẫu nhiên và thiếu checkout;
- audit log import nhân viên, cấu hình công trình, random check, phân công và xuất báo cáo.

## Dữ liệu thanh toán và báo cáo nền tảng

Có 13 đơn thanh toán mẫu, trải từ tháng 7 đến tháng 9 và dùng đúng VND:

| Trạng thái | Số đơn | Ý nghĩa |
|---|---:|---|
| `PAID` | 7 | Doanh thu thực thu, có đơn đã xuất hóa đơn và đang chờ xuất |
| `FAILED` | 2 | Ngân hàng từ chối hoặc không đủ số dư |
| `CANCELLED` | 1 | Khách hàng chủ động hủy |
| `EXPIRED` | 1 | Quá thời hạn thanh toán |
| `UNDERPAID` | 1 | Đã nhận thiếu tiền, cần đối soát và chưa xuất hóa đơn |
| `PENDING` | 1 | Link thanh toán còn chờ khách hàng thực hiện |

Các bất biến hóa đơn được giữ đúng: chỉ đơn `PAID` mới có trạng thái chờ xuất/đã xuất hóa đơn; đơn chưa thành công chỉ có chi tiết giao dịch. Lịch sử gia hạn của An Phát và Minh Long tạo dữ liệu cho renewal, doanh thu thực thu, MRR và doanh thu theo gói.

Minh Long hết hạn ngày 12/09 và An Phát hết hạn ngày 20/09, vì vậy các bộ lọc thuê bao sắp hết hạn 7, 15 và 30 ngày đều có dữ liệu để kiểm thử.

## Bốn công ty tối giản

| Email | Công ty | Vai trò | Trạng thái |
|---|---|---|---|
| `owner.minhlong@fams.test` | Logistics Minh Long | `TENANT_ADMIN` | Hoạt động, email đã xác thực |
| `owner.saoviet@fams.test` | Dịch vụ Sao Việt | `TENANT_ADMIN` | Hoạt động, email đã xác thực |
| `owner.phuchung@fams.test` | Nội thất Phúc Hưng | `TENANT_ADMIN` | Hoạt động, email đã xác thực |
| `owner.bacnam@fams.test` | Cơ điện Bắc Nam | `TENANT_ADMIN` | Hoạt động, email đã xác thực |

Bốn công ty này phục vụ màn hình quản trị nền tảng, vòng đời thuê bao, thanh toán và kiểm tra tenant isolation; không chứa dữ liệu nhân sự/công trình dư thừa.

## Các bất biến được kiểm tra tự động

Seed sẽ trả lỗi nếu một trong các điều kiện sau bị vi phạm:

- không đủ 5 tenant demo hoặc 15 thành viên seed chuẩn của An Phát;
- tài khoản demo chưa hoạt động/chưa xác thực email;
- Platform Admin sở hữu, tham gia hoặc có hồ sơ tại công ty;
- một nhân viên có thiếu hoặc thừa role công ty;
- số role không đúng ma trận 1/2/4/8;
- quản lý công trình không được site-scope;
- nhân viên thiếu phòng ban chính;
- assignment tham chiếu nhân viên/site/shift thuộc tenant khác;
- công ty sai múi giờ Việt Nam, tiền tệ VND hoặc thiếu thuê bao;
- ma trận subscription thiếu Active, Trial, Expired hoặc Cancelled;
- lịch sử không đủ 524 phiên/20 lượt vắng hoặc không phủ cả tháng 7, 8 và 9;
- thiếu các trường hợp muộn, về sớm, OT, thiếu checkout, chờ duyệt và từ chối;
- thiếu 18 random check hoặc không đủ năm nhóm vi phạm;
- thiếu trạng thái thanh toán, doanh thu ba tháng hoặc sai vòng đời hóa đơn;
- còn phiên check-in demo mở quá hạn.
