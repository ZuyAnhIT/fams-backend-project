# Kịch bản test thủ công — #101 App hiển thị random check đang chờ

**Nền tảng: Backend, Mobile App.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG — "/scheduled-checks/my-pending". Đã xác nhận lại qua code hiện
tại — **ĐÚNG, không lỗi thời:**

- **`RandomCheckScreen.tsx`** — tính `remaining` = `expiresAt - now`, hiển thị đếm ngược
  (`formatCountdown`), hiển thị đúng nhãn yêu cầu theo mode (`RANDOM_CHECK_MODE_LABELS` suy từ
  `configSnapshot` của check — đúng nguyên tắc snapshot đã xác nhận ở #97, không đọc config sống).

---

## A. Test trên Backend
### 1. `GET /scheduled-checks/my-pending` trả đúng danh sách check đang chờ
- Nhân viên có 1 check `status=sent` chưa phản hồi.
- **Kỳ vọng:** trả về đúng check đó, kèm `expiresAt`, `configSnapshot` (để App biết yêu cầu mode).

## B. Test trên Mobile App
### 2. ✅ Đếm ngược đúng tới `expiresAt`
- Mở màn hình khi còn random check chờ phản hồi.
- **Kỳ vọng:** đồng hồ đếm ngược chạy đúng, giảm dần về 0.

### 3. ✅ Mở từ deep-link (push notification) vào đúng màn phản hồi
- Bấm vào push thông báo random check (xem #100).
- **Kỳ vọng:** mở thẳng vào màn phản hồi đúng `checkId`, không qua màn danh sách trung gian.

### 4. Hiển thị đúng yêu cầu theo mode
- Test lần lượt với check thuộc `location_only`, `location_face`, `location_face_liveness`.
- **Kỳ vọng:** UI hiển thị đúng yêu cầu tương ứng (chỉ vị trí / vị trí+selfie / vị trí+selfie+liveness).

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
Không có gap cần vá. Case 2-4 cần test tay trên App thật (đếm ngược/deep-link khó test đầy đủ qua
script tự động). `test_employee_pending_checks.sh` đã phủ phần backend cốt lõi.
