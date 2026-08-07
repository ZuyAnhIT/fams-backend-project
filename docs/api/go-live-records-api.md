# API Reference: Go-live Records

**Mới 2026-08-06** theo yêu cầu FE: checklist UAT/go-live trước đây chỉ tồn tại dưới dạng markdown (`docs/deployment/go-live-checklist.md`), không có cách nào lưu lại thành biên bản chính thức (ai kiểm tra, tenant nào, môi trường/phiên bản nào, từng bước kết quả ra sao, bằng chứng, ai phê duyệt). Module này lưu đúng các trường đó.

Base path: `/api/v1/platform/go-live-records`
Quyền: `PLATFORM_ADMIN` hoặc `golive:manage`. Cross-tenant by nature (đội triển khai không nhất thiết là thành viên của tenant đang go-live).

---

## Vòng đời 1 bản ghi

`DRAFT` (đang thực hiện checklist, có thể sửa `steps`) → `APPROVED` hoặc `REJECTED` (kết thúc — **không sửa được nữa**, tạo bản ghi mới nếu cần chạy lại).

---

## `POST /api/v1/platform/go-live-records` — Bắt đầu 1 lượt go-live mới

```json
{
  "tenantId": "...",
  "environment": "production",
  "buildVersion": "2026.08.06-1",
  "steps": [
    { "stepName": "Env vars complete", "result": "PASS" },
    { "stepName": "System health UP", "result": "PASS", "evidenceUrl": "https://.../screenshot.png" },
    { "stepName": "UAT flow B.8 hoàn tất", "result": "FAIL", "note": "Bước export báo cáo lỗi timeout, đang xử lý" }
  ]
}
```

- `tenantId`, `environment`, `buildVersion` bắt buộc. `steps` tuỳ chọn (có thể để trống rồi bổ sung dần qua `PATCH .../steps`).
- `result` mỗi bước: `PASS`/`FAIL`/`SKIP` (chuỗi tự do, không validate cứng — FE tự định nghĩa danh sách bước theo `go-live-checklist.md`).
- Người tạo (`performedBy`) = người gọi API (tự động lấy từ JWT) — không truyền tay.
- Trả `201` + bản ghi đầy đủ, `status: "DRAFT"`.

## `GET /api/v1/platform/go-live-records` — Danh sách (phân trang)

Query: `tenantId`, `status` (`DRAFT`/`APPROVED`/`REJECTED`), `page`, `size`.

## `GET /{id}` — Chi tiết 1 bản ghi

## `PATCH /{id}/steps` — Cập nhật kết quả từng bước (chỉ khi còn `DRAFT`)

```json
{ "steps": [ { "stepName": "...", "result": "PASS" } ], "completed": true }
```

Thay **toàn bộ** danh sách `steps` (không phải patch từng phần tử) — gửi lại đầy đủ mọi bước kể cả bước không đổi. `completed: true` ghi nhận `completedAt` (chỉ set lần đầu). Trả **409** nếu bản ghi đã `APPROVED`/`REJECTED`.

## `POST /{id}/approve` — Phê duyệt

```json
{ "note": "Đã kiểm tra đầy đủ, sẵn sàng go-live" }
```

Ghi nhận người phê duyệt (`approvedBy` = người gọi API), thời điểm, và ghi chú. **Chỉ thực hiện được 1 lần** — gọi lại trên bản ghi đã quyết định trả `409`.

## `POST /{id}/reject` — Từ chối

Cùng shape với approve, set `status: "REJECTED"`. Muốn thử lại thì tạo bản ghi `POST` mới, không sửa bản ghi bị reject.

---

## FE integration notes

- **UI đề xuất**: màn "Go-live Checklist" cho đội triển khai — danh sách bước lấy từ `docs/deployment/go-live-checklist.md` làm template cố định phía FE (backend không áp đặt danh sách bước), mỗi bước có toggle PASS/FAIL/SKIP + ô ghi chú + upload bằng chứng (URL). Nút "Hoàn tất" gọi `PATCH .../steps` với `completed:true`, sau đó nút "Phê duyệt"/"Từ chối" riêng cho vai trò cấp cao hơn (người tạo checklist và người phê duyệt nên là 2 người khác nhau trong thực tế, dù backend không ép buộc điều này).
- Bản ghi đã `APPROVED`/`REJECTED` là **biên bản chính thức, bất biến** — FE nên hiển thị read-only rõ ràng, không cho sửa (khớp đúng 409 phía backend).
- Không có endpoint xoá — biên bản go-live là dữ liệu compliance, không nên xoá được qua API thông thường.
