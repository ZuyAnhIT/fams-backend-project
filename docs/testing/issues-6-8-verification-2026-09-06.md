# Minh chứng sửa vấn đề 6–8 — 06/09/2026

## Phạm vi

1. Kết quả gán vai trò hàng loạt không còn hiển thị câu tiếng Anh `User <uuid> already has role ...`.
2. App có thể dùng hồ sơ đang đăng nhập và danh sách ca/công trình đã đồng bộ để tạo lượt check-in khi mất mạng.
3. Trạng thái phân công được tách khỏi trạng thái đang làm việc thực tế.

## Kết quả nghiệp vụ

### Gán trùng vai trò

- Backend trả: `Nhân viên đã có vai trò "Nhân viên" trong công ty này.`
- Không trả UUID tài khoản hoặc mã hệ thống `EMPLOYEE` trong thông báo người dùng.
- Web có lớp dịch tương thích với thông báo tiếng Anh của backend cũ trong giai đoạn triển khai cuốn chiếu.

### Check-in offline

- Cache được tách theo `userId + tenantId`, tránh dùng nhầm dữ liệu giữa hai tài khoản/công ty.
- Chỉ dùng cache của cùng ngày nghiệp vụ Việt Nam và không quá 24 giờ.
- Hồ sơ đang có trong phiên không bị màn chấm công loại bỏ chỉ vì `/auth/me` refetch lỗi mạng.
- Với ca GPS: App lấy GPS và đưa lượt vào hàng đợi cục bộ.
- Với ca yêu cầu Face ID/liveness: App cho chụp ảnh bằng chứng offline; backend đối soát khi có mạng. Liveness offline không được tự coi là đạt và có thể chuyển `pending_review` cho HR theo chính sách hiện hữu.
- Backend vẫn là nguồn quyết định cuối: kiểm tra tenant, nhân viên, phân công, khung giờ, geofence, trùng lặp, tuổi bản ghi và Face ID lúc đồng bộ.

Điều kiện an toàn: người dùng cần đăng nhập/mở App khi có mạng trong cùng ngày để tiến trình nền tải lịch/ca mới nhất (không bắt buộc phải mở tab Chấm công trước). Nếu chưa có cache hợp lệ, App không tự đoán phân công.

### Trạng thái phân công

- `Assignment.status=active`: bản ghi chưa bị HR hủy; không mô tả việc nhân viên đang có mặt hay đang làm việc.
- `Assignment.lifecycleStatus`: `upcoming`, `effective`, `completed`, `cancelled`, tính theo múi giờ Việt Nam/site và hỗ trợ ca qua đêm.
- Giao diện dùng nhãn `Sắp bắt đầu`, `Đang hiệu lực`, `Đã kết thúc`, `Đã hủy`; không dịch `active` thành `Đang làm việc`.
- Muốn hiển thị “Đang làm việc” phải dựa trên một phiên check-in còn mở, không dựa trên phân công.

## Kiểm thử đã chạy

| Thành phần | Lệnh | Kết quả |
|---|---|---|
| Backend trong Docker | `docker exec fams-api sh ./mvnw -q test` | 60 test, 0 lỗi |
| Backend hồi quy trực tiếp | `bash ./mvnw -q -Dtest=AssignmentLifecycleResolverTest,UserRoleServiceBulkLocalizationTest,AssignmentServiceExactSelectionTest test` | 9 test, 0 lỗi |
| App | `npm run quality` | lint + typecheck đạt; 22 test, 0 lỗi |
| Web | `npm run typecheck` và `npm run lint -- --quiet` | đạt |
| Web E2E | `npx playwright test tests/e2e/shift-assignment-management.spec.ts --grep "Phân công hết ca"` | 1 test, 0 lỗi |

Test biên trạng thái gồm ca thường kết thúc đúng phút, ca qua đêm kết thúc ngày hôm sau và bản ghi bị hủy. Test cache offline gồm còn hạn trong cùng ngày, đổi ngày Việt Nam và quá giới hạn 24 giờ.

## Ảnh minh chứng

- Toàn màn hình bảng phân công: `../fams-front-web-project/docs/test-evidence/shift-assignment-management/06-completed-assignment-status.png`
- Badge trạng thái: `../fams-front-web-project/docs/test-evidence/shift-assignment-management/06-completed-assignment-status-badge.png`

## Kịch bản kiểm tra trên điện thoại thật

1. Đăng nhập khi có mạng, mở tab **Chấm công** và chờ danh sách ca hiện ra.
2. Bật chế độ máy bay, quay lại tab **Chấm công**.
3. Xác nhận có banner **Đang dùng lịch đã lưu trên thiết bị** và ca đúng vẫn hiển thị.
4. Thực hiện chấm công; với chính sách Face ID, chụp ảnh theo hướng dẫn.
5. Xác nhận màn hình báo lượt đang chờ đồng bộ.
6. Bật mạng; App tự đồng bộ. Kiểm tra lịch sử có nguồn `offline`; nếu là liveness offline thì kiểm tra hàng chờ HR khi backend trả `pending_review`.
