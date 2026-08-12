# Bản đồ tính năng × nền tảng — toàn bộ 150 tính năng (07/08/2026)

> Mục đích: liệt kê lại **toàn bộ tính năng của hệ thống** (150 mục theo `docs/BACKLOG.md`/audit gần nhất) và phân loại theo nền tảng, để dùng làm checklist review giao diện thực tế. Không suy đoán — đối chiếu trực tiếp với route/màn hình thật trong 2 repo frontend (`fams-front-web-project`, `fams-front-app-project`) tại thời điểm viết tài liệu, cộng với các doc "Web hay App?" đã có sẵn ở `docs/api/*-ui-permissions-guide.md` và các báo cáo tích hợp phía FE (`fams-front-app-project/docs/29,30,32,34,35_*.md`, `fams-front-web-project/docs/onboarding/34_*.md`).
>
> Ký hiệu: **Web** = Company Portal/Admin Console (`fams-front-web-project`) · **App** = Mobile App nhân viên (`fams-front-app-project`) · **Cả hai** = có màn tương ứng ở cả 2 · **Ngầm** = không có màn hình riêng (chạy nền, validation nội bộ, hoặc chỉ lộ ra gián tiếp qua kết quả/thông báo lỗi của 1 tính năng khác) · **Chưa dựng** = có API/hạ tầng nhưng chưa có UI ở nền tảng lẽ ra nên có.

---

## Phần 1 — Tính năng chạy ngầm / không có màn hình riêng (23 mục)

Nhóm này **không cần review UI trực tiếp** — hoặc vì chúng chạy hoàn toàn nội bộ (cron, validation, tính toán), hoặc vì kết quả của chúng chỉ hiển thị lồng bên trong màn hình của 1 tính năng khác (không có nút/màn riêng để bấm vào).

| # | Tính năng | Vì sao không có UI riêng |
|---|---|---|
| 14 | Khóa tài khoản khi đăng nhập sai | Chỉ hiện qua thông báo lỗi 423 lúc đăng nhập (#1), không có màn "tài khoản bị khóa" riêng |
| 23 | Seed role và permission hệ thống | Chạy 1 lần lúc deploy qua Flyway migration |
| 31 | Ghi audit cho hành động quan trọng | Cơ chế ghi nền; **kết quả** xem được ở #136 (Web) |
| 32 | Tạo notification in-app cơ bản | Endpoint nội bộ (service gọi service); **kết quả** xem được ở #89 |
| 71 | Kiểm tra check-in sớm | Validation, chỉ hiện qua thông báo lỗi trong luồng check-in App (#68) |
| 73 | Kiểm tra check-out muộn | Gộp vào tính OT/work-minutes (#74), không có màn riêng |
| 74 | Tính work_minutes | Tính toán nội bộ, hiển thị ở bảng công (#85/86) |
| 80 | Tự động tạo attendance summary | Job + tính real-time lúc check-in/out |
| 81 | Tính đi muộn | Hiện dạng cờ/số phút trong bảng công (#85/86), không có màn riêng |
| 82 | Tính về sớm | Tương tự #81 |
| 83 | Tính OT | Tương tự, hiện trong bảng công |
| 84 | Phát hiện thiếu checkout | Cờ `missingCheckout` trong bảng công |
| 87 | Đăng ký thiết bị nhận push | Tự động lúc mở App (device token), không có màn thao tác |
| 88 | Gửi push notification | Cơ chế gửi nền; **kết quả** xem được ở #89 |
| 96 | Tự động sinh scheduled checks đầu ca | Cron nội bộ |
| 97 | Snapshot config khi sinh check | Cơ chế nội bộ lúc sinh check |
| 98 | Tạo Bull/BullMQ job gửi check | Redis sorted-set + job poll nội bộ (không dùng BullMQ thật, xem `docs/api/backend-feature-audit-2026-08-01.md`) |
| 100 | Gửi random check notification | Cơ chế gửi nền; **kết quả** nhận ở App (#89/#101) |
| 105 | Từ chối phản hồi trễ | Validation, hiện qua lỗi 410 trong luồng phản hồi App (#102-104) |
| 106 | Tạo violation khi không phản hồi | Job nền (`NoResponseViolationJob`) |
| 107 | Tạo violation khi fail random check | Logic nội bộ trong luồng phản hồi |
| 135 | Enforce giới hạn gói | Chặn nội bộ ở backend; hiện qua thông báo lỗi 403 `PLAN_LIMIT_EXCEEDED` ở màn đang thao tác (mời nhân viên, tạo site...) |
| 138 | Trace theo request_id | App gửi header `X-Request-Id` ngầm mọi request; Web dùng để tra cứu **bên trong** màn Audit Viewer (#136), không phải màn riêng |
| 140 | Retry và fallback notification | Cơ chế gửi lại + fallback email nội bộ, không có màn thao tác |
| 142 | Cron refresh attendance nightly | Job đêm |
| 143 | Monitor scheduled check job | Cơ chế theo dõi nội bộ; **kết quả sức khoẻ job** hiện 1 phần ở #147 (Web) |
| 144 | Dọn dữ liệu ảnh và notification cũ | Job tuần (`DataRetentionJob`) |
| 145 | Mask dữ liệu nhạy cảm trong audit và API | Cơ chế redact tại tầng serialize/ghi, tự động, không có công tắc UI |
| 146 | Guard quyền API theo RBAC | Cross-cutting — bảo vệ MỌI API, không phải 1 màn cụ thể |
| 148 | Kịch bản kiểm thử end-to-end | Tài liệu QA (`docs/testing/manual-test-scenarios.md`), không phải tính năng có UI |
| 150 | Checklist triển khai tenant đầu tiên | Tài liệu vận hành (`docs/deployment/go-live-checklist.md`), không phải tính năng có UI |

*(29 dòng trên gồm cả #148/#150 — 2 mục này về bản chất là tài liệu, liệt kê ở đây để không sót khỏi 150 mục gốc, không phải "tính năng chạy ngầm" đúng nghĩa.)*

---

## Phần 2 — Tính năng có giao diện, theo nền tảng

### 2.1 Auth & Tài khoản cá nhân (Sprint 1, #1–13)

| # | Tính năng | Nền tảng | Ghi chú |
|---|---|---|---|
| 1 | Đăng nhập email/mật khẩu | **Cả hai** | |
| 2 | Đăng nhập OTP số điện thoại | **Cả hai** | |
| 3 | Đăng nhập Google | **Cả hai** | App có link/unlink tài khoản Google riêng |
| 4 | Đăng xuất thiết bị hiện tại | **Cả hai** | |
| 5 | Đăng xuất tất cả thiết bị | **Cả hai** | Web: `settings/(personal)/sessions`; App: tab `sessions` |
| 6 | Đăng ký tài khoản | **Cả hai** | |
| 7 | Quên mật khẩu | **Cả hai** | |
| 8 | Đặt lại mật khẩu | **Cả hai** | |
| 9 | Đổi mật khẩu | **Cả hai** | |
| 10 | Xem thông tin cá nhân | **Cả hai** | |
| 11 | Cập nhật hồ sơ cá nhân | **Cả hai** | |
| 12 | Bật TOTP 2FA (thiết lập) | **Cả hai** | App có đầy đủ setup QR/secret, xác nhận TOTP, backup codes và tắt 2FA trong Hồ sơ |
| 13 | Đăng nhập có 2FA (xác thực) | **Cả hai** | |

### 2.2 Tenant / Platform / RBAC (Sprint 1, #15–30)

**100% Web** — đúng chủ đích, không có tính năng nào trong nhóm này hợp lý trên Mobile App (quản trị công ty/quyền hạn là nghiệp vụ back-office).

| # | Tính năng | Nền tảng |
|---|---|---|
| 15 | Tạo tenant mới | Web |
| 16 | Danh sách tenant | Web |
| 17 | Cập nhật tenant | Web |
| 18 | Cấu hình giao diện/định dạng | Web *(App chưa đọc dateFormat/timeFormat/màu brand dù quyền API đã cho phép — quyết định sản phẩm chưa chốt)* |
| 19 | Quản lý IP whitelist | Web |
| 20 | Quản lý gói dịch vụ | Web |
| 21 | Cấu hình giới hạn gói | Web |
| 22 | Gán subscription cho tenant | Web |
| 24 | Danh sách role | Web |
| 25 | Tạo role tùy chỉnh | Web |
| 26 | Sửa role và quyền | Web |
| 27 | Xóa/vô hiệu hóa role | Web |
| 28 | Xem permission theo nhóm | Web |
| 29 | Gán role cho user | Web |
| 30 | Thu hồi role | Web |

### 2.3 Invitation / Employee / Workspace / Face ID (Sprint 2, #33–51)

| # | Tính năng | Nền tảng | Ghi chú |
|---|---|---|---|
| 33 | Mời nhân viên bằng email | Web | |
| 34 | Chấp nhận lời mời | **Cả hai** | Bấm link email trên điện thoại (mở App) hoặc trình duyệt (Web) |
| 35 | Hủy lời mời | Web | |
| 36 | Danh sách nhân viên | Web | |
| 37 | Xem chi tiết nhân viên (người khác) | Web | |
| 38 | Tạo nhân viên thủ công | Web | |
| 39 | Cập nhật nhân viên (HR sửa) | Web | |
| 40 | Tạm ngừng/nghỉ việc nhân viên | Web | |
| 41 | Import danh sách (Excel) | Web | |
| 42 | Export danh sách (Excel) | Web | |
| 43 | Tạo workspace | Web | |
| 44 | Danh sách workspace | Web | |
| 45 | Cập nhật workspace | Web | |
| 46 | Gán nhân viên vào workspace | Web | |
| 47 | Chuyển workspace cho nhân viên | Web | |
| 48 | Ghi nhận đồng ý Face ID | App | Chỉ chính nhân viên tự bấm được, kể cả HR cũng không consent hộ |
| 49 | Đăng ký Face ID | App *(chính)* | HR có thể hỗ trợ tại kiosk nhưng đăng nhập bằng tài khoản nhân viên, không phải màn Web riêng cho HR |
| 50 | HR xem trạng thái Face ID | Web | |
| 51 | Xóa/vô hiệu hóa Face ID | **Cả hai** | HR thu hồi của người khác (Web) hoặc nhân viên tự thu hồi (App) |

### 2.4 Site / Geofence / Shift / Assignment (Sprint 2, #52–67)

Phần **tạo/sửa/ngừng dùng** (#52, #55–66) vẫn Web-only. App có màn đọc danh sách/chi tiết site và danh sách phân công theo permission cho Supervisor; App không có form quản trị hoặc vẽ polygon geofence.

| # | Tính năng | Nền tảng | Ghi chú |
|---|---|---|---|
| 52 | Tạo công trình | Web | |
| 53 | Danh sách công trình | **Cả hai** | App chỉ đọc, dành cho vai trò có `sites:list`/`sites:read` |
| 54 | Xem chi tiết công trình | **Cả hai** | App hiển thị thông tin, geofence, ca và supervisor; không cho sửa |
| 55 | Cập nhật công trình | Web | |
| 56 | Tạo geofence | Web | |
| 57 | Sửa geofence | Web | |
| 58 | Xem lịch sử geofence | Web | |
| 59 | Tạo ca làm việc | Web | |
| 60 | Cấu hình OT/giới hạn giờ | Web | Vừa bổ sung 4 field mới (#60 fix 07/08) — form đã cập nhật |
| 61 | Danh sách ca theo site | Web | |
| 62 | Cập nhật/ngừng dùng ca | Web | |
| 63 | Tạo phân công | Web | |
| 64 | Danh sách phân công | **Cả hai** | App read-only trong phạm vi công trình và permission; Web là màn quản trị chính |
| 65 | Cập nhật phân công | Web | |
| 66 | Hủy phân công | Web | |
| 67 | Hiển thị site được phép check-in hôm nay | **App** | Màn đầu tiên nhân viên thấy khi mở app để chấm công |

### 2.5 Check-in / Check-out / Attendance (Sprint 3, #68–86)

| # | Tính năng | Nền tảng | Ghi chú |
|---|---|---|---|
| 68 | Check-in GPS cơ bản | App | |
| 69 | Check-in có Face ID | App | |
| 70 | Check-in có liveness | App | |
| 72 | Check-out GPS | App | |
| 75 | Check-in offline và đồng bộ | App | |
| 76 | Hiển thị kết quả check-in/out | App | |
| 77 | Nhân viên xem lịch sử chấm công | App | |
| 78 | HR xem danh sách check-in | Web | |
| 79 | HR xem chi tiết check-in | Web | |
| 85 | Nhân viên xem bảng công ngày/tháng | App | Có badge cảnh báo OT mới (#60 fix) |
| 86 | HR xem bảng công tổng hợp | Web | Có badge cảnh báo OT mới (#60 fix) |

### 2.6 Notification & Random Check (Sprint 3-4, #89–113)

| # | Tính năng | Nền tảng | Ghi chú |
|---|---|---|---|
| 89 | Danh sách thông báo trong app/web | **Cả hai** | |
| 90 | Đánh dấu đã đọc | **Cả hai** | |
| 91 | Cấu hình random check mặc định tenant | Web | |
| 92 | Cấu hình override theo site | Web | |
| 93 | Cấu hình số lần/khung giờ check | Web | |
| 94 | Cấu hình mode kiểm tra | Web | |
| 95 | Cấu hình áp dụng theo vai trò | Web | |
| 99 | Hủy scheduled check | Web | HR huỷ; cũng tự huỷ ngầm khi assignment bị huỷ |
| 101 | App hiển thị random check đang chờ | App | |
| 102 | Phản hồi mode chỉ vị trí | App | |
| 103 | Phản hồi mode vị trí + Face ID | App | |
| 104 | Phản hồi mode vị trí + Face ID + Liveness | App | |
| 108 | HR kích hoạt kiểm tra ngay | Web | |
| 109 | HR xem danh sách scheduled checks | Web | |
| 110 | HR xem chi tiết random check | Web | |
| 111 | HR override check-in | Web | |
| 112 | HR chỉnh attendance summary | Web | |
| 113 | Nhân viên gửi giải trình check-in lỗi | App | Qua `POST /checkin/{id}/explain`, không qua "My Exceptions" (module đó chỉ đọc) |

### 2.7 Violation / Dashboard / Reports / Search / UX (Sprint 5, #114–132)

| # | Tính năng | Nền tảng | Ghi chú |
|---|---|---|---|
| 114 | HR xem danh sách vi phạm | Web | |
| 115 | HR xem chi tiết violation | Web | |
| 116 | Xác nhận vi phạm | Web | |
| 117 | Bỏ qua vi phạm | Web | |
| 118 | Cập nhật ảnh hưởng công | Web | |
| 119 | Dashboard nhân viên | **App** | |
| 120 | Dashboard HR | **Web** | |
| 121 | Dashboard giám sát công trình | **Cả hai** | Web có bản đồ (#130); App chỉ hiện số liệu on-site theo site, **cố tình không dựng bản đồ** trên mobile |
| 122 | Báo cáo công ngày | Web | |
| 123 | Báo cáo công tháng | Web | |
| 124 | Export bảng công | Web | |
| 125 | Báo cáo vi phạm theo kỳ | Web | |
| 126 | Báo cáo hiện diện theo site | Web | |
| 127 | Báo cáo trạng thái Face ID | Web | |
| 128 | Tìm kiếm nhanh nhân viên/site/check-in | Web | `GlobalSearch` trong Header, chưa có trên App |
| 129 | Thông báo lỗi thân thiện | **Cả hai** | Cross-cutting, mọi màn ở cả 2 nền tảng |
| 130 | Bản đồ site và vị trí hiện tại | **Cả hai, khác mục đích** | Web Supervisor hiển thị tọa độ tại lúc check-in, không phải live tracking. App nhân viên hiển thị vị trí hiện tại so với geofence trước khi chấm công; App không có bản đồ giám sát tập thể. |
| 131 | Lưu bộ lọc thường dùng | Web | App chủ động không dựng — chưa có màn danh sách quản trị tương ứng để áp lại bộ lọc |
| 132 | Export danh sách vi phạm | Web | |

### 2.8 Platform Admin / Audit / Notification config / System status (Sprint 6, #133–147, #149)

| # | Tính năng | Nền tảng | Ghi chú |
|---|---|---|---|
| 133 | Khóa/mở tenant | Web | Admin Console |
| 134 | Xem chi tiết tenant vận hành | Web | Admin Console |
| 136 | Xem danh sách audit log | Web | Admin Console + Company Portal (theo quyền); App **cố tình không có** Audit Viewer |
| 137 | Xem diff old/new value | Web | Cùng màn #136 |
| 139 | Quản lý template thông báo | Web | |
| 141 | Cấu hình nhận thông báo cá nhân | **Cả hai** | Web: `settings/(personal)/notifications`; App: tab `notification-settings` |
| 147 | Màn trạng thái hệ thống | Web | Admin Console, Platform Admin only |
| 149 | Hướng dẫn Admin/HR/Employee | **Cả hai** | Web: `admin/help` + `customer/help`; App: tab `help` |

---

## Phần 3 — Tính năng thực tế đang chạy nhưng KHÔNG nằm trong danh sách 150 mục gốc (phát hiện thêm)

Các tính năng dưới đây tồn tại thật trong code (cả 2 frontend đều dùng), nhưng không có số thứ tự trong checklist 150 mục gốc bạn từng gửi — bổ sung để bạn khai báo lại nếu cần đưa vào backlog chính thức:

| Tính năng | Nền tảng | Mô tả ngắn |
|---|---|---|
| Chuyển đổi/chọn công ty đang làm việc (multi-tenant switching) | **Cả hai** | `GET /roles/me` + `POST /auth/switch-tenant` — 1 tài khoản có thể thuộc nhiều tenant, cần màn chọn/chuyển. Web: `customer/select-company`; App: `select-tenant` |
| Liên kết/hủy liên kết tài khoản Google | **Cả hai** | Mở rộng của #3, đặt mật khẩu cho tài khoản chỉ đăng nhập bằng Google |
| Mời nhân viên nền tảng (Platform Staff) qua email | Web | Khác lời mời nhân viên công ty (#33) — dành riêng cho Platform Admin mời nội bộ FAMS |
| Tenant tự huỷ vĩnh viễn (`cancel`, khác khoá/mở #133) | Web | |
| Nhân viên tự xem kết quả 1 lượt random check cụ thể (`my-result`) | App | Tách khỏi #101 (danh sách đang chờ) |

---

## Tóm tắt số liệu

- **Web-only**: ~78 tính năng (chiếm đa số — đúng bản chất back-office/quản trị của các nhóm Tenant/RBAC/Site/Shift/Employee/Report)
- **App-only**: ~20 tính năng (đúng bản chất "nhân viên tự dùng hằng ngày": check-in, Face ID, random check response, dashboard cá nhân)
- **Cả hai**: ~19 tính năng (chủ yếu Auth, thông báo, hồ sơ cá nhân, dashboard giám sát)
- **Ngầm/không có UI riêng**: ~23 tính năng (Phần 1)
- **Chưa dựng dù có hạ tầng**: không còn #12. #18 đã được App đọc để áp dụng date/time và màu brand ở các bề mặt chính; việc phủ màu brand cho mọi component legacy tiếp tục theo lộ trình UI-token hóa.

**Cách dùng khi review**: mở song song 3 cửa sổ VSCode theo đúng quy trình đang dùng — với mỗi dòng "Web" hoặc "App" ở Phần 2, mở đúng route đã dẫn (hoặc tìm theo tên component) và đối chiếu với Acceptance Criteria gốc trong `docs/BACKLOG.md`. Phần 1 và Phần 3 không cần mở UI — Phần 1 review qua code/log, Phần 3 là phát hiện thêm cần bạn quyết định có đưa vào backlog chính thức hay không.
