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

Seed có tính idempotent: chạy lại sẽ cập nhật đúng các bản ghi demo định danh sẵn, không nhân bản dữ liệu. Sau khi nạp, `scripts/verify_demo_seed.sql` tự kiểm tra số lượng, trạng thái tài khoản, vai trò, tenant isolation và quan hệ nghiệp vụ.

Mật khẩu chung của tài khoản demo: `Admin@1234`.

## Phạm vi dữ liệu

Chỉ có ba công ty demo đang hoạt động:

| Công ty | Slug | Mức dữ liệu | Gói |
|---|---|---|---|
| Công ty CP Xây dựng An Phát | `demo-an-phat` | Đầy đủ | Doanh nghiệp |
| Công ty TNHH Logistics Minh Long | `demo-minh-long` | Tối giản | Khởi đầu |
| Công ty TNHH Dịch vụ Sao Việt | `demo-sao-viet` | Tối giản | Khởi đầu |

Tất cả công ty dùng `Asia/Ho_Chi_Minh`, locale `vi-VN`, tiền tệ `VND` và thuê bao đang hoạt động.

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

## Dữ liệu lịch sử tháng 09/2026

Seed tạo 48 phiên chấm công từ ngày 01–04/09/2026 cho 12 người có phân ca, gồm:

- ngày công bình thường;
- một trường hợp đi muộn;
- một trường hợp về sớm;
- một trường hợp OT;
- một trường hợp quên checkout đã được đóng logic, không để phiên mở treo;
- kiểm tra ngẫu nhiên thành công, không phản hồi và sai vị trí;
- hai vi phạm chưa xử lý để HR thử quy trình duyệt;
- hồ sơ Face ID demo cho toàn bộ nhân sự vận hành;
- thông báo phân công và một audit log khởi tạo dữ liệu.

## Hai công ty tối giản

| Email | Công ty | Vai trò | Trạng thái |
|---|---|---|---|
| `owner.minhlong@fams.test` | Logistics Minh Long | `TENANT_ADMIN` | Hoạt động, email đã xác thực |
| `owner.saoviet@fams.test` | Dịch vụ Sao Việt | `TENANT_ADMIN` | Hoạt động, email đã xác thực |

Hai công ty này chỉ phục vụ màn hình quản trị nền tảng, thuê bao và kiểm tra tenant isolation; không chứa dữ liệu nhân sự/công trình dư thừa.

## Các bất biến được kiểm tra tự động

Seed sẽ trả lỗi nếu một trong các điều kiện sau bị vi phạm:

- không đủ đúng 3 tenant demo hoặc 15 thành viên An Phát;
- tài khoản demo chưa hoạt động/chưa xác thực email;
- Platform Admin sở hữu, tham gia hoặc có hồ sơ tại công ty;
- một nhân viên có thiếu hoặc thừa role công ty;
- số role không đúng ma trận 1/2/4/8;
- quản lý công trình không được site-scope;
- nhân viên thiếu phòng ban chính;
- assignment tham chiếu nhân viên/site/shift thuộc tenant khác;
- công ty sai múi giờ Việt Nam, tiền tệ VND hoặc thiếu thuê bao;
- lịch sử chấm công bắt đầu trước tháng 09/2026;
- còn phiên check-in demo mở quá hạn.
