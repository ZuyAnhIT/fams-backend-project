# Kịch bản test thủ công — #90 Đánh dấu đã đọc

**Nền tảng: Backend, Web Admin, Mobile App.**

## ✅ PASS — ĐÃ KHÓA (2026-08-17)

Không có gap chức năng — xác nhận qua test live, không cần sửa code. Đã bổ sung bằng chứng test
cho luồng batch (đánh dấu nhiều thông báo được CHỌN LỌC) vốn trước đây chỉ có bằng chứng qua đọc
code, chưa test live.

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-17)

### 1. ✅ Đánh dấu 1 thông báo — đúng quyền sở hữu (regression, không đổi)
- `test_mark_read.sh` — 13/13 pass, bao gồm case cross-user 404.

### 2. ✅ Batch mark-read (`PATCH .../notifications/read` với danh sách ID) — MỚI test live
- Tạo 3 notification chưa đọc, gọi batch với 2/3 ID.
- **Kết quả thực tế:** response `{"markedCount":2}`; DB xác nhận đúng 2 bản ghi được chọn chuyển
  `is_read=true` kèm `read_at`, bản ghi thứ 3 KHÔNG bị chọn vẫn giữ nguyên `is_read=false` — xác
  nhận đúng: chỉ đánh dấu ĐÚNG những gì được chọn, không ảnh hưởng phần còn lại.

### 3. ✅ Unread badge giảm đúng (regression, không đổi)
- `test_mark_read.sh` case 8 — `unreadCount=0` sau "đọc tất cả".

---

## B. Test trên Web Admin / Mobile App
- Chế độ "chọn nhiều" đã xác nhận đúng ở tầng backend (case 2) — hai FE đều gọi cùng endpoint batch
  này (`handleMarkSelectedAsRead` / `markSelectedAsRead`), nên hành vi UI được suy ra là đúng qua
  chuỗi API đã xác nhận. Chưa chụp ảnh riêng UI cho luồng chọn nhiều trong đợt này.

## Regression
`tests/notification/test_mark_read.sh` — 13/13 PASS, không regression.
