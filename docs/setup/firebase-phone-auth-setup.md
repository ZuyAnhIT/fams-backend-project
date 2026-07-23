# Hướng dẫn hoàn thiện Firebase Phone Auth cho Feature #2

Hướng dẫn đầy đủ để bạn tự hoàn tất phần thiết lập bên ngoài (Firebase Console + EAS build) mà
tôi không thể tự làm thay. Làm theo đúng thứ tự A → B → C bên dưới.

---

## A. Firebase Console — tạo/hoàn thiện dự án

### A1. Tạo project (bỏ qua nếu đã có)
1. Vào **[console.firebase.google.com](https://console.firebase.google.com)** → **Add project**.
2. Đặt tên (vd `fams-production`) → tắt Google Analytics nếu không cần → **Create project**.

### A2. Bật Phone Authentication
1. **Build → Authentication → Get started** → tab **Sign-in method** → chọn **Phone** → **Enable** → **Save**.

### A3. Thêm số điện thoại test (rất khuyến nghị — làm bước này trước khi test bất cứ gì)
1. Vẫn trong **Authentication → Settings** → tab **Phone numbers for testing**.
2. Bấm **Add phone number** → nhập số giả, vd `+84123456789`, mã OTP cố định vd `123456` → **Add**.
3. Lợi ích: test không tốn hạn mức SMS thật (free tier chỉ 10 SMS/ngày), không cần đợi tin nhắn,
   và **không cần cấu hình SHA-1/Play Integrity cho Android** (mục A6) để bắt đầu test được ngay.

### A4. Lấy config cho Backend (Firebase Admin SDK)

Trước tiên, mở **Project settings**: trong Firebase Console, ở góc trên bên trái, ngay cạnh tên
project (dưới logo Firebase), có 1 icon **bánh răng ⚙️** kèm chữ "Project Overview" — bấm vào
icon bánh răng đó → menu xổ xuống hiện "**Project settings**" (mục đầu tiên) → bấm vào.

Trang Project settings có các tab nằm ngang phía trên: **General | Cloud Messaging | Integrations
| Service accounts | ...**

1. Bấm tab **General** (tab đầu tiên, thường mở sẵn) → ngay bên dưới tên project, có dòng
   **"Project ID"** dạng `fams-xxxxx` → **copy/ghi lại giá trị này**.
2. Bấm tab **Service accounts**.
3. Trang này hiện đoạn code mẫu (Node.js) và bên dưới có nút **"Generate new private key"** →
   bấm vào.
4. Hộp thoại xác nhận hiện ra cảnh báo giữ bí mật key → bấm **"Generate key"**.
5. Trình duyệt tự tải về 1 file `.json` (tên dạng `fams-xxxxx-firebase-adminsdk-xxxxx.json`) —
   đây chính là file cần cho mục B1.

### A5. Lấy config cho Web Admin (Firebase Web SDK)

1. Vẫn ở **Project settings → tab General**, kéo xuống phần **"Your apps"** (nếu chưa có app
   nào, sẽ thấy 1 hàng icon nền tảng: iOS / Android / Web `</>` / Unity ngay dưới chữ "Your apps").
2. Bấm icon **`</>`** (biểu tượng Web).
3. Hộp thoại "Add Firebase to your web app" hiện ra:
   - Ô "App nickname": đặt tên bất kỳ, vd `FAMS Web Admin`.
   - Không tích "Also set up Firebase Hosting".
   - Bấm **"Register app"**.
4. Màn tiếp theo hiện khối code:
   ```js
   const firebaseConfig = {
     apiKey: "...",
     authDomain: "...",
     projectId: "...",
     storageBucket: "...",
     messagingSenderId: "...",
     appId: "..."
   };
   ```
   **Copy/ghi lại 4 giá trị**: `apiKey`, `authDomain`, `projectId`, `appId` (dùng cho mục B2).
5. Bấm **"Continue to console"** để đóng hộp thoại (bỏ qua các bước cài SDK/thêm code — không
   cần vì Web Admin đã tự cấu hình sẵn).
6. *(Nếu lỡ đóng mà chưa kịp copy: quay lại **Project settings → General → Your apps**, bấm vào
   app Web vừa tạo → phần "SDK setup and configuration" → chọn radio **"Config"** → hiện lại y hệt.)*
7. Kiểm tra thêm: **Authentication → Settings (tab) → Authorized domains** → xác nhận `localhost`
   đã có sẵn trong danh sách (Firebase tự thêm mặc định).

### A6. Lấy config cho Mobile App

1. Vẫn ở **Project settings → tab General → phần "Your apps"** → bấm **"Add app"** (nút có dấu
   `+`, hoặc icon nền tảng nếu đây là app đầu tiên) → chọn icon **Android**.
2. Hộp thoại "Add Firebase to your Android app":
   - Ô **"Android package name"**: gõ chính xác `com.fams.mobile` (phân biệt hoa/thường, phải
     khớp y hệt với `app.json` của mobile app).
   - Ô "App nickname" (tuỳ chọn): vd `FAMS Mobile Android`.
   - Ô "Debug signing certificate SHA-1": **để trống, bỏ qua** — chỉ cần điền nếu sau này muốn
     dùng số điện thoại thật thay vì số test (không bắt buộc để bắt đầu test).
   - Bấm **"Register app"**.
3. Màn tiếp theo "Download config file" → bấm nút **"Download google-services.json"** → file tải
   về chính là file cần cho mục B3.
4. Các bước sau đó ("Add Firebase SDK", "Add initialization code") → bấm **"Next"** để bỏ qua hết
   (không cần làm thủ công vì Expo config plugin `@react-native-firebase/app` đã tự lo phần này)
   → cuối cùng bấm **"Continue to console"**.
5. *(Nếu cần build iOS sau này: lặp lại bước 1 nhưng chọn icon iOS, "Bundle ID" cũng điền
   `com.fams.mobile`, tải file `GoogleService-Info.plist` tương tự.)*

---

## B. Đưa config vào từng dự án

⚠️ **Lưu ý bảo mật:** file service account JSON (bước A4) chứa private key thật — **bạn tự đặt
file/sửa `.env` trực tiếp trên máy**, không cần dán nội dung này vào chat. Web config (bước A5)
là config phía client, vốn dĩ công khai trong bundle JS — dán trực tiếp vào `.env.local` cũng an
toàn.

**Tìm file vừa tải ở đâu?** Nếu bạn tải bằng trình duyệt chạy trên **Windows** (còn máy chạy dự
án là WSL) thì file nằm ở `/mnt/c/Users/<TênWindows>/Downloads/`. Nếu trình duyệt chạy thẳng
trong Linux/WSL thì thường ở `~/Downloads/`. Chạy `ls -t /mnt/c/Users/*/Downloads/*.json` hoặc
`ls -t ~/Downloads/*.json` để tìm file JSON vừa tải (file mới nhất nằm trên cùng).

### B1. Backend — `fams-backend-project/.env`

File service account JSON (bước A4) đã **có sẵn `project_id` bên trong nó** — không cần gõ tay,
dùng lệnh dưới đây để tự trích xuất + rút gọn thành 1 dòng + ghi thẳng vào `.env` (thay đúng
đường dẫn file JSON của bạn ở dòng đầu):

```bash
SERVICE_ACCOUNT_FILE="/mnt/c/Users/<TênWindows>/Downloads/fams-xxxxx-firebase-adminsdk-xxxxx.json"   # ← sửa đường dẫn đúng file bạn vừa tải

cd /home/duyanh/Projects/FAMS/fams-backend-project
PROJECT_ID=$(jq -r .project_id "$SERVICE_ACCOUNT_FILE")
JSON_ONE_LINE=$(jq -c . "$SERVICE_ACCOUNT_FILE")

printf "\n# ── Firebase Admin SDK (Feature #2 — Phone OTP login) ────────────\nFCM_PROJECT_ID=%s\nFCM_SERVICE_ACCOUNT_JSON=%s\n" "$PROJECT_ID" "$JSON_ONE_LINE" >> .env
```

Kiểm tra đã ghi đúng chưa (lệnh này sẽ in ra private key thật trên màn hình — chỉ chạy trên máy
của bạn, đừng copy/dán kết quả này ra ngoài):
```bash
grep FCM_ .env
```
Nếu thấy 2 dòng `FCM_PROJECT_ID=fams-xxxxx` và `FCM_SERVICE_ACCOUNT_JSON={"type":"service_account"...}`
là đúng. Xong bước này, báo tôi — tôi sẽ chạy lại container backend để áp dụng (`.env` chỉ được
đọc lại khi container khởi động lại, `docker restart` không tự đọc lại `.env` nên tôi sẽ dùng
`docker compose ... up -d` thay vì restart thường).

### B2. Web Admin — `fams-front-web-project/.env.local`

File này đã có sẵn khung 4 dòng trống. Mở file bằng editor bất kỳ (VD `code
fams-front-web-project/.env.local`) và điền giá trị đã copy ở bước A5 vào đúng sau dấu `=`,
tương ứng từng dòng:

| Trong file `.env.local` | Điền giá trị nào từ `firebaseConfig` |
|---|---|
| `NEXT_PUBLIC_FIREBASE_API_KEY=` | `apiKey` |
| `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=` | `authDomain` |
| `NEXT_PUBLIC_FIREBASE_PROJECT_ID=` | `projectId` |
| `NEXT_PUBLIC_FIREBASE_APP_ID=` | `appId` |

Không cần dấu ngoặc kép, dán thẳng giá trị sau dấu `=`, mỗi dòng 1 giá trị. Ví dụ sau khi điền:
```
NEXT_PUBLIC_FIREBASE_API_KEY=AIzaSyD-abc123...
NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=fams-xxxxx.firebaseapp.com
NEXT_PUBLIC_FIREBASE_PROJECT_ID=fams-xxxxx
NEXT_PUBLIC_FIREBASE_APP_ID=1:123456789:web:abcdef123456
```
Lưu file lại — Next.js dev server tự đọc lại `.env.local` ở request tiếp theo, không cần restart
(nếu vẫn báo lỗi cũ thì báo tôi để restart `next dev` cho chắc).

### B3. Mobile App — `fams-front-app-project/google-services.json`

Copy thẳng file vừa tải (bước A6) vào **đúng thư mục gốc** của mobile app, đặt tên chính xác
`google-services.json` (không được đổi tên khác):

```bash
cp "/mnt/c/Users/<TênWindows>/Downloads/google-services.json" \
   /home/duyanh/Projects/FAMS/fams-front-app-project/google-services.json
```

Kiểm tra lại: `ls /home/duyanh/Projects/FAMS/fams-front-app-project/google-services.json` phải
ra đúng đường dẫn (không báo "No such file"). File này đã nằm trong `.gitignore` sẵn, không lo
commit nhầm lên git. Nếu sau này build iOS, copy thêm `GoogleService-Info.plist` vào **cùng thư
mục gốc** này theo cách tương tự.

⚠️ **Quan trọng:** phải làm xong bước B3 này **trước khi chạy lệnh build ở mục C** — file
`google-services.json` phải có mặt sẵn thì EAS mới đóng gói đúng vào bản build.

---

## C. Build EAS dev-client cho Mobile App

`@react-native-firebase/auth` cần native module thật — không chạy được qua Expo Go hay Expo Web.
Phải build 1 bản "dev-client" cài lên điện thoại/simulator. Dự án đã có sẵn cấu hình
`eas.json` (profile `development`), chỉ cần làm các bước sau:

### C1. Cài đặt & đăng nhập EAS CLI (1 lần duy nhất)

Kiểm tra đã có `eas-cli` chưa:
```bash
eas --version
```
Nếu báo "command not found", cài mới:
```bash
npm install -g eas-cli
```
Đăng nhập (cần tài khoản Expo — nếu chưa có, tạo miễn phí tại
[expo.dev/signup](https://expo.dev/signup) trước, chỉ cần email + mật khẩu):
```bash
eas login
```
Lệnh sẽ hỏi **Email or username** rồi **Password** ngay trên terminal. Kiểm tra đăng nhập thành
công:
```bash
eas whoami
```
phải in ra đúng username bạn vừa đăng nhập (không phải "Not logged in").

### C2. Chạy build

**Bắt buộc đã xong bước B3 (`google-services.json` đã có trong thư mục gốc mobile app) trước
khi chạy lệnh này.**

```bash
cd /home/duyanh/Projects/FAMS/fams-front-app-project
eas build --profile development --platform android
```
Lần đầu chạy, EAS có thể hỏi thêm vài câu (vd có muốn tự tạo Android keystore không — chọn
**Yes/Generate new keystore**, để EAS tự quản lý). Sau đó build sẽ đẩy lên cloud của Expo.

Nếu test trên iPhone/simulator thay vì Android:
```bash
eas build --profile development-simulator --platform ios    # cho iOS Simulator, KHÔNG cần tài khoản Apple Developer trả phí
# hoặc
eas build --profile development --platform ios               # cho iPhone thật, CẦN tài khoản Apple Developer ($99/năm) — EAS sẽ hướng dẫn thêm UDID thiết bị khi chạy lệnh
```

Build chạy trên cloud (~10-20 phút) — terminal hiện tiến trình trực tiếp, đồng thời có 1 link
dạng `https://expo.dev/accounts/.../builds/...` để theo dõi/tải về sau.

### C3. Cài đặt lên thiết bị

- **Android (điện thoại thật):** build xong, terminal + trang expo.dev hiện 1 **mã QR** — mở
  Camera trên điện thoại Android quét mã đó, chọn tải file `.apk` về rồi cài trực tiếp (Android
  có thể hỏi "Cho phép cài từ nguồn không xác định" → đồng ý).
- **Android (emulator trên máy):** tải file `.apk` về máy tính rồi chạy
  `adb install duong-dan-file.apk` (cần emulator đang mở sẵn).
- **iOS Simulator:** cách nhanh nhất — chạy thẳng `eas build:run --platform ios` sau khi build
  xong, lệnh này tự tải và cài vào simulator đang mở giúp bạn.
- **iPhone thật:** cần đăng ký UDID thiết bị vào Apple Developer Program — EAS tự hỏi và hướng
  dẫn ngay trong lúc chạy lệnh build ở bước C2 nếu thiết bị chưa được đăng ký.

### C4. Chạy Metro bundler đúng chế độ dev-client

Sau khi cài xong, **không dùng `npx expo start` (chế độ Expo Go) như trước nữa** — phải chạy:
```bash
cd /home/duyanh/Projects/FAMS/fams-front-app-project
npx expo start --dev-client
```
Mở app **"FAMS"** vừa cài (icon riêng, khác với app Expo Go) trên điện thoại/simulator — app sẽ
tự hiện màn hình quét QR hoặc tự kết nối luôn nếu điện thoại và máy tính cùng mạng Wi-Fi. Từ giờ
về sau, mỗi lần muốn chạy app chỉ cần lệnh `npx expo start --dev-client` này (không cần build lại
qua EAS nữa, trừ khi thêm package native mới).

---

## Sau khi hoàn tất

Báo tôi biết bạn đã xong bước nào (B1/B2/B3/C) — tôi sẽ:
1. Restart backend để đọc `FCM_PROJECT_ID`/`FCM_SERVICE_ACCOUNT_JSON` mới.
2. Test lại bằng Playwright cho Web Admin (`/login/phone`, đăng ký bằng SĐT) với số điện thoại
   test đã tạo ở bước A3.
3. Với Mobile App, do dev-client chạy trên thiết bị/simulator thật (ngoài tầm với công cụ tự động
   của tôi), tôi sẽ hướng dẫn bạn tự test theo kịch bản ở
   `docs/manual-tests/sprint-1-feature-02-phone-otp-login.md` mục B, và bạn phản hồi kết quả lại.
