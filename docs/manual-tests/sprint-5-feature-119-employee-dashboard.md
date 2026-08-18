# Kịch bản test thủ công — #119 Dashboard nhân viên

**Nền tảng: Backend, Mobile App.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "thiếu random-check-pending, violation-pending,
notifications". Đã audit lại code hiện tại (2026-08-18) — **phần lớn ĐÃ SAI/LỖI THỜI:**

- **✅ `EmployeeDashboardResponse.alerts`** đã có `pendingExplanations` (gộp
  `checkin.status=pending_review` + `violation.resolved=false` của nhân viên) và
  `unreadNotifications` — cả 2 field audit gốc nói "thiếu" đều đã có sẵn trong code.
- **⚠️ "random-check-pending" đúng là KHÔNG có trong chính response `/dashboard/employee`** —
  nhưng Mobile App Home screen (`app/(tabs)/home.tsx`) gọi THÊM `useMyPendingRandomChecks()`
  (endpoint riêng, đã có từ #101) và hiển thị đúng số lượng qua badge trên card "Kiểm tra ngẫu
  nhiên" (`activeRandomChecks.length`) + dòng mô tả "N yêu cầu cần phản hồi ngay". Về mặt SẢN
  PHẨM (màn hình người dùng thực sự thấy), không có gap — chỉ là kiến trúc 2 API riêng thay vì 1
  API gộp, một lựa chọn hợp lý (tránh N+1 phình to 1 endpoint).

---

## A. Test trên Backend
### 1. ✅ `GET /dashboard/employee` trả đúng `alerts.pendingExplanations` và `unreadNotifications`
- Nhân viên có 1 checkin `pending_review` + 1 violation `resolved=false` + 2 notification chưa đọc.
- **Kỳ vọng:** `pendingExplanations=2`, `unreadNotifications=2`.

## B. Test trên Mobile App
### 2. ✅ Home screen hiển thị đúng badge "Kiểm tra ngẫu nhiên" khi có random check đang chờ
- Nhân viên có 1 scheduled check `status=sent` chưa hết hạn.
- **Kỳ vọng:** card "Kiểm tra ngẫu nhiên" hiện badge số + mô tả "1 yêu cầu cần phản hồi ngay".

### 3. ✅ Card "Cần giải thích" và "Thông báo" hiển thị đúng số từ `alerts`
- **Kỳ vọng:** khớp đúng `pendingExplanations`/`unreadNotifications` từ API.

---

## Ghi chú
Không có gap cần vá — audit gốc phần lớn lỗi thời. Regression: `test_employee_dashboard.sh`
(36/36 cùng đợt #116-120) PASS, không thay đổi code cho tính năng này.
