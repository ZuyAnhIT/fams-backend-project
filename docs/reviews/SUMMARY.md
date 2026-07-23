# FAMS — Bảng tổng hợp review tính năng theo Sprint

Đây là chỉ mục tổng, cập nhật sau mỗi tính năng được review. Chi tiết từng tính năng nằm ở:
- `docs/reviews/backend/sprint-N.md` — review code backend
- `docs/reviews/web/sprint-N.md` — đối chiếu Web Admin
- `docs/reviews/app/sprint-N.md` — đối chiếu Mobile App

Trạng thái: ✅ Đạt chuẩn · 🟡 Cần sửa nhỏ · 🔴 Cần sửa lớn/thiếu nghiêm trọng · ⏳ Đang review

## Sprint 1

| # | Tính năng | Backend | Web | App | Ghi chú |
|---|---|---|---|---|---|
| 1 | Đăng nhập email/mật khẩu | 🟡 | 🟡 | 🟡 | Backend: thiếu `last_login`+audit log+rate-limit, Swagger thiếu mã lỗi. Web: sai type response, field `rememberMe` chết, không hiện lỗi 423/403 rõ ràng. App: 2 bộ auth song song (nghi code chết), thiếu `deviceId`, 403 chưa phân biệt tenant-suspended. Không dự án nào có test tự động cho UI. |
