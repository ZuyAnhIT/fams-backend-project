# Kịch bản test thủ công — #87 Đăng ký thiết bị nhận push

**Nền tảng: Backend, Mobile App.**

## ✅ PASS — ĐÃ KHÓA (2026-08-17)

Không có gap chức năng thật — xác nhận lại qua test live, không cần sửa code. 2 điểm đã ghi nhận
trước đó không phải bug:
- AC ghi "lưu vào bảng `tokens`" nhưng bảng thật là `user_devices` — chỉ là AC ghi nhầm tên, không
  ảnh hưởng chức năng.
- Token mới của cùng thiết bị vật lý tạo bản ghi MỚI (không update tại chỗ) — do giới hạn kỹ thuật
  của FCM SDK (không có ID thiết bị ổn định để liên kết token cũ/mới), hành vi hợp lý.

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-17)

### 1. ✅ Đăng ký thiết bị mới thành công
- `POST /me/devices` với `deviceToken` mới.
- **Kết quả thực tế:** HTTP 200, bản ghi mới trong `user_devices`.

### 2. ✅ Đăng ký lại CÙNG `deviceToken` — không tạo trùng
- Xác nhận qua `findActiveByToken` — cập nhật lại đúng bản ghi cũ.

### 3. ✅ Regression: `tests/notification/test_fcm_devices.sh` — 13/13 pass
- Bao gồm case đăng ký, đăng ký lại, validation thiếu `deviceToken`.

---

## B. Test trên Mobile App
- **Cần test tay bổ sung trên thiết bị/dev-build thật** (Expo Go không cấp được push token thật,
  đây là giới hạn môi trường đã biết trước, không phải bug) — xác nhận: đăng nhập tự động đăng ký
  token; đăng xuất tự động hủy đăng ký (soft-delete, không xóa cứng); token refresh tự động đăng ký
  lại.

## Regression
`tests/notification/test_fcm_devices.sh` — 13/13 PASS, không regression.
