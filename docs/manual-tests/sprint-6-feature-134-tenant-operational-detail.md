# Kịch bản test thủ công — #134 Xem chi tiết tenant vận hành

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — bằng chứng: `TenantDetailService`/`TenantDetailResponse`,
`test_tenant_detail.sh`; thiếu: không có cảnh báo gần vượt limit; chưa track storage usage. Audit
lại code hiện tại (2026-08-19) — **audit gốc ĐÚNG cả 2, nhưng cần làm rõ mức độ**:

- **✅ Employees/sites/random-checks: đếm thật từ DB, KHÔNG phải placeholder.**
- **🟡 "Cảnh báo gần vượt limit": không phải backend field, nhưng ĐÃ được frontend tính toán và
  hiển thị đúng** (progress bar chuyển màu vàng ở 80%, đỏ ở 100% cho 3 số liệu có current thật) —
  thỏa mãn Ý ĐỊNH của AC dù cách triển khai khác (client-side thay vì backend trả field riêng).
  KHÔNG coi đây là gap cần vá thêm.
- **❌ GAP thật duy nhất: "storage usage" — hoàn toàn không có hạ tầng theo dõi ở bất kỳ đâu**
  (không cột DB, không logic cộng dồn kích thước file). Đã hỏi người dùng về hướng giải quyết
  (do liên quan tới quyết định kiến trúc thật sự, không phải chỉ thiếu code).

## ✅ ĐÃ VÁ (2026-08-19) — theo quyết định người dùng: "xây dựng nhẹ, chỉ tính file qua S3/MinIO"

- `TenantStorageUsageService` mới: tính dung lượng đã dùng on-demand (liệt kê S3/MinIO lúc gọi
  API, không lưu counter riêng) gồm:
  - File bằng chứng giải trình vi phạm (`explanation-evidence/{tenantId}/...` — đã tenant-prefix
    sẵn từ trước, quét trực tiếp).
  - Avatar (`avatars/{userId}-...` — KHÔNG tenant-prefix, phải resolve tập userId thuộc tenant
    trước rồi khớp key).
- **CỐ Ý KHÔNG bao gồm**: ảnh đăng ký/chấm công Face ID (lưu ở `ai-service`, hệ thống file riêng
  trên host, không phải S3, không thể quét từ Java backend trong phạm vi đợt vá này) — số liệu
  trả về là **mức tối thiểu (floor)**, không phải tổng chính xác. UI đã ghi rõ giới hạn này.
- `TenantDetailResponse` thêm field `currentStorageGb` (double, GB). Web Admin (cả
  `TenantDetailPage.tsx` lẫn `TenantUsagePanel.tsx` — có 2 nơi hiển thị usage trùng lặp, cả 2 đều
  đã cập nhật) hiện đúng số đã dùng + progress bar + ghi chú giới hạn.

---

## A. Test trên Backend

### 1. ✅✅ (Case quan trọng nhất) Storage usage tính đúng, cộng dồn đúng cả 2 loại file
- Setup: upload trực tiếp 1 file 28 byte vào `explanation-evidence/{tenantId}/...` và 1 file 26
  byte vào `avatars/{ownerId}-...` qua MinIO client.
- **Kỳ vọng — xác nhận đúng qua live API call:** `GET /tenants/{id}/detail` trả
  `currentStorageGb` = (28+26)/1073741824 ≈ 5.03e-8 — khớp chính xác byte-for-byte, không lệch.

### 2. ✅ Employees/sites/random-checks vẫn đếm đúng như cũ (không đổi hành vi)

## B. Test trên Web Admin

### 3. ✅✅ ĐÃ TEST LIVE qua Playwright thật — hiển thị số liệu storage mới + ghi chú giới hạn
- Trang chi tiết tenant (Platform Admin): mục "Dung lượng lưu trữ (GB)" hiện đúng "0 / 6" (số
  thật, không còn "API chưa có số đã dùng"), ghi chú bên dưới hiện đúng nội dung nói rõ KHÔNG gồm
  ảnh Face ID.

---

## Ghi chú
Tính năng cố ý on-demand (không có migration/cột DB mới, không counter duy trì) theo đúng phạm vi
người dùng chọn ("lightweight" thay vì "exact") — nếu quy mô tenant/bucket lớn lên, sẽ cần chuyển
sang counter cập nhật lúc upload/xóa thay vì quét trực tiếp mỗi lần gọi API. `tsc --noEmit` sạch.
Backend regression (`tests/tenant/*.sh`) 91/91 pass cùng đợt #131-135.
