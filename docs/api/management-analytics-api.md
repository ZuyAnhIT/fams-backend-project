# API báo cáo điều hành

Các API này phục vụ dashboard ra quyết định, chỉ đọc dữ liệu và dùng múi giờ nghiệp vụ `Asia/Ho_Chi_Minh` khi quy đổi biên ngày.

## Quản trị nền tảng

Chỉ `PLATFORM_ADMIN` được truy cập:

```http
GET /api/v1/platform/reports/revenue?from=2026-01-01&to=2026-09-30&expiryDays=30
GET /api/v1/platform/reports/customer-health?from=2026-01-01&to=2026-09-30
```

Tham số lọc chung của hai báo cáo nền tảng:

| Tham số | Kiểu | Mô tả |
|---|---|---|
| `from`, `to` | `yyyy-MM-dd` | Mặc định 30 ngày gần nhất, tối đa 3 năm |
| `tenantId` | UUID | Chỉ số của một công ty cụ thể |
| `planId` | UUID | Chỉ số của một gói dịch vụ cụ thể |
| `subscriptionStatus` | enum | `TRIAL`, `ACTIVE`, `EXPIRED` hoặc `CANCELLED` |
| `expiryDays` | số nguyên | Chỉ áp dụng cho revenue; cửa sổ sắp hết hạn 1–90 ngày |

Các điều kiện công ty/gói/trạng thái được áp dụng ở backend vào toàn bộ KPI, biểu đồ và danh sách liên quan. Giá trị `subscriptionStatus` ngoài danh sách trên trả HTTP 400.

`revenue` trả về:

- doanh thu thực thu theo `billing_orders.paid_at` trong kỳ;
- MRR hiện tại, trong đó gói năm được chia 12;
- tỷ lệ chuyển đổi, gia hạn, churn, ARPA và thanh toán thành công;
- trạng thái subscription/thanh toán, xu hướng tháng, doanh thu theo gói và funnel;
- thuê bao sắp hết hạn theo `expiryDays` (giới hạn 1–90 ngày; UI có lựa chọn nhanh 7/15/30 ngày).

`customer-health` trả về:

- công ty/người dùng mới và đang hoạt động;
- người dùng hoạt động 7/30 ngày, nhân viên, công trình, lượt chấm công;
- mức sử dụng chấm công, Face ID, random check, báo cáo/export;
- công ty không hoạt động, điểm sức khỏe, cảnh báo churn và gần chạm giới hạn gói.

Doanh thu thực thu và MRR không được cộng chung: tiền gói năm được ghi nhận toàn bộ vào thực thu tại thời điểm thanh toán, nhưng chỉ `giá năm / 12` được dùng trong MRR.

## Quản trị công ty

Cần quyền `reports:list`; dữ liệu tiếp tục bị giới hạn theo site scope của người gọi:

```http
GET /api/v1/tenants/{tenantId}/reports/workforce-effectiveness
GET /api/v1/tenants/{tenantId}/reports/risk-compliance
```

Tham số chung:

| Tham số | Kiểu | Mô tả |
|---|---|---|
| `from`, `to` | `yyyy-MM-dd` | Mặc định 30 ngày gần nhất, tối đa 366 ngày |
| `siteId` | UUID | Công trình; trả 403 nếu ngoài site scope |
| `workspaceId` | UUID | Phòng ban/workspace chính của nhân viên |
| `shiftId` | UUID | Ca làm; áp dụng cho chấm công, random check và vi phạm liên quan |
| `employeeId` | UUID | Nhân viên cụ thể |

`workforce-effectiveness` tổng hợp tỷ lệ có mặt/vắng/đi muộn/về sớm/thiếu checkout, giờ làm/OT, so kỳ trước, xu hướng ngày, công trình và ngày trong tuần.

`risk-compliance` tổng hợp vi phạm trên 100 lượt chấm công, tồn đọng/quá hạn trên 24 giờ, thời gian xử lý trung bình/trung vị, tỷ lệ giải trình được chấp nhận, random check, Face ID, xu hướng, điểm nóng công trình và nhân viên tái phạm.

## Quy tắc khoảng ngày

- `from > to`: HTTP 400.
- Dashboard công ty: tối đa 366 ngày.
- Dashboard nền tảng: tối đa 3 năm.
- Dữ liệu tiền luôn là VND nguyên (`long`), phần trăm làm tròn một chữ số thập phân.
