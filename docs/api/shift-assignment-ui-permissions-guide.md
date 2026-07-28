# Tài liệu bàn giao UI: Web vs App và ẩn/hiện theo vai trò — Quản lý Ca làm việc & Phân công

> Cập nhật theo code đang chạy ngày 27/07/2026. Đây **không phải** tài liệu API — chi tiết request/response đã có ở `docs/api/shift-assignment-management-api.md`, tài liệu này chỉ trả lời 2 câu hỏi khi dựng giao diện:
> 1. Tính năng này thuộc **Web** hay **App**?
> 2. Với từng vai trò đăng nhập, phần tử nào **ẩn hẳn**, phần tử nào **hiện nhưng disable**, phần tử nào **hiện đầy đủ**?

Phạm vi: 9 tính năng bạn yêu cầu.

## 1. Kết luận nhanh: App hay Web?

**Khác với Site/Geofence (100% Web) và giống nhóm Employee** — nhóm tính năng này **có cả phần Web (quản trị) lẫn phần App (nhân viên tự dùng)**, vì bản thân "phân công" chính là dữ liệu quyết định nhân viên nhìn thấy gì trên app mỗi ngày.

| Tính năng | Nền tảng |
|---|---|
| Tạo ca làm việc | **Web only** |
| Cấu hình OT/giới hạn giờ | **Web only** |
| Danh sách ca theo site | **Web only** |
| Cập nhật/ngừng dùng ca | **Web only** |
| Tạo phân công nhân viên vào site | **Web only** |
| Danh sách phân công (HR/Admin/Supervisor xem điều phối) | **Web only** |
| Cập nhật phân công | **Web only** |
| Hủy phân công | **Web only** |
| **Hiển thị site được phép check-in hôm nay** | **App (chính)** — đây là màn hình đầu tiên nhân viên thấy khi mở app để chấm công |

**Không có phần Ca/Phân công nào cần dựng thêm trên App ngoài tính năng 9** — nhân viên không tự tạo/sửa/xem lịch sử phân công của người khác, chỉ tự xem "hôm nay mình làm ở đâu, ca nào" qua đúng 1 API đã có sẵn.

## 2. Ma trận tổng hợp: Tính năng × Vai trò (Company Portal)

Ký hiệu: **Full** = thao tác đầy đủ · **View** = chỉ xem · **Ẩn** = không hiện.

| # | Tính năng | Endpoint chính | TENANT_ADMIN | HR_MANAGER | SITE_SUPERVISOR |
|---|---|---|---|---|---|
| 1 | Tạo ca | `POST .../shifts` | Full | Full | Ẩn |
| 2 | Danh sách ca | `GET .../shifts` | Full | Full | **View** (site được giao) |
| 3 | Cập nhật/deactivate ca | `PUT .../shifts/{id}` | Full | Full | Ẩn |
| 4 | Cấu hình OT | `PUT .../shifts/{id}/ot-config` | Full | Full | Ẩn |
| 5 | Xóa ca (mới, chỉ khi chưa dùng) | `DELETE .../shifts/{id}` | Full | Full | Ẩn |
| 6 | Tạo phân công | `POST .../assignments` | Full | Full | Ẩn |
| 7 | Danh sách phân công | `GET .../assignments` | Full | Full | **View** (site được giao) |
| 8 | Cập nhật phân công | `PUT .../assignments/{id}` | Full | Full | Ẩn |
| 9 | Hủy phân công | `DELETE .../assignments/{id}` | Full | Full | Ẩn |

**SITE_SUPERVISOR chỉ xem, không thao tác** — đúng seed quyền hiện tại (`shifts:read/list`, `assignments:read/list` — không có create/update/delete). Danh sách tự động giới hạn đúng site được giao (site-scope), không cần FE tự lọc thêm.

## 3. Chi tiết ẩn/hiện và các trạng thái nút cần lưu ý

### 3.1 Nút "Xóa ca" (mới) — giờ có sẵn field để biết trạng thái nút không cần thử-và-lỗi

- **Đã bổ sung** `assignmentHistoryCount`/`canDelete` vào `ShiftResponse` (theo đúng đề nghị của FE) — dùng trực tiếp `canDelete` để bật/tắt nút, không cần tự đếm hay gọi thêm API:
  - `canDelete = true` → **Enable + xác nhận** khi bấm.
  - `canDelete = false` → **Hiện nhưng disable + tooltip**, gợi ý dùng số liệu có sẵn: "Không thể xóa: ca này đã có {assignmentHistoryCount} lượt phân công. Dùng 'Ngừng dùng' để giữ lại lịch sử."
- Nút "Ngừng dùng" (deactivate qua `status=inactive`) nên đặt cạnh nút "Xóa" để người dùng chọn đúng hành động khi `canDelete=false`.

### 3.2 Form "Tạo/Sửa phân công" — 3 lỗi nghiệp vụ mới cần xử lý mượt trên UI

- **Dropdown chọn ca**: chỉ hiện các ca `status=active` của site đang chọn — ca inactive nên ẩn khỏi dropdown hoàn toàn (không chỉ hiện mờ), vì gán ca inactive giờ bị chặn cứng ở backend (`400`).
- **Dropdown chọn nhân viên**: nên lọc bỏ nhân viên có `status=terminated` (đã nghỉ việc) — backend giờ chặn cứng trường hợp này, lọc trước ở FE giúp tránh người dùng chọn rồi mới thấy lỗi. Nhân viên `inactive` (tạm ngừng) vẫn nên hiện được chọn (backend không chặn) — chỉ `terminated` mới bị chặn.
- **Xung đột đa-site** (lỗi mới, khó đoán trước ở FE vì phụ thuộc TOÀN BỘ phân công khác của nhân viên đó): không có cách lọc trước hợp lý ở FE (sẽ phải tải toàn bộ lịch của nhân viên trước khi submit, không thực tế cho 1 form đơn giản) — nên xử lý bằng cách **bắt lỗi `409` sau khi submit** và hiện đúng message backend trả về (đã có tên site xung đột cụ thể): *"Employee already has an overlapping active assignment at site 'X' during this period"*. Gợi ý thêm 1 dòng hướng dẫn: "Kiểm tra lại lịch phân công hiện tại của nhân viên này trước khi thử lại."

### 3.3 Màn "Site được phép check-in hôm nay" (App)

- Đã có sẵn, không cần dựng thêm logic — chỉ cần gọi đúng 1 API, hiển thị danh sách card: tên site, địa chỉ, ca (nếu có, kèm giờ bắt đầu/kết thúc), khoảng cách tới geofence nếu muốn (tính từ tọa độ site — không cần vẽ polygon, xem `site-geofence-ui-permissions-guide.md` mục 1.1).
- Nếu danh sách rỗng (nhân viên chưa được phân công gì hôm nay **hoặc** nhân viên không còn `active` — xem 3.4), hiện thông báo rõ ràng "Bạn chưa được phân công công trình nào hôm nay — liên hệ HR/quản lý" thay vì màn trắng.
- **Sau đợt sửa lần này**: danh sách này về mặt lý thuyết không còn khả năng hiện 2 site trùng giờ cho cùng 1 người nữa (đã chặn từ gốc ở bước tạo/sửa phân công) — nhưng FE vẫn nên xử lý an toàn nếu app hiện ra >1 kết quả cùng lúc (ví dụ 2 site không trùng giờ nhưng cùng ngày), không giả định luôn chỉ có đúng 1 kết quả.

### 3.4 [MỚI] Cập nhật theo báo cáo App team (27/07/2026) — 4 field mới + mã lỗi mới trên màn chấm công

Chi tiết kỹ thuật đầy đủ ở `docs/api/shift-assignment-management-api.md` mục 0.2. Việc App cần làm trên UI:

- **Dùng `availabilityStatus` để tô trạng thái từng card** thay vì tự tính giờ ở client (tránh lệch múi giờ/logic ca-qua-đêm với backend):
  - `unrestricted` — không ràng buộc giờ, nút "Chấm công" luôn bật.
  - `upcoming` — chưa tới giờ, nút disable + hiện đếm ngược tới `checkinAllowedFrom`.
  - `open` — trong cửa sổ cho phép, nút bật.
  - `closed` — ca đã kết thúc (`checkinAllowedUntil` đã qua), nút disable + label "Ca đã kết thúc" thay vì để người dùng bấm rồi nhận lỗi.
  - Dùng `serverNow` (không phải giờ máy điện thoại) làm mốc "bây giờ" nếu tự tính đếm ngược ở client, để tránh lệch khi giờ máy sai.
- **Bắt thêm 3 mã lỗi mới khi submit chấm công** (trước đây App chỉ cần xử lý `CHECKIN_TOO_EARLY`):
  | errorCode | HTTP | Hiển thị gợi ý |
  |---|---|---|
  | `EMPLOYEE_NOT_ACTIVE` | 403 | Không nên xảy ra nếu FE đã lọc theo 3.3 (danh sách rỗng khi không active) — nhưng vẫn nên có màn chặn rõ ràng phòng trường hợp trạng thái đổi giữa lúc tải danh sách và lúc bấm chấm công |
  | `SITE_INACTIVE` | 422 | "Công trình này hiện không hoạt động, vui lòng liên hệ quản lý" |
  | `CHECKIN_TOO_LATE` | 422 | "Ca làm việc đã kết thúc, không thể chấm công" — nên hiếm gặp nếu FE đã disable nút khi `availabilityStatus=closed`, nhưng vẫn cần xử lý (race condition giữa lúc mở màn và lúc bấm) |
  | `DUPLICATE_RESOURCE` (đổi hành vi) | 409 | Trước đây chỉ nghĩa "đã chấm công ca này rồi"; giờ nghĩa rộng hơn: "đang có phiên chấm công MỞ ở site khác chưa checkout" — nên đổi message hiển thị thành "Bạn đang có phiên chấm công chưa hoàn tất ở {site trong message lỗi} — vui lòng checkout trước" thay vì giả định luôn là cùng site |

## 4. Sơ đồ nav đề xuất

```text
COMPANY PORTAL (fams-front-web-project) — TENANT_ADMIN / HR_MANAGER / SITE_SUPERVISOR
└── Công trình → Chi tiết site → 2 tab liên quan tài liệu này
    ├── Tab "Ca làm việc"
    │   ├── Danh sách ca (SITE_SUPERVISOR chỉ xem)
    │   ├── Nút "Tạo ca" — ẩn với SITE_SUPERVISOR
    │   └── Click 1 ca → Sửa giờ/tên, Cấu hình OT, nút "Ngừng dùng"/"Xóa" (ẩn với SITE_SUPERVISOR)
    └── Tab "Phân công nhân viên"
        ├── Danh sách phân công — tìm/lọc/sort/phân trang (SITE_SUPERVISOR chỉ xem, đúng site được giao)
        ├── Nút "Phân công nhân viên mới" — ẩn với SITE_SUPERVISOR
        └── Click 1 dòng → Sửa ca/thời gian/vai trò, nút "Hủy phân công" (ẩn với SITE_SUPERVISOR)

MOBILE APP (fams-front-app-project)
└── Màn "Chọn công trình chấm công" (màn hình chính khi mở app)
    └── Danh sách card: site + ca (nếu có) + trạng thái geofence — từ đúng 1 API có sẵn
```

## 5. Checklist bàn giao frontend

- [ ] Route "Ca làm việc"/"Phân công" trên Company Portal: SITE_SUPERVISOR vẫn thấy (có `read/list`) nhưng mọi nút tạo/sửa/xóa/hủy phải ẩn hoàn toàn, không chỉ disable.
- [ ] Dropdown chọn ca trong form phân công: chỉ hiện ca `status=active` — backend giờ chặn cứng ca inactive.
- [ ] Dropdown chọn nhân viên trong form phân công: lọc bỏ `status=terminated` — backend giờ chặn cứng, lọc trước tránh UX xấu.
- [ ] Bắt lỗi `409` xung đột đa-site sau khi submit form phân công, hiện đúng message backend (đã có tên site xung đột) — không cần tự dựng logic kiểm tra trước ở FE, không khả thi cho 1 form đơn giản.
- [ ] Nút "Xóa ca" và "Ngừng dùng ca" nên đặt cạnh nhau với mô tả rõ khác biệt — Xóa chỉ dùng được khi ca chưa từng có lịch sử, còn lại luôn dùng "Ngừng dùng".
- [ ] Màn "Chọn công trình chấm công" trên App dùng đúng 1 API đã có sẵn, không cần gọi thêm API Site/Shift/Geofence riêng lẻ — response đã gộp sẵn.
- [ ] Không giả định danh sách "site được phép check-in hôm nay" luôn có đúng 1 phần tử — xử lý UI cho cả trường hợp rỗng và nhiều hơn 1 site hợp lệ cùng ngày (khác giờ).
- [ ] **Mới**: dùng `canDelete`/`assignmentHistoryCount` có sẵn trong `ShiftResponse` để bật/tắt nút Xóa ca — không cần tự đếm hay bắt lỗi `400` để suy ra trạng thái.
- [ ] **Mới**: dùng `employeeSummary`/`shiftSummary` có sẵn trong `AssignmentResponse` khi render bảng/chi tiết phân công — không cần tự gọi thêm API Employee/Shift theo từng dòng.
- [ ] **Mới**: "sáng Site A, tối Site B cùng ngày" giờ được phép (trước đây có thể bị `409` nhầm) — nếu FE từng tự chặn trường hợp này ở client trước khi submit, gỡ bỏ chặn đó, chỉ dựa vào phản hồi backend.
- [ ] **Mới (0.2)**: dùng `availabilityStatus`/`checkinAllowedFrom`/`checkinAllowedUntil`/`serverNow` mới trong `available-sites` để disable nút "Chấm công" đúng lúc (`upcoming`/`closed`) thay vì để người dùng bấm rồi nhận lỗi — xem mục 3.4.
- [ ] **Mới (0.2)**: bắt thêm 3 mã lỗi mới khi submit chấm công: `EMPLOYEE_NOT_ACTIVE` (403), `SITE_INACTIVE` (422), `CHECKIN_TOO_LATE` (422) — xem bảng mục 3.4.
- [ ] **Mới (0.2)**: đổi message hiển thị cho lỗi `409 DUPLICATE_RESOURCE` khi chấm công — không còn nghĩa "đã chấm công ca này", giờ nghĩa "đang có phiên mở ở site khác chưa checkout", dùng site/giờ trong message lỗi trả về thay vì hardcode.
