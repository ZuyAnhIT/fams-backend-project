# Kịch bản test thủ công — #131 Lưu bộ lọc thường dùng

**Nền tảng: Web Admin.**

ℹ️ Audit gốc (07-22): ❌ CHƯA LÀM — "không có bảng/code SavedFilter nào". Audit lại code hiện tại
(2026-08-19) — **audit gốc HOÀN TOÀN LỖI THỜI**: tính năng đã được xây dựng đầy đủ từ
**2026-08-06** (commit "feat: add saved filters audit logs and violation export filtering") —
SAU ngày audit gốc 07-22, nên không phải audit gốc sai lúc viết, chỉ là chưa cập nhật lại.

- **✅ Backend: đầy đủ** — module `savedfilter` riêng (Entity/Repository/Service/Controller),
  bảng `saved_filters` riêng (không dùng chung `tenant_settings` như backlog gốc dự kiến — thiết
  kế đúng hơn vì đây là dữ liệu theo user, không phải theo tenant). API:
  `GET/POST /tenants/{id}/saved-filters`, `PATCH/DELETE /{filterId}`. Scope theo user+tenant+
  resourceType, không ảnh hưởng người khác (đúng AC).
- **✅ Frontend: component đầy đủ** — `SavedFilterToolbar` (dropdown chọn bộ lọc đã lưu, nút
  "Lưu bộ lọc hiện tại", "Đặt mặc định" tự áp dụng lúc mở trang, "Xóa" chỉ xóa khỏi tài khoản
  riêng).
- **❌ GAP thật duy nhất còn lại: mới tích hợp ở đúng 1 màn hình (Vi phạm)** — các danh sách lớn
  khác (nhân viên, công trình, báo cáo) chưa có nút "Lưu bộ lọc".

## ✅ ĐÃ VÁ (2026-08-19)
Wiring thêm `SavedFilterToolbar` vào 2 màn danh sách lớn khác:
- **Danh sách nhân viên** (`/customer/employees`) — lưu được `status`/`department`/`workspaceId`/
  `faceRegistered`.
- **Danh sách công trình** (`/customer/sites`) — lưu được `status`.

(Chưa mở rộng thêm ra các trang báo cáo khác trong đợt này — đã có sẵn hạ tầng generic, có thể
tích hợp thêm bất kỳ lúc nào chỉ bằng cách thêm `SavedFilterToolbar` + `savedParams`/
`applySavedFilter`, không cần sửa backend.)

---

## A. Test trên Backend (đã có sẵn, không đổi trong đợt này)

### 1. ✅✅ (Case quan trọng nhất) CRUD saved filter hoạt động đúng, scope đúng theo resourceType
- **Kỳ vọng — xác nhận đúng qua live API call:** tạo saved filter `resourceType=employees`,
  `isDefault=true` → `POST` trả 200 với đúng `filterParams`; `GET ?resourceType=employees` trả về
  đúng bộ lọc vừa tạo.

## B. Test trên Web Admin

### 2. ✅✅ ĐÃ TEST LIVE qua Playwright thật — SavedFilterToolbar trên trang Nhân viên và Công trình
- Cả 2 trang: chọn filter trạng thái → lưu thành bộ lọc có tên → toast "Đã lưu bộ lọc cá nhân" →
  reload trang → chọn lại từ dropdown → filter áp dụng đúng → xóa → dropdown rỗng đúng. Không có
  lỗi console liên quan tính năng.

---

## Ghi chú
`tsc --noEmit` phía Web Admin sạch. Không cần thay đổi backend cho đợt vá này — module
`savedfilter` đã generic sẵn theo `resourceType`, chấp nhận bất kỳ chuỗi nào không cần allow-list.
