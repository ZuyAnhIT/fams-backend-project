# Tài liệu bàn giao UI: Web vs App và ẩn/hiện theo vai trò — Face ID

> Cập nhật theo code đang chạy ngày 28/07/2026. Đây **không phải** tài liệu API — chi tiết request/response đã có ở `docs/api/face-id-management-api.md`, tài liệu này chỉ trả lời:
> 1. Tính năng này thuộc **Web** hay **App**?
> 2. Với từng vai trò đăng nhập, phần tử nào **ẩn hẳn**, phần tử nào **hiện nhưng disable**, phần tử nào **hiện đầy đủ**?

## 0. Active liveness — App đã dựng xong màn hình quay theo lệnh

App team đã tích hợp xong theo tài liệu API (báo cáo `18_BAO_CAO_APP_FACE_ID_ACTIVE_LIVENESS_2026-07-28.md`) — mục này giữ lại tóm tắt luồng cho tham chiếu:

1. Gọi `POST .../liveness-challenge?purpose=enroll` (hoặc `purpose=checkin&siteId=...` — **`siteId` giờ bắt buộc cho `checkin`**, xem mục 0.1) nhận về `actions` (ví dụ `["center","turn_left","blink"]`).
2. Hiện từng lệnh theo thứ tự, mỗi bước tự chụp 1 ảnh (đếm ngược ~2s).
3. Gửi đúng N ảnh theo đúng thứ tự tới `POST .../liveness-challenge/{challengeId}/frames`.
4. Nếu `status=failed`: đọc `steps[]`, cho làm lại (challenge mới — mỗi challenge chỉ dùng 1 lần, và giờ có **giới hạn tối đa 5 lần thử/10 phút** mỗi nhân viên, lỗi `429 TOO_MANY_ATTEMPTS`).
5. Nếu `status=passed`: gọi `POST .../enroll/from-challenge?challengeId=...` (đăng ký) hoặc gửi `livenessChallengeId` trong `submitCheckin` (chấm công) — **phải làm trong vòng 2 phút** sau khi challenge `passed`, quá thời gian này server từ chối dù vẫn ghi `status=passed` trong DB (chống dùng lại 1 kết quả cũ ở nơi/lúc khác).

### 0.1 [MỚI] Sửa theo báo cáo P0 của Web + App — App cần cập nhật

- **`purpose=checkin` giờ bắt buộc kèm `siteId`** khi gọi `POST .../liveness-challenge` — nếu App đang gọi thiếu field này sẽ nhận `400`. Dùng đúng `siteId` của site đang chấm công (không được tái dùng challenge của site khác).
- **`available-sites` giờ trả `requireFaceIdCheckin` trong từng `site`** — App nên dùng field này để quyết định mở luồng camera NGAY khi hiện danh sách site, thay vì đợi submit rồi bắt lỗi `422 FACE_ID_REQUIRED` mới mở camera (trải nghiệm mượt hơn).
- **Nhân viên giờ tự xem được Face ID của chính mình** (`GET .../face-id`) — nếu App có workaround/ẩn màn hình do trước đây bị `403`, có thể gỡ bỏ.
- **Rate-limit mới** (5 lần/10 phút) — App nên hiện thông báo rõ ràng khi gặp `429`, không phải lỗi chung chung, và có thể disable nút "Thử lại" tạm thời thay vì cho bấm liên tục.

## 1. Kết luận nhanh: App hay Web?

| Tính năng | Web (Company Portal) | App (nhân viên) |
|---|---|---|
| Ghi nhận đồng ý (consent) | — | ✅ Chỉ App/tài khoản nhân viên tự bấm |
| Đăng ký khuôn mặt (enroll, gửi 3-5 ảnh) | HR có thể hỗ trợ chụp tại kiosk (dùng cùng API, đăng nhập bằng tài khoản của chính họ trên thiết bị dùng chung) | ✅ Luồng chính |
| Xem trạng thái Face ID của bản thân | — | ✅ |
| HR xem trạng thái Face ID toàn công ty (báo cáo) | ✅ | — |
| HR duyệt/từ chối lượt đăng ký (hàng đợi mới) | ✅ | — |
| Thu hồi Face ID | ✅ (HR) hoặc ✅ (nhân viên tự làm) | ✅ (nhân viên tự làm) |
| Chấm công có ảnh Face ID | — | ✅ (App gửi kèm `employeePhotoBase64` khi `submitCheckin`) |
| Cấu hình "bắt buộc Face ID" cho 1 site | ✅ (form tạo/sửa site) | — |

**Lưu ý quan trọng cho App**: bước "ghi nhận đồng ý" (consent) giờ **chỉ chấp nhận chính nhân viên tự bấm** — nếu HR cố gọi API này hộ (ví dụ từ Web) sẽ nhận `403`. Nếu Web hiện nút "đồng ý hộ" ở đâu đó, cần gỡ bỏ — đây là thay đổi có chủ đích, không phải bug.

## 2. Ma trận tổng hợp: Tính năng × Vai trò (Company Portal)

| Tính năng | TENANT_ADMIN / HR_MANAGER | SITE_SUPERVISOR | Nhân viên thường |
|---|---|---|---|
| Xem báo cáo Face ID toàn site được giao | ✅ (toàn tenant với TENANT_ADMIN/HR_MANAGER; SITE_SUPERVISOR chỉ site của mình) | ✅ (chỉ site được giao) | ❌ |
| Xem hàng đợi "chờ duyệt" | ✅ | ✅ (chỉ nhân viên thuộc site được giao) | ❌ |
| Duyệt/từ chối 1 lượt đăng ký | ✅ (`face_id:manage`) | ✅ nếu có `face_id:manage` | ❌ |
| Thu hồi Face ID của người khác | ✅ | ✅ nếu có `face_id:manage` | ❌ (chỉ thu hồi của chính mình) |
| Đồng ý/đăng ký/thu hồi Face ID của chính mình | ✅ | ✅ | ✅ |

## 3. Chi tiết ẩn/hiện và các trạng thái nút cần lưu ý

### 3.1 Màn "Đăng ký Face ID" (App) — cập nhật quan trọng: không còn kích hoạt ngay

- Sau khi gửi 3-5 ảnh, **không** hiện "Đăng ký thành công, đã sẵn sàng chấm công" — giờ phải hiện **"Đã gửi, đang chờ HR duyệt"** (`reviewStatus=pending`). Đây là thay đổi hành vi lớn nhất App cần cập nhật.
- Nếu nhân viên ĐÃ có Face ID `enrolled` từ trước và đang nộp lại (đổi diện mạo...), UI nên nói rõ: "Khuôn mặt hiện tại của bạn vẫn dùng được để chấm công trong lúc chờ duyệt ảnh mới" — tránh nhân viên hoang mang tưởng mất quyền chấm công.
- Nếu `reviewStatus=pending` đã tồn tại (server trả `409` khi thử nộp lại) → disable nút "Gửi ảnh", hiện "Bạn có 1 lượt đăng ký đang chờ duyệt, vui lòng đợi kết quả".
- Nếu `reviewStatus=rejected` → hiện lý do (`rejectionReason`) + nút "Chụp lại".
- Lỗi `400` khi gửi ảnh giờ có nhiều dạng cụ thể hơn trước (fail anti-spoofing, ảnh không cùng 1 người trong lô...) — hiện đúng message backend trả về, không hardcode "ảnh không hợp lệ" chung chung, giúp nhân viên biết cách sửa (chụp trong nơi đủ sáng, không dùng ảnh cũ...).

### 3.2 Màn "Trạng thái Face ID của tôi" (App)

Hiện đầy đủ theo `GET /face-id`:
- `status` (not_enrolled/enrolled/revoked) — trạng thái ĐANG DÙNG để chấm công.
- Nếu `reviewStatus=pending` — badge riêng "Đang chờ duyệt" cạnh trạng thái chính, không thay thế nó.
- Nếu `reviewStatus=rejected` — hiện `rejectionReason` + CTA "Đăng ký lại".

### 3.3 [MỚI] Màn "Hàng đợi duyệt Face ID" (Web, HR/Admin)

- Gọi `GET /api/v1/tenants/{tenantId}/face-id/pending-review` — trả sẵn `employeeId/employeeCode/employeeName`, không cần tra cứu thêm.
- Mỗi dòng cần 2 nút: **Duyệt** (`POST .../approve`) và **Từ chối** (`POST .../reject`, bắt buộc nhập lý do).
- **Quan trọng**: nút Duyệt/Từ chối phải **ẩn hoàn toàn nếu người xem chính là nhân viên đó** (không áp dụng cho Web vì Web luôn là HR, nhưng nếu tài khoản HR cũng có hồ sơ nhân viên riêng và tự nộp đăng ký, họ không được tự duyệt hồ sơ của chính mình — backend đã chặn `403`, FE nên ẩn nút trước để tránh trải nghiệm xấu).
- Danh sách rỗng → "Không có lượt đăng ký nào đang chờ duyệt".
- **[MỚI]** Mỗi dòng nên hiện kèm ảnh preview — gọi `GET .../employees/{employeeId}/face-id/pending-review/photo` (trả JPEG trực tiếp, không phải JSON — dùng làm `src` của `<img>` hoặc tải về blob). Trước bản sửa này HR phải "duyệt mù" (chỉ thấy tên/mã/thời gian, không thấy mặt) — giờ đã có ảnh thật để đối chiếu trước khi bấm Duyệt.

### 3.4 [MỚI] Form tạo/sửa Site — thêm toggle "Bắt buộc Face ID khi chấm công"

- Field `requireFaceIdCheckin` (boolean) trong form tạo/sửa site — nên có tooltip giải thích: "Khi bật, nhân viên bắt buộc phải có ảnh khuôn mặt hợp lệ mới chấm công được tại công trình này".
- Khi bật, nên gợi ý thêm (không bắt buộc UI phải chặn) nhắc HR đảm bảo nhân viên tại site đó đã đăng ký + được duyệt Face ID trước, tránh tình huống cả site không ai chấm công được vì chưa ai enrolled.

### 3.5 Màn chấm công (App) — mã lỗi mới cần xử lý mượt

| errorCode | Hiển thị gợi ý |
|---|---|
| `FACE_ID_REQUIRED` | "Công trình này yêu cầu chụp ảnh khuôn mặt để chấm công" — mở camera ngay, đừng chỉ hiện text lỗi |
| `FACE_ID_NOT_ENROLLED` | "Bạn chưa đăng ký Face ID (hoặc chưa được duyệt) — công trình này yêu cầu bắt buộc" + nút đi tới màn đăng ký |

Nếu App biết trước site có `requireFaceIdCheckin=true` (lấy từ response site trong `available-sites`, xem `docs/api/shift-assignment-management-api.md`), nên **luôn bật sẵn camera / bắt buộc chụp ảnh trước khi cho bấm "Chấm công"** thay vì để người dùng bấm rồi mới nhận lỗi 422.

## 4. Sơ đồ nav đề xuất

```text
COMPANY PORTAL (fams-front-web-project) — TENANT_ADMIN / HR_MANAGER / SITE_SUPERVISOR
└── Nhân sự → Face ID
    ├── Tab "Báo cáo tổng quan" (đã có) — thêm cột reviewStatus/submittedAt
    └── Tab "Chờ duyệt" (MỚI) — danh sách + nút Duyệt/Từ chối
└── Công trình → Chi tiết site → form Sửa → thêm toggle "Bắt buộc Face ID" (MỚI)

MOBILE APP (fams-front-app-project)
└── Màn "Face ID của tôi"
    ├── Trạng thái hiện tại + badge "đang chờ duyệt" nếu có
    └── Nút "Đăng ký" / "Đăng ký lại" / "Thu hồi"
└── Màn chấm công — bắt buộc chụp ảnh nếu site yêu cầu, xử lý 2 mã lỗi mới ở mục 3.5
```

## 5. Checklist bàn giao frontend

- [ ] **Quan trọng nhất**: sau khi gửi ảnh enroll, đổi thông báo từ "đăng ký thành công" sang "đã gửi, chờ HR duyệt" — hành vi backend đã đổi hẳn.
- [ ] Ẩn/gỡ mọi nút "HR đồng ý Face ID hộ nhân viên" trên Web nếu có — backend giờ trả `403`.
- [ ] Dựng màn "Hàng đợi duyệt Face ID" mới cho HR (mục 3.3) — tính năng hoàn toàn mới, chưa có UI trước đây.
- [ ] Thêm toggle "Bắt buộc Face ID" vào form Site (mục 3.4).
- [ ] App: bắt 2 mã lỗi mới khi chấm công (`FACE_ID_REQUIRED`, `FACE_ID_NOT_ENROLLED`) — mục 3.5.
- [ ] App: hiện `rejectionReason` khi `reviewStatus=rejected`, kèm CTA đăng ký lại.
- [ ] Không giả định "gửi ảnh xong là chấm công được ngay" ở bất kỳ đâu trong luồng onboarding nhân viên mới — luôn có độ trễ chờ HR duyệt.
