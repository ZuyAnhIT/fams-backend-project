# Minh chứng quản lý thanh toán và hóa đơn — 06/09/2026

## Phạm vi đã kiểm tra

- Thanh cuộn sidebar và bảng thanh toán dùng thumb mảnh, bo tròn, đổi màu khi hover; vẫn hỗ
  trợ Firefox và Chromium.
- Platform Billing hiển thị tên công ty thay tenant UUID; tìm kiếm theo công ty/mã đơn/gói/mã
  giao dịch, lọc trạng thái/chu kỳ và sắp xếp theo các trường nghiệp vụ.
- Cả Owner công ty và Platform Admin mở được chi tiết giao dịch.
- Đơn chưa nhận tiền không có phiếu/hóa đơn; đơn `UNDERPAID` được đưa vào trạng thái kế toán
  `PAYMENT_REVIEW`; đơn `PAID` có phiếu xác nhận nội bộ và trạng thái hóa đơn
  `PENDING_ISSUANCE`.
- Tên công ty được snapshot tại thời điểm tạo đơn, nên tài liệu lịch sử không đổi khi tenant đổi tên.

## Minh chứng tự động

### Backend

```text
bash ./mvnw clean -Dtest=BillingOrderServiceTest test
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

docker exec fams-api mvn test
Tests run: 56, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Các assertion bao gồm webhook `PAID` idempotent, kích hoạt gói đúng một lần, chuyển nghĩa vụ
hóa đơn sang `PENDING_ISSUANCE`, sinh phiếu nội bộ, không sinh phiếu/hóa đơn cho đơn chưa
nhận tiền và buộc `UNDERPAID` vào luồng `PAYMENT_REVIEW`.

### Migration và API đang chạy

```text
Flyway: 122 | add billing buyer and invoice metadata | success=true
Flyway: 123 | flag underpaid orders for invoice review | success=true
Số đơn thiếu tenant_name_snapshot: 0
Số đơn PAID sai invoice_status: 0
```

Truy vấn API thật `search=An Phát&status=PAID&sortBy=amount&sortDir=desc` trả hai đơn theo
thứ tự `20.000 đ`, `10.000 đ`, có `tenantName`, `paymentReceiptNumber` và
`invoiceStatus=PENDING_ISSUANCE`.

### Frontend

```text
npm run typecheck
PASS

npx eslint <billing files + billing-management.spec.ts>
0 errors

npm run build -- --webpack
Compiled successfully; 52 routes generated

PLAYWRIGHT_PORT=3100 npx playwright test tests/e2e/billing-management.spec.ts
2 passed
```

Ảnh do Playwright tạo ở frontend:

- `docs/test-evidence/billing-management/01-platform-paid-detail.png`
- `docs/test-evidence/billing-management/02-company-cancelled-detail.png`

## Ranh giới nghiệp vụ hóa đơn

Phiếu tải từ FAMS được ghi rõ là chứng từ nội bộ, không thay thế hóa đơn điện tử/VAT. Trạng
thái `ISSUED` và đường dẫn tra cứu chỉ được phép có sau khi tích hợp dịch vụ hóa đơn điện tử
hợp pháp; phiên bản này không giả lập việc phát hành. Cách xác định thời điểm phát sinh nghĩa vụ
tham chiếu Nghị định 70/2025/NĐ-CP (hiệu lực từ 01/06/2025):
https://vanban.chinhphu.vn/?docid=213179&lang=vi&pageid=27160
