# Minh chứng vấn đề 12–14 và báo cáo điều hành — 06/09/2026

## Phạm vi đã hoàn thành

### Vấn đề 12 — đổi trạng thái nhân viên

- Chặn propagation tại cả trigger và menu item của Ant Design Dropdown.
- `onRow` có guard cho button/link/input/menu item, nên xác nhận đổi trạng thái không còn điều hướng sang trang chi tiết.
- Mutation hoàn tất, hiển thị thông báo tiếng Việt và vẫn ở danh sách.

### Vấn đề 13 — số phút bị che khi hover

- Toàn bộ trường phút trong modal OT dùng số nguyên và tắt spinner (`controls=false`).
- Hậu tố “phút” không còn chồng lên giá trị tại cột hẹp; hover vẫn nhìn thấy đầy đủ số.

### Vấn đề 14 — bật OT không lưu được

- Giữ đúng bất biến nghiệp vụ: bật OT phải có cửa sổ checkout sau giờ ca lớn hơn 0.
- Khi bật, frontend tự điền mặc định 120 phút nếu dữ liệu cũ là 0/rỗng, vẫn cho quản trị viên chỉnh tối đa 1.440 phút.
- Cả frontend và backend cùng validate; backend trả mã `INVALID_OT_CHECKOUT_WINDOW` và thông báo tiếng Việt thay cho lỗi 400 chung chung.
- Không dùng “không giới hạn” cho cửa sổ checkout nhằm tránh phiên chấm công mở treo và trạng thái “Đang làm việc” sai sau ca.

### Bốn báo cáo quản trị

- Platform: Doanh thu & gói dịch vụ; Tăng trưởng & sức khỏe khách hàng.
- Tenant: Hiệu quả nhân sự & chấm công; Rủi ro & tuân thủ.
- Có KPI, so kỳ, xu hướng, phân bổ, funnel, cảnh báo cần hành động và drill-down.
- Platform có biểu đồ kết quả thanh toán và đổi nhanh danh sách thuê bao sắp hết hạn theo 7/15/30 ngày.
- Tenant hiển thị giờ trung bình/nhân viên, số vi phạm ảnh hưởng bảng công và Pareto nguyên nhân có đường tỷ lệ tích lũy.
- Bộ lọc platform gồm thời gian, công ty, gói và trạng thái thuê bao; backend áp dụng trực tiếp vào truy vấn tổng hợp thay vì chỉ lọc dữ liệu đã tải ở trình duyệt.
- Bộ lọc tenant gồm thời gian, công trình, workspace, ca và nhân viên; áp dụng quyền `reports:list` và site scope.
- Thực thu dùng thời điểm `paid_at`; MRR tách riêng và quy đổi gói năm `/ 12`.
- Vi phạm dùng tỷ lệ trên 100 lượt chấm công; quá hạn xử lý tính từ 24 giờ.

## Kết quả kiểm thử

| Lệnh | Kết quả |
|---|---|
| `docker exec fams-api sh -lc 'mvn -q test'` | PASS, 64 test, 0 lỗi |
| `bash tests/report/test_management_analytics.sh` | PASS, 19/19 trường hợp API thật |
| `npm run build` | PASS, Next.js production build và TypeScript |
| `npm run lint` | PASS, 0 lỗi/cảnh báo sau khi dọn mã |
| Playwright liên quan employee/shift/analytics | PASS, 15/15 kịch bản Chromium |

Maven chạy trực tiếp ngoài Docker từng gặp lỗi duy nhất ở `ApiServerApplicationTests` do mật khẩu PostgreSQL của profile máy host không trùng container. Đây không phải lỗi logic; cùng toàn bộ suite chạy trong `fams-api` đúng biến môi trường Docker đạt exit code 0.

## Test API thật bao phủ

- Platform Admin đọc revenue/health: 200.
- Tenant Admin bị chặn endpoint toàn nền tảng: 403.
- Lọc platform đồng thời theo công ty/gói/trạng thái thuê bao: 200; trạng thái không hỗ trợ: 400.
- HR đọc workforce/risk và lọc site/workspace/employee: 200.
- HR lọc workforce/risk theo ca, bao gồm random check: 200.
- Drill-down từ dashboard giữ đồng thời ngày/công trình/workspace/ca/nhân viên tới lịch sử chấm công; workspace không khớp trả 0 bản ghi thay vì làm rò dữ liệu.
- Nhân viên thường đọc báo cáo quản trị: 403.
- Khoảng ngày đảo ngược: 400.
- Kiểm tra đầy đủ field KPI, MRR/thực thu, by-plan, funnel, expiring, trend, aging và site risk.

## Ảnh minh chứng giao diện

Frontend lưu tại `docs/test-evidence`:

- `employee-management/06-status-change-stays-on-list.png`
- `shift-assignment-management/07-ot-default-window-and-visible-minutes.png`
- `shift-assignment-management/08-ot-save-success.png`
- `management-analytics/01-platform-revenue.png`
- `management-analytics/02-platform-customer-health.png`
- `management-analytics/03-tenant-workforce.png`
- `management-analytics/04-tenant-risk.png`

## Giới hạn dữ liệu được diễn giải đúng

- Storage không được suy đoán từ database khi object storage không có snapshot quota theo ngày; điểm gần giới hạn hiện dùng employee/site/random-check. Lượt report/export vẫn được đếm từ audit log.
- Cohort lịch sử và forecast doanh thu nâng cao cần snapshot sự kiện theo ngày để không tái dựng sai trạng thái quá khứ. Dashboard hiện chỉ hiển thị các chỉ số có nguồn dữ liệu đáng tin cậy trong hệ thống.
