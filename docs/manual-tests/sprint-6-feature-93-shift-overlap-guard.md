# Kịch bản test thủ công — #93 Chặn khung giờ kiểm tra ngẫu nhiên không trùng ca làm việc

**Nền tảng: Backend, Web Admin.**

## Bối cảnh — phát hiện từ ca hỗ trợ khách hàng thật (2026-08-22)
Khách hàng tạo công ty "FOFO", 1 công trình, cấu hình kiểm tra ngẫu nhiên (checksPerShift=3,
allowedStartTime=15:00, allowedEndTime=17:00, checkMode=location_face_liveness), 1 ca làm việc
"Ca Chiều" (14:25-14:35). Không có kiểm tra ngẫu nhiên nào được gửi cho nhân viên dù cấu hình đã
tồn tại và Face ID đã đăng ký xong.

**Nguyên nhân**: `ScheduledCheckGeneratorService#resolveEffectiveWindow` giao khung giờ cấu hình
với giờ ca làm thực tế trước khi sinh lịch (có chủ đích, tránh gửi kiểm tra ngoài giờ làm) — khung
15:00-17:00 và ca 14:25-14:35 không giao nhau, phần giao rỗng, không sinh gì cả, không báo lỗi ở
đâu. Không phải bug — nhưng gap thật: hệ thống không cảnh báo NGƯỜI DÙNG tại thời điểm lưu cấu
hình, khiến gap này im lặng vô thời hạn.

## Đã vá — cả Backend và Web Admin

### Backend
`RandomCheckConfigService#validateOverlapsSiteShifts` (mới) — gọi khi tạo/sửa **site override**
(không áp dụng tenant-default, vốn cố ý không gắn 1 ca cụ thể): nếu site đã có ≥1 ca active và
khung giờ cấu hình không giao với **bất kỳ** ca nào (bỏ qua ca qua đêm, cùng logic loại trừ như
generator thật), trả **400** kèm thông điệp liệt kê tên+giờ từng ca.

### Web Admin
`RandomCheckConfigFormModal.tsx` — thêm validator live trên field "Kết thúc khung giờ", fetch danh
sách ca của site (`useShiftsQuery`) và kiểm tra giao nhau ngay khi người dùng nhập, không cần chờ
submit mới biết lỗi.

## A. Test Backend (API thật)
- Tạo site + 1 ca `14:25-14:35` (test) → tạo site override với khung `15:00:00-17:00:00` →
  **400**, message: `"Allowed window (15:00–17:00) does not overlap any active shift at this
  site: \"Ca ngan\" (14:25–14:35)..."`.
- Cùng site, đổi khung sang `13:00:00-15:00:00` (giao với ca 120 phút, đủ cho
  checksPerShift=3/minIntervalMinutes=45) → **200**, tạo/sửa thành công.
- Site chưa có ca nào → không chặn gì (không có gì để đối chiếu).
- Tenant-default config → không áp dụng check này (đúng thiết kế, không gắn 1 ca cụ thể).
- `tests/randomcheck/test_random_check_config_site_override.sh` — thêm 3 case mới, PASS 16/16
  (toàn bộ file), chạy ổn định 2 lần liên tiếp.

## B. Test Web Admin (Playwright, trình duyệt thật)
- Mở form "Tùy chỉnh riêng cho site" tại trang chi tiết công trình, nhập khung `15:00-17:00`
  (site chỉ có ca `14:25-14:35`) → submit → **dòng cảnh báo đỏ hiện đúng ngay dưới field "Kết
  thúc khung giờ"**: "Khung giờ này không trùng với bất kỳ ca làm nào tại công trình:
  "Ca Chieu" (14:25–14:35)...".
- Đổi khung sang `14:00-15:00` (giao ca, đủ feasibility với checksPerShift=1) → submit →
  **"Đã tạo policy riêng cho công trình."** — thành công, policy hiển thị đúng trên bảng.

## Regression
`test_random_check_config.sh` 16/16, `test_random_check_config_site_override.sh` 16/16,
`test_manual_check.sh` 16/16, `test_no_response_violation.sh` 12/12,
`test_dispatch_notification.sh` 9/9 — không hồi quy. `tests/run_all.sh` (146 suite toàn dự án)
chạy lại sau khi deploy để xác nhận không ảnh hưởng diện rộng.

## Dữ liệu FOFO thật của khách hàng
**Không tự động sửa** — đã giải thích nguyên nhân + hướng dẫn khách hàng tự chọn sửa ca làm hay
sửa khung giờ cấu hình (quyết định nghiệp vụ của khách hàng, không phải lỗi hệ thống cần backend
tự "sửa hộ"). Ghi nhận: site FOFO sau đó đã có thêm 1 ca "Ca Chiều 1" (14:50-15:15) giao đúng với
khung 15:00-17:00 — khách hàng tự khắc phục được sau khi hiểu nguyên nhân.
