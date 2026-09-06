# Minh chứng bộ dữ liệu demo v4 — 06/09/2026

## Phạm vi

Bộ seed v4 tạo dữ liệu xác định cho local/staging từ 15/07/2026 đến hết ngày làm việc 05/09/2026, dùng múi giờ `Asia/Ho_Chi_Minh` và tiền tệ `VND`.

## Kết quả chạy thực tế

Lệnh:

```bash
bash scripts/seed.sh
```

Kết quả assertion database:

| Chỉ số seed quản lý | Kết quả |
|---|---:|
| Công ty demo | 5 |
| Thành viên An Phát | 15 |
| Phòng ban | 5 |
| Công trình | 4 |
| Phân công dài hạn | 12 |
| Phiên chấm công 15/07–05/09 | 524 |
| Bản ghi có tình huống ngoại lệ | 54 |
| Kiểm tra ngẫu nhiên | 18 |
| Đơn thanh toán nền tảng | 13 |

Chạy liên tiếp hai lần đều đạt và không tăng số bản ghi do seed quản lý.

## Phân bổ chấm công

| Tháng | Có chấm công | Đi muộn | Về sớm | Có OT | Thiếu checkout | Phút làm việc | Phút OT |
|---|---:|---:|---:|---:|---:|---:|---:|
| 07/2026, từ ngày 15 | 172 | 6 | 3 | 7 | 0 | 91.589 | 585 |
| 08/2026 | 295 | 9 | 5 | 17 | 2 | 155.313 | 1.380 |
| 09/2026, đến ngày 05 | 57 | 2 | 0 | 2 | 1 | 29.918 | 180 |

Ngoài các dòng trên còn có 20 lượt vắng không tạo check-in, 42 phiên offline, một phiên chờ duyệt, một phiên bị từ chối và một lượt vượt giới hạn OT ngày.

## Random check và tuân thủ

- 18 lần kiểm tra, gồm kiểm tra tự động và 2 kiểm tra thủ công.
- Có đủ 5 loại vi phạm: `location_fail`, `face_fail`, `liveness_fail`, `no_response`, `face_verify_timeout`.
- Có cả vi phạm đã xác nhận, được bác bỏ sau giải trình, chưa xử lý và quá hạn.
- Có 33 thông báo seed v4, trong đó 17 thông báo chưa đọc.
- Có 8 audit event nghiệp vụ trải từ tháng 7 đến tháng 9.

## Thanh toán nền tảng

| Tháng | Đơn đã thanh toán | Doanh thu thực thu |
|---|---:|---:|
| 07/2026 | 3 | 70.000 đ |
| 08/2026 | 3 | 80.000 đ |
| 09/2026 | 1 | 40.000 đ |

Phân bố trạng thái: 7 `PAID`, 2 `FAILED`, 1 `CANCELLED`, 1 `EXPIRED`, 1 `UNDERPAID`, 1 `PENDING`. Thuê bao có đủ Active, Trial, Expired và Cancelled. Bộ lọc sắp hết hạn 7/15/30 ngày lần lượt trả về 1/2/2 thuê bao.

## Kiểm thử API

Lệnh:

```bash
bash tests/report/test_management_analytics.sh
```

Kết quả: **19 đạt, 0 thất bại**. Bao gồm phân quyền Platform Admin/Tenant Admin/Employee, doanh thu–MRR, sức khỏe khách hàng, hiệu quả nhân sự, rủi ro và drill-down theo công trình/workspace/ca/nhân viên.

## An toàn dữ liệu

- Không dùng email thật; toàn bộ tài khoản demo sử dụng miền `.test`.
- Platform Admin không sở hữu và không có role tại công ty.
- Seed chỉ dựng lại UUID/history do chính seed quản lý; dữ liệu người dùng tạo thủ công được giữ lại.
- Không tạo phiên check-in mở quá hạn.
- Giao dịch chưa `PAID` không được đánh dấu đã xuất hóa đơn.
