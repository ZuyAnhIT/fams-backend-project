# Minh chứng sửa luồng thanh toán PayOS và chặn mua trùng gói

Ngày kiểm tra: 06/09/2026 (Asia/Ho_Chi_Minh).

## Nguyên nhân lỗi tạo thanh toán

Database local đã được dựng/nạp seed lại nên `billing_order_code_seq` quay về dải
`100000`. Tuy nhiên PayOS lưu `orderCode` theo vòng đời tài khoản merchant, không theo
database local. Ví dụ database ghi nhận đơn mới `100011` là `CREATE_FAILED`, trong khi
truy vấn chỉ đọc từ PayOS xác nhận `100011` đã tồn tại với trạng thái `CANCELLED` và số
tiền 10.000 VND. PayOS vì thế từ chối tạo payment link mới có cùng mã.

Migration `V125__use_reset_safe_payos_order_codes.sql` chuyển sequence sang dải Unix
time mili-giây và vẫn lấy giá trị lớn hơn mã lớn nhất trong database. Cách này giữ mã
đơn tăng dần, nằm trong miền số nguyên an toàn của JavaScript/PayOS và không quay lại
dải cũ khi dựng database vào thời điểm khác.

## Quy tắc mua gói

- Subscription `ACTIVE` còn hạn (hoặc không có ngày hết hạn) không được tạo checkout
  cho chính gói đang dùng, không phụ thuộc chu kỳ tháng/năm.
- API trả HTTP 409, mã `SUBSCRIPTION_PLAN_ALREADY_ACTIVE` và thông báo tiếng Việt.
- Subscription đã hết thời hạn hoặc đã ở trạng thái khác `ACTIVE` được mua lại gói cũ.
- Frontend khóa thẻ gói hiện tại, tự chọn một gói hợp lệ khác và giải thích rõ lý do.
- Backend vẫn là lớp chặn cuối để không thể bỏ qua quy tắc bằng cách gọi API trực tiếp.
- Payment link hợp lệ đã phát hành trước đó vẫn được xử lý nếu tiền thực sự đến; hệ
  thống không được nhận tiền rồi từ chối kích hoạt dịch vụ.

## Kết quả kiểm tra

Unit test:

```text
bash ./mvnw -q -Dtest=BillingOrderServiceTest test
Kết quả: PASS
```

Frontend:

```text
npx eslint src/features/shared/billing/components/BillingCheckoutPanel.tsx
npm run typecheck
Kết quả: PASS
```

Migration trên môi trường Docker local:

```text
Flyway version 125: success
billing_order_code_seq: 1788691633320 (chưa sử dụng tại thời điểm kiểm tra)
```

Kiểm tra tích hợp bằng tài khoản quản trị Công ty An Phát:

```text
Mua lại gói Doanh nghiệp đang ACTIVE:
  success=false
  errorCode=SUBSCRIPTION_PLAN_ALREADY_ACTIVE

Tạo checkout gói Khởi đầu:
  success=true
  orderCode=1788691633320
  status=PENDING
  checkoutUrlReturned=true

Dọn đơn kiểm tra trên PayOS:
  success=true
  status=CANCELLED
```

Không có giao dịch chuyển tiền nào được thực hiện trong lượt kiểm tra tích hợp.
