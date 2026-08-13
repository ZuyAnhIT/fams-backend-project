# Kịch bản test thủ công — #14 Khóa tài khoản khi đăng nhập sai

**Nền tảng: chỉ Backend** (không có màn hình UI riêng — đây là cơ chế chạy ngầm trong luồng đăng
nhập, đã được test qua giao diện thật ở kịch bản #1 case 4). File này chỉ bổ sung phần **chưa**
cover ở #1: xác nhận qua API xem audit log có ghi nhận lần đăng nhập sai/khóa hay không.

⚠️ **Phát hiện khi rà lại code trước khi viết kịch bản này** (mới phát hiện, chưa có trong
BACKLOG cũ): `AuthService.login()` chỉ ghi audit log cho **đăng nhập THÀNH CÔNG** (action
`LOGIN`). Các nhánh **sai mật khẩu** và **tài khoản bị khóa** hoàn toàn **không** ghi audit log,
dù Acceptance Criteria yêu cầu rõ "ghi audit result=failure/denied". Case 2 dưới đây xác nhận lại.

---

## A. Test (đã pass qua kịch bản #1 case 4, không cần lặp lại UI)
- ✅ Sai mật khẩu 5 lần liên tiếp → khóa 423, thông báo tiếng Việt kèm giờ mở khóa.
- ✅ Đặt lại mật khẩu (kịch bản #8 case 6) → tự mở khóa ngay, không cần đợi hết giờ.

## B. Case mới cần test (qua API, không cần UI)

### 1. Đếm số lần sai đúng, reset khi đăng nhập đúng
- Sai mật khẩu 2-3 lần (chưa tới ngưỡng khóa), sau đó đăng nhập đúng.
- **Kỳ vọng:** kiểm tra DB, `failed_login_attempts` phải về lại `0` sau lần đăng nhập đúng:
  ```bash
  docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT failed_login_attempts, locked_until FROM users WHERE email='admin@fams.com';"
  ```

### 2. Kiểm tra audit log khi sai mật khẩu / bị khóa (xác nhận gap đã nêu ở đầu file)
- Sai mật khẩu `admin@fams.com` 1-2 lần, sau đó đăng nhập lại đúng (lấy token), gọi:
  ```bash
  curl -s "http://localhost:8080/api/v1/audit-logs?userId=<id admin>&action=LOGIN_FAILED" \
    -H "Authorization: Bearer <token>"
  curl -s "http://localhost:8080/api/v1/audit-logs?userId=<id admin>&action=ACCOUNT_LOCKED" \
    -H "Authorization: Bearer <token>"
  ```
- **Kỳ vọng theo code hiện tại:** cả 2 đều trả về rỗng — xác nhận đúng gap. Đây là gap **quan
  trọng hơn** #9/#11 (thiếu audit đổi mật khẩu/hồ sơ) vì liên quan trực tiếp phát hiện tấn công
  brute-force — báo lại nếu muốn ưu tiên bổ sung sớm.

---

## Dọn dẹp sau khi test
```bash
docker exec fams-postgres psql -U fams_user -d fams_db -c \
  "UPDATE users SET failed_login_attempts=0, locked_until=NULL WHERE email='admin@fams.com';"
```
