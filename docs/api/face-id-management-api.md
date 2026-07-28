# Tài liệu tích hợp Face ID — Review, sửa lỗi, bổ sung và API tham chiếu

> Cập nhật theo code đang chạy ngày 28/07/2026. Base path: `/api/v1/tenants/{tenantId}/employees/{employeeId}/face-id` (theo nhân viên) và `/api/v1/tenants/{tenantId}/face-id` (hàng đợi duyệt của HR).

## 0.2 [MỚI] Sửa theo báo cáo Web + App team sau khi tích hợp active liveness

Cả 2 team (Web: `17_BAO_CAO_FACE_ID_UI_2026-07-28.md`, App: `18_BAO_CAO_APP_FACE_ID_ACTIVE_LIVENESS_2026-07-28.md`) tích hợp xong theo tài liệu mục 0.1 và báo lại — đã xác minh từng điểm với code thật, tất cả đều đúng, đã sửa:

| # | Vấn đề | Team báo | Đã sửa |
|---|---|---|---|
| P0-1 | `getStatus()` không có kiểm tra tự xem — nhân viên thường (không có `face_id:manage`/`employees:read`) bị `403` khi xem chính Face ID của mình, dù Controller Javadoc/App đều giả định xem được | App | Thêm check `employee.userId == callerUserId`, cùng pattern với các action khác |
| P0-2 | `available-sites` không trả `requireFaceIdCheckin` trong `SiteInfo` — App phải submit mù, nhận `422` rồi mới biết cần mở camera, thay vì biết trước | App | Thêm field, map từ `site.isRequireFaceIdCheckin()` |
| P0-3 | Challenge check-in: không kiểm tra hết hạn/độ mới lúc tiêu thụ, không gắn với site cụ thể, không atomic (2 request đồng thời có thể cùng tiêu thụ 1 challenge), publish job lỗi thì âm thầm để checkin `valid` | App | 4 sửa riêng — xem mục 0.3 |
| P0-4 | Hàng đợi duyệt (`pending-review`) không có cách xem ảnh — HR "duyệt mù" | Cả 2 team | Endpoint mới `GET .../face-id/pending-review/photo` |
| P0-5 | Có thể bắt đầu/nộp frame challenge `purpose=enroll` trước khi có consent — server đã xử lý ảnh sinh trắc học trước khi xác nhận đồng ý | App | Chặn ngay lúc `POST .../liveness-challenge` nếu chưa consent (purpose=enroll) |
| P1 | Rate-limit số lần thử liveness | App | Tối đa 5 lần/10 phút mỗi nhân viên, lỗi `429 TOO_MANY_ATTEMPTS` |
| P1 | Swagger mô tả `/consent` sai — nói HR gọi hộ được, code (đã sửa từ mục 0.1) chỉ cho tự làm | Web | Cập nhật lại mô tả |

### 0.3 Chi tiết 4 sửa cho challenge check-in (P0-3)

1. **Gắn với site**: `POST .../liveness-challenge` giờ nhận thêm `siteId` (bắt buộc khi `purpose=checkin`) — `submitCheckin` chỉ chấp nhận challenge có đúng `siteId` khớp với site đang chấm công. Migration `V76` thêm cột `liveness_challenges.site_id`.
2. **Kiểm tra độ mới lúc tiêu thụ**: challenge phải hoàn tất (`completedAt`) trong vòng 2 phút trước khi submit checkin — không chỉ dựa vào `status=passed` (có thể "passed" từ rất lâu rồi mới đem ra dùng).
3. **Atomic consume**: đổi từ đọc-rồi-ghi sang 1 câu `UPDATE ... WHERE status='passed'` duy nhất (`LivenessChallengeRepository.consumeIfPassed`) — 2 request dùng cùng `challengeId` gần như đồng thời, chỉ 1 request thắng, request còn lại nhận lỗi rõ ràng thay vì cả 2 cùng "thành công".
4. **Publish job lỗi không còn bị bỏ qua**: nếu không gửi được job xác thực khuôn mặt tới AI worker (lỗi mạng/Redis), checkin tại site bắt buộc Face ID giờ tự chuyển sang `pending_review` thay vì âm thầm giữ `valid` — không phải violation (chưa chứng minh được sai, chỉ là không xác minh được), nhưng HR sẽ thấy để xem lại.

### 0.4 Endpoint mới: xem ảnh đang chờ duyệt

`GET /api/v1/tenants/{tenantId}/employees/{employeeId}/face-id/pending-review/photo` — trả về JPEG của ảnh đại diện cho lượt nộp đang `pending`, yêu cầu quyền `face_id:manage` + đúng site-scope (SITE_SUPERVISOR chỉ xem được nhân viên thuộc site của mình). Nguồn ảnh:
- Nếu đăng ký qua active liveness → ảnh `center` của challenge đã pass.
- Nếu HR enroll trực tiếp (ảnh tĩnh, kiosk) → ảnh đầu tiên trong lô đã tải lên.

Ảnh được xóa (`pending_photo_path = NULL`) ngay khi duyệt hoặc từ chối — không lưu thêm ngoài vòng đời của 1 lượt đang chờ xử lý.

### 0.5 Việc CHƯA làm — ghi nhận nhưng ngoài phạm vi đợt này

Cả 2 báo cáo có thêm loạt đề xuất P1 hợp lý về mặt kỹ thuật/pháp lý nhưng cần quyết định tổ chức hoặc hạ tầng ngoài phạm vi 1 đợt sửa code, **chưa triển khai**:
- Consent lưu thêm `noticeVersion`/policy URL/mục đích/nguồn ghi nhận (hiện chỉ có boolean + timestamp) — cần input về nội dung chính sách thật từ đội pháp lý.
- DPIA/đánh giá tác động, lịch retention chính thức, quy trình yêu cầu truy cập/xoá dữ liệu — quy trình tổ chức, không phải thay đổi code.
- Play Integrity (Android) / App Attest (iOS) chống app giả/camera injection — thuộc phạm vi App, cần build production thật (không chạy được qua Expo Go).
- Đo FMR/FNMR và kiểm thử PAD độc lập theo ISO/IEC 30107-3 trên tập dữ liệu đa dạng thực tế — cần tập dữ liệu và quy trình kiểm thử riêng, môi trường hiện tại chỉ có 1 ảnh mặt thật để test (đã nêu ở mục 0.1).
- Chuyển từ 3 ảnh rời sang phiên video liên tục — thay đổi kiến trúc lớn, cân nhắc sau khi có dữ liệu thực tế về tỷ lệ gian lận với thiết kế hiện tại.
- Phương án chấm công thay thế cho người từ chối/không dùng được sinh trắc học (rate-limit khi thất bại lặp lại đã làm; phương án thủ công/manual review thay thế thì chưa).

## 0.1 [MỚI] Active liveness (quay đầu/nháy mắt theo lệnh) — backend + AI service

Bạn hỏi cơ chế chống giả mạo hiện tại có phải dạng "quay các góc mặt (trực diện/trái/phải/trên/dưới), nháy mắt theo yêu cầu" hay không — câu trả lời trước đó là **chưa**, hệ thống chỉ có "passive liveness" (phân tích 1 ảnh tĩnh). Đã nâng cấp lên **active liveness** đầy đủ theo đúng lựa chọn của bạn, phạm vi backend + AI service trước (chưa đụng vào UI camera của App — xem mục "Việc còn lại cho App" cuối phần này).

### Cơ chế mới

1. **Bắt đầu 1 challenge**: `POST .../face-id/liveness-challenge?purpose=enroll|checkin` → server random 1 chuỗi hành động, luôn bắt đầu bằng `center` (ảnh chính diện, dùng làm ảnh tham chiếu) + 2 hành động ngẫu nhiên trong `{turn_left, turn_right, look_up, look_down, blink}`. Random mỗi lần để không thể quay sẵn 1 video rồi phát lại cho lần sau.
2. **Nộp ảnh theo đúng thứ tự**: `POST .../liveness-challenge/{challengeId}/frames` — client chụp đúng 1 ảnh cho mỗi hành động, gửi theo đúng thứ tự. Server (fams-ai) kiểm tra:
   - **Hướng đầu** (turn/look): ước lượng góc quay đầu bằng `cv2.solvePnP` (thuật toán chuẩn dùng trong nhận diện tư thế khuôn mặt — 6 điểm mốc: mũi, cằm, 2 khóe mắt, 2 khóe miệng, đối chiếu với mô hình mặt 3D chuẩn) — KHÔNG phải phân loại ảnh học máy đen-hộp, mà là hình học đo lường được, dễ giải thích và kiểm chứng.
   - **Nháy mắt**: Eye Aspect Ratio (EAR) — công thức chuẩn trong nghiên cứu phát hiện nháy mắt (Soukupová & Čech, 2016), tỷ lệ khoảng cách các điểm mốc quanh mắt giảm mạnh khi nhắm mắt.
   - **Cùng 1 người xuyên suốt**: so khớp cosine similarity giữa embedding của TẤT CẢ các ảnh trong chuỗi — chặn việc ghép ảnh nháy mắt/quay đầu của người khác vào.
   - **Chống ảnh in/màn hình**: chạy lại MiniFASNet (như trước) trên ảnh `center` — lớp phòng thủ thứ 2, phòng trường hợp kẻ gian dùng mặt nạ 3D tinh vi vẫn "quay đầu" đúng nhưng texture vẫn lộ là giả.
   - Trả `status: passed|failed` kèm chi tiết từng bước (`steps`) — ví dụ ảnh nào không đạt hành động nào, để hiển thị lỗi rõ ràng cho người dùng.
3. **Dùng kết quả đã pass**:
   - Đăng ký: `POST .../face-id/enroll/from-challenge?challengeId=...` (thay cho `POST .../enroll` khi tự đăng ký) — cùng luồng chờ HR duyệt như trước, chỉ khác nguồn ảnh.
   - Chấm công: `SubmitCheckinRequest` có thêm field `livenessChallengeId` — **bắt buộc thay cho `employeePhotoBase64`** tại site có `requireFaceIdCheckin=true`.

### Quyết định thiết kế quan trọng

- **Tự đăng ký (self-service) giờ BẮT BUỘC qua active liveness** — gọi thẳng `POST .../enroll` (ảnh tĩnh) khi là chính chủ sẽ bị chặn `400`. **HR hỗ trợ đăng ký tại kiosk** (có mặt giám sát trực tiếp — bản thân sự có mặt của con người đã là 1 lớp chống gian lận) vẫn được dùng `POST .../enroll` (ảnh tĩnh) như cũ, không bắt buộc active liveness — vì có người giám sát trực tiếp việc chụp.
- **Chấm công tại site KHÔNG bắt buộc Face ID**: giữ nguyên hành vi cũ hoàn toàn (ảnh tĩnh optional qua `employeePhotoBase64`), không có gì đổi — chỉ site có `requireFaceIdCheckin=true` mới bắt buộc `livenessChallengeId`.
- **Không tái sử dụng challenge**: mỗi challenge chỉ dùng được 1 lần (`status` chuyển `consumed` ngay khi tiêu thụ) — chặn việc dùng lại 1 kết quả pass cho nhiều lần đăng ký/chấm công.

### Test đã chạy — và giới hạn thành thật của việc test

- **Test sống qua API thật**: toàn bộ luồng start challenge → nộp ảnh → enroll-from-challenge → HR duyệt → chấm công với `livenessChallengeId` → worker AI khớp đúng embedding → checkin `valid`. Test cả trường hợp **nộp cùng 1 ảnh tĩnh cho 3 hành động khác nhau** — hệ thống đúng đắn từ chối 2/3 bước (chỉ `center` đạt), chứng minh cơ chế chặn được kiểu gian lận "gửi lại đúng 1 ảnh cũ" phổ biến nhất.
- **Đã kiểm chứng toán học của thuật toán đo góc đầu độc lập**: dựng lại phép chiếu 3D→2D với góc quay đã biết trước (0°, ±10°, ±20°, 30°), đưa qua đúng pipeline `solvePnP` + trích Euler angle, xác nhận góc phục hồi khớp chính xác góc đưa vào — đồng thời phát hiện và sửa 1 lỗi số học thật (ambiguity dấu trong `cv2.decomposeProjectionMatrix`, khiến ảnh chính diện ban đầu bị tính sai thành pitch≈-171°/roll≈-180° thay vì gần 0°).
- **Giới hạn**: môi trường test chỉ có 1 ảnh khuôn mặt thật (chân dung chính diện, dùng cho toàn bộ các test khác trong tài liệu này) — **không có ảnh mặt quay các góc thật** để test độc lập từng ngưỡng `turn_left/turn_right/look_up/look_down` qua đúng pipeline nhận diện mốc khuôn mặt của `face_recognition` trên ảnh camera thật. Đã bù bằng cách kiểm chứng toán học thuần túy (mục trên) — nhưng khuyến nghị **đội QA validate lại ngưỡng góc (`YAW_THRESHOLD_DEG=15°`, `PITCH_THRESHOLD_DEG=12°` trong `ai-service/app/services/head_pose_service.py`) bằng ảnh chụp thật từ camera điện thoại thật** trước khi đưa vào dùng chính thức — góc quay đầu "vừa đủ" để qua ngưỡng có thể khác giữa camera trước điện thoại (góc rộng, biến dạng nhiều ở rìa) và ảnh test tĩnh.

### Việc còn lại cho App (fams-front-app-project) — chưa làm trong lượt này

- Màn hình quay: hiện lệnh theo `actions` trả về (ví dụ "Nhìn thẳng vào camera" → "Quay đầu sang trái" → "Nháy mắt"), tự động chụp 1 ảnh khi phát hiện đúng khoảnh khắc (hoặc đơn giản hơn: đếm ngược 2 giây mỗi bước rồi tự chụp) — không cần quay video thật, chỉ cần N ảnh JPEG đúng thứ tự.
- Xử lý `status=failed` từ `.../frames`: hiện đúng bước nào sai (`steps[].passed=false`) để người dùng biết cần làm lại kiểu gì (ví dụ "chưa quay đủ sang trái" thay vì lỗi chung chung).
- Đổi màn hình chấm công tại site `requireFaceIdCheckin=true`: thay chụp 1 ảnh bằng luồng challenge đầy đủ trước khi gọi `submitCheckin`.

## 0. Tóm tắt kết quả

**4 tính năng bạn liệt kê đã được xây dựng từ trước** (ghi nhận đồng ý, đăng ký, HR xem trạng thái, xóa/thu hồi) — nhưng review sâu vào nghiệp vụ (đối chiếu với các hệ thống chấm công sinh trắc học thực tế: ZKTeco/Hikvision, DingTalk/Feishu chấm công khuôn mặt doanh nghiệp, và luật riêng tư Nghị định 13/2023/NĐ-CP) phát hiện **công nghệ nền tảng đã đúng hướng** (embedding 128 chiều qua `face_recognition`/dlib, so khớp bằng cosine similarity — không lưu ảnh phẳng để so khớp trực tiếp như bạn lo ngại) nhưng có **7 lỗ hổng nghiệp vụ thật**, đã sửa cả 7:

| # | Vấn đề | Mức độ | Đã sửa |
|---|---|---|---|
| 1 | Đăng ký (enroll) không chạy anti-spoofing — ảnh in/chụp màn hình vẫn đăng ký được | 🔴 Nghiêm trọng | Mục 2.1 |
| 2 | N ảnh trong 1 lượt đăng ký không được kiểm tra có cùng 1 người | 🔴 Nghiêm trọng | Mục 2.1 |
| 3 | Face verify khi chấm công không bắt buộc, thất bại cũng không ảnh hưởng gì | 🟠 Xung đột logic | Mục 2.2, 2.3 |
| 4 | HR có thể tự "đồng ý" Face ID thay nhân viên | 🟠 Vi phạm nguyên tắc consent | Mục 2.4 |
| 5 | Nghỉ việc không tự thu hồi Face ID — dữ liệu sinh trắc học tồn tại vô thời hạn | 🟠 Rủi ro pháp lý | Mục 2.5 |
| 6 | Báo cáo Face ID cho HR không áp site-scope | 🟡 Thiếu nhất quán | Mục 2.6 |
| 7 | Job dọn dẹp định kỳ (`DataRetentionJob`) gọi 1 API không tồn tại ở fams-ai, lỗi âm thầm mỗi tuần | 🟡 Bug tiềm ẩn | Mục 2.7 |

Và bổ sung theo quyết định của bạn (đã xác nhận qua 3 câu hỏi lựa chọn):
- **Mọi lượt đăng ký/đăng ký lại đều phải qua HR duyệt** (không tự động kích hoạt) — mục 1.
- **Site có thể cấu hình bắt buộc Face ID khi chấm công** (`requireFaceIdCheckin`) — mục 3.

**Kết quả test**: build lại cả `fams-api` (Java) và `fams-ai` (Python/AI), test sống qua API thật trên dữ liệu seed (tenant `beta-industries`) cho: consent bị HR chặn/nhân viên tự làm được, site bắt buộc Face ID chặn thiếu ảnh (`FACE_ID_REQUIRED`), chặn nhân viên chưa enroll (`FACE_ID_NOT_ENROLLED`). Chạy lại `tests/site/*.sh` (189 test) — 100% pass, không hồi quy. `tests/face-id/*.sh` và `tests/face_id/*.sh` (9 file) bị chặn bởi lỗi kịch bản test có từ trước (tạo tenant thiếu `ownerEmail` — API đã đổi từ lâu, kịch bản test chưa cập nhật), không liên quan tới thay đổi lần này.

## 1. Kiến trúc mới: mọi đăng ký đều qua hàng đợi duyệt của HR

### 1.1 Vì sao — tham khảo thực tế

Máy chấm công sinh trắc học chuyên dụng (ZKTeco, Hikvision — phổ biến ở VN) luôn để **HR/quản lý enroll tại thiết bị**, nhân viên không tự enroll qua app cá nhân, chính vì sợ gian lận (đúng nỗi lo bạn nêu ban đầu). Các app hiện đại hơn cho tự chụp qua điện thoại (DingTalk/Feishu chấm công khuôn mặt) đều **yêu cầu HR duyệt ảnh trước khi kích hoạt**. Hệ thống của bạn giờ theo đúng mô hình thứ hai: tự chụp qua app, nhưng không tự động có hiệu lực.

### 1.2 Cỗ máy trạng thái

```
not_enrolled ──consent──▶ not_enrolled (đã đồng ý) ──enroll──▶ reviewStatus=pending
                                                                     │
                                            approve ◀────────────────┴───────────────▶ reject
                                               │                                          │
                                               ▼                                          ▼
                                           enrolled                          (status GIỮ NGUYÊN,
                                               │                              reviewStatus=rejected)
                                        re-enroll (bất kỳ lúc nào)
                                               │
                                               ▼
                                    reviewStatus=pending, status VẪN LÀ enrolled
                                    (khuôn mặt cũ vẫn dùng chấm công được trong lúc chờ duyệt)
```

Điểm mấu chốt: **`status`** (not_enrolled|enrolled|revoked) luôn phản ánh trạng thái **đã được duyệt gần nhất** — đây là cái quyết định có chấm công được hay không. **`reviewStatus`** (none|pending|rejected) theo dõi lượt nộp đang chờ xử lý, **độc lập với `status`**. Nhờ vậy, khi một nhân viên đã `enrolled` nộp ảnh đăng ký lại (đổi diện mạo, thiết bị mới...), họ **không bị gián đoạn khả năng chấm công** trong lúc chờ HR duyệt — khuôn mặt cũ vẫn dùng được cho tới khi (nếu) HR duyệt ảnh mới.

### 1.3 API

**`POST /face-id/consent`** — CHỈ nhân viên tự gọi được (xem mục 2.4), không đổi request/response so với trước.

**`POST /face-id/enroll`** (multipart, 3-5 ảnh) — KHÔNG còn kích hoạt ngay. Mỗi ảnh phải:
1. Pass anti-spoofing (MiniFASNet qua DeepFace) — ảnh in/chụp màn hình bị từ chối ngay `400`.
2. Detect được đúng 1 khuôn mặt.
3. Cùng 1 người với các ảnh còn lại trong lô (cosine similarity ≥ ngưỡng `AI_ENROLL_SAME_PERSON_THRESHOLD`, mặc định 0.45).

Qua hết → `reviewStatus=pending`, `status` giữ nguyên. Response mẫu:
```json
{ "status": "not_enrolled", "consentGiven": true, "reviewStatus": "pending",
  "pendingPhotoCount": 4, "submittedAt": "2026-07-28T10:00:00Z" }
```
Nếu đang có 1 lượt `pending` chưa xử lý → `409`, phải chờ HR quyết định trước khi nộp lô mới.

**`POST /face-id/approve`** (mới) — HR/Admin duyệt, **không tự duyệt được cho chính mình** (khác hẳn pattern "self OR face_id:manage" dùng ở các action khác — duyệt hồ sơ của chính mình triệt tiêu hoàn toàn ý nghĩa bước duyệt). Promote `pending_embedding` → `embedding`, `status=enrolled`.

**`POST /face-id/reject`** (mới, body `{"reason": "..."}`) — HR/Admin từ chối kèm lý do. Nếu đây là lượt đăng ký lại, khuôn mặt đã duyệt trước đó (nếu có) không đổi.

**`GET /api/v1/tenants/{tenantId}/face-id/pending-review`** (mới, tenant-level, không theo employeeId) — hàng đợi duyệt cho HR, tự động lọc theo site-scope của người gọi (SITE_SUPERVISOR chỉ thấy nhân viên thuộc site được giao). Trả về danh sách kèm `employeeId/employeeCode/employeeName` để HR không cần tra cứu thêm.

## 2. Chi tiết các lỗi đã sửa

### 2.1 [Đã sửa — nghiêm trọng] Enroll không chống giả mạo

`ai-service/app/routers/enroll.py` trước đây chỉ gọi `extract_embedding()` (detect + encode khuôn mặt) — hàm `check_liveness()` (MiniFASNet anti-spoofing) đã tồn tại sẵn trong codebase và được dùng cho checkin, nhưng **không hề được gọi ở bước enroll**. Ai đó đăng ký bằng ảnh in/ảnh chụp lại màn hình vẫn qua được, và từ đó **mọi lần chấm công sau đều "khớp"** với khuôn mặt giả đó — lỗ hổng đúng như bạn dự đoán ban đầu.

Đã sửa: mỗi ảnh trong lô enroll giờ bắt buộc pass `check_liveness()` trước khi được chấp nhận; đồng thời thêm kiểm tra chéo N ảnh phải cùng 1 người (cosine similarity giữa từng cặp ảnh ≥ ngưỡng riêng, thấp hơn ngưỡng verify vì ảnh enroll cố ý chụp nhiều góc).

### 2.2 [Đã sửa — xung đột logic] Face verify khi chấm công không có hậu quả gì

Trước đây: ảnh chấm công là optional, xử lý async "bắn rồi quên" — dù AI trả về thất bại, bản ghi checkin vẫn giữ `status=valid`. So sánh: tính năng **random-check (kiểm tra đột xuất)** trong CÙNG hệ thống đã xử lý đúng từ trước — khi face fail thì hạ `outcome=fail` + tạo **violation**. Đây là 2 tính năng xử lý cùng loại sự kiện (face verify fail) nhưng khác nhau hoàn toàn.

Đã sửa (mục 2.3 + 3): `FaceResultCallbackController` giờ áp đúng logic của random-check cho checkin thường — NHƯNG chỉ khi site yêu cầu Face ID (`requireFaceIdCheckin=true`), để không phạt những trường hợp ảnh chỉ là optional/best-effort.

### 2.3 [Đã sửa] Escalate + tạo violation khi face verify thất bại tại site bắt buộc Face ID

Migration `V74` thêm cột `checkin_id` (nullable) vào bảng `violations` — trước đây bảng này chỉ liên kết được với `scheduled_check_id`/`check_response_id` (random-check), không có đường nối tới `checkins` thường.

`FaceResultCallbackController` (nhánh `sourceType=checkin`): khi AI trả `faceVerified=false` VÀ site của lượt chấm công đó có `requireFaceIdCheckin=true` → hạ `checkin.status` từ `valid` xuống `pending_review` (nếu chưa ở trạng thái đó) + tạo 1 `Violation` (`violation_type = face_fail` hoặc `liveness_fail` tùy nguyên nhân, `checkin_id` trỏ về bản ghi checkin). HR thấy ngay trong danh sách vi phạm, giống hệt cách random-check đã làm.

### 2.4 [Đã sửa — vi phạm nguyên tắc consent] HR "đồng ý" thay nhân viên

`POST /consent` trước đây cho phép người có quyền `face_id:manage` gọi hộ nhân viên khác. Theo Nghị định 13/2023/NĐ-CP (dữ liệu sinh trắc học là dữ liệu cá nhân nhạy cảm, Điều 11) và GDPR Art.9, đồng ý xử lý dữ liệu sinh trắc học **phải do chính chủ thể dữ liệu thực hiện** — HR không được "đồng ý hộ", kể cả khi có quyền quản lý.

Đã sửa: `giveConsent()` giờ **chỉ chấp nhận chính nhân viên** (so khớp `employee.userId == callerUserId`), kể cả platform admin cũng không được gọi hộ. `enrollFace()`/`revokeFace()` vẫn giữ pattern "self OR face_id:manage" như cũ (HR hỗ trợ chụp ảnh tại kiosk sau khi nhân viên đã tự đồng ý qua tài khoản của họ — vẫn hợp lệ và phổ biến trong thực tế).

### 2.5 [Đã sửa — rủi ro pháp lý] Nghỉ việc không tự thu hồi Face ID

`EmployeeService.changeEmployeeStatus` trước đây không đụng tới `face_profiles`. Dữ liệu sinh trắc học (ảnh + embedding) của người đã nghỉ việc tồn tại vô thời hạn dù mục đích xử lý đã kết thúc.

Đã sửa: khi chuyển trạng thái nhân viên sang `terminated`, tự động gọi thu hồi Face ID (xóa ảnh gốc + embedding qua `fams-ai`, giống hệt luồng nhân viên tự thu hồi). **Chỉ áp dụng cho `terminated`, không áp dụng cho `inactive`** (tạm nghỉ/nghỉ phép — có thể quay lại, không nên bắt đăng ký lại sinh trắc học).

### 2.6 [Đã sửa] Báo cáo Face ID không áp site-scope

`ReportService.getFaceIdEnrollmentReport` trước đây nạp TOÀN BỘ nhân viên trong tenant, không lọc theo site được phép của người gọi — khác với mọi tính năng khác trong hệ thống (checkins, employees, assignments đều đã áp site-scope nhất quán). Một SITE_SUPERVISOR bị giới hạn 1 site nhưng có quyền `reports:list` vẫn xem được Face ID của toàn công ty.

Đã sửa: áp `SiteScopeService.resolveAllowedSiteIds` giống các báo cáo khác, lọc nhân viên qua liên kết assignment→site (Employee không có cột site trực tiếp). Đồng thời bổ sung `reviewStatus`/`submittedAt`/`rejectionReason` vào từng dòng báo cáo, và `statusFilter=pending` giờ lọc theo `reviewStatus` (trước đây filter theo `status='pending'` không còn ý nghĩa vì `pending` đã chuyển hẳn sang `reviewStatus`).

### 2.7 [Đã sửa — bug tiềm ẩn] Job dọn embedding định kỳ gọi API không tồn tại

Phát hiện khi review: `DataRetentionJob` (chạy mỗi Chủ Nhật 3h sáng) gọi `AiServiceClient.deleteEmbedding()` → `DELETE /embeddings/{profileId}` ở `fams-ai` — nhưng route này **chưa từng được implement** (chỉ có biến gọi, không có handler, không đăng ký trong `main.py`). Mỗi tuần job này âm thầm nhận lỗi 404, log error, và cờ `embedding_deleted` không bao giờ được set — chạy vô ích mỗi tuần, mãi mãi.

*Đính chính*: ban đầu tôi tưởng cột `embedding_deleted` là dead code (không có nơi nào set) — đã đọc nhầm, thực ra nó có được set bởi `DataRetentionJob`, chỉ là job đó luôn thất bại vì thiếu route ở phía Python. Rất may phát hiện trước khi đề xuất xóa nhầm.

Đã sửa: implement `DELETE /embeddings/{profile_id}` (router mới `embeddings.py`) — xóa embedding + set `embedding_deleted=true` theo đúng `face_profiles.id`. Đồng thời revoke tương tác (qua `DELETE /enroll/{employee_id}`) giờ cũng set `embedding_deleted=true` ngay lập tức (đồng bộ), nên job định kỳ giờ chỉ còn là lưới an toàn cho trường hợp lệnh revoke tương tác bị lỗi mạng giữa chừng.

## 3. Tính năng mới: Face ID bắt buộc theo site

`Site.requireFaceIdCheckin` (boolean, mặc định `false`) — cấu hình được khi tạo/sửa site (`POST`/`PUT /sites`, field `requireFaceIdCheckin`). Khi bật:

- `submitCheckin` từ chối ngay (`422 FACE_ID_REQUIRED`) nếu thiếu ảnh — không mất công gửi job async cho một lượt chắc chắn không đạt.
- Từ chối ngay (`422 FACE_ID_NOT_ENROLLED`) nếu nhân viên chưa có Face ID `enrolled` — hướng dẫn rõ đi đăng ký trước, thay vì để chấm công "trôi qua" không xác thực.
- `requiresLiveness` bị ép `true` bất kể client gửi gì — tránh App tự ý tắt anti-spoofing ở nơi chính sách yêu cầu bật.
- Nếu ảnh gửi lên sau đó xác thực THẤT BẠI (async) → escalate + tạo violation như mục 2.3.

Vì sao theo site chứ không theo tenant (khớp lựa chọn "Recommended" bạn đã chọn): một công ty xây dựng có thể có công trường an ninh cao (kho vật tư, khu vực hạn chế) cần Face ID bắt buộc, và văn phòng thường không cần — linh hoạt theo nhu cầu thực tế, giống Connecteam/Deputy cho phép cấu hình chính sách theo từng địa điểm riêng.

## 4. Mã lỗi mới cần FE xử lý

| HTTP | errorCode | Khi nào |
|---|---|---|
| 403 | (message: "consent must be given by the employee themselves") | HR/Admin cố gọi `/consent` hộ nhân viên khác |
| 409 | (message: "previous submission still pending review") | Nộp enroll mới khi đang có lượt `pending` chưa xử lý |
| 422 | `FACE_ID_REQUIRED` | Site bắt buộc Face ID nhưng chấm công thiếu ảnh |
| 422 | `FACE_ID_NOT_ENROLLED` | Site bắt buộc Face ID nhưng nhân viên chưa `enrolled` |
| 400 | (enroll — nhiều dạng message) | Ảnh fail anti-spoofing, sai số lượng, nhiều/không có khuôn mặt, ảnh không cùng 1 người trong lô |
| 404 | (approve/reject) | Không có lượt đăng ký nào đang `pending` cho nhân viên đó |

## 5. Xác nhận thêm — không cần sửa

- **Không lưu ảnh phẳng để so khớp** — nền tảng công nghệ (embedding 128 chiều qua `face_recognition`/dlib ResNet-34, so khớp bằng cosine similarity) đã đúng chuẩn ngành từ trước, không phải sửa.
- **Thu hồi (revoke) không cần duyệt, luôn thực hiện ngay** — dù do chính nhân viên hay do HR — đây là quyền hợp pháp (rút consent) không thể trì hoãn bằng một bước xét duyệt.
- **`pending`/`enrolled`/`revoked` là 3 trạng thái độc lập với `reviewStatus`** — tránh nhầm giữa "trạng thái đang dùng để chấm công" và "trạng thái của lượt nộp gần nhất".
