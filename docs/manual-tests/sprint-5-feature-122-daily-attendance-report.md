# Kịch bản test thủ công — #122 Báo cáo công ngày

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): không nằm trong 14 issue gốc — audit code hiện tại (2026-08-18) phát hiện
gap khi rà toàn bộ module Report (#122-#125): AC của cả 4 report/export endpoint đều gọi filter
theo workspace/phòng ban, nhưng `ReportService` chưa từng nhận `workspaceId` ở bất kỳ endpoint
nào, dù module `Workspace`/`WorkspaceMember` đã tồn tại đầy đủ trong codebase để hỗ trợ việc này.
Đã hỏi người dùng có nên làm ngay cả 4 endpoint hay hoãn — **người dùng chọn làm ngay cả 4**.

## ✅ ĐÃ VÁ (2026-08-18)

- `GET /reports/attendance/daily` nhận thêm `workspaceId` (optional).
- `AttendanceSummary` không có cột `workspaceId` trực tiếp (quan hệ qua `WorkspaceMember`) → resolve
  tập `employeeId` thuộc workspace trước
  (`WorkspaceMemberRepository.findDistinctEmployeeIdsByTenantIdAndWorkspaceId`), rồi lọc qua
  `AttendanceSummarySpecification` (overload mới nhận `Collection<UUID> employeeIds`) — cùng pattern
  đã dùng ở #120 cho filter site trên Employee.
- Workspace tồn tại nhưng **0 thành viên** → trả kết quả rỗng (không fallback về "không lọc") —
  phân biệt rõ `null` (không lọc) và tập rỗng (lọc nhưng không khớp ai).
- Web Admin: thêm dropdown "Lọc báo cáo ngày theo workspace" cạnh dropdown site sẵn có, dùng lại
  `useWorkspacesQuery` (pattern đã có ở trang danh sách nhân viên).

---

## A. Test trên Backend

### 1. ✅ `GET /reports/attendance/daily` không kèm `workspaceId` — không đổi hành vi cũ
- **Kỳ vọng:** giống hệt trước khi vá.

### 2. ✅✅ (Case quan trọng nhất) `workspaceId=X` chỉ tính nhân viên thuộc workspace X
- Setup: 2 nhân viên cùng site, cùng ngày điểm danh `present`; chỉ 1 người là thành viên workspace X.
- **Kỳ vọng:** không filter → `totalPresent=2`; filter `workspaceId=X` → `totalPresent=1`, chỉ
  chứa đúng nhân viên đó trong `records`. **Xác nhận đúng qua live API call** (script verify
  `/tmp/verify_121_125.sh`): không filter → 2, có filter → 1 (đúng employeeId).

### 3. ✅ Workspace hợp lệ nhưng 0 thành viên → trả rỗng, không rơi về "không lọc"
- **Kỳ vọng:** `totalPresent=0`, `records` rỗng — không lộ dữ liệu ngoài phạm vi workspace.

## B. Test trên Web Admin

### 4. ✅✅ ĐÃ TEST LIVE qua Playwright thật — dropdown workspace mới trên trang báo cáo công ngày
- Setup thật: 2 nhân viên cùng site cùng có bản ghi điểm danh hôm nay, 1 người thuộc workspace,
  1 người không.
- Mở tab Công ngày (không filter): stat "Có mặt" = 2, bảng 2 dòng.
- Chọn workspace trong dropdown mới → stat "Có mặt" giảm đúng còn 1, bảng còn 1 dòng (đúng nhân
  viên thuộc workspace). Xác nhận dropdown kích hoạt fetch lọc thật qua re-render UI, không phải
  chỉ hiển thị tĩnh.

---

## Ghi chú
`tsc --noEmit` phía Web Admin sạch. Backend regression (`tests/report/*.sh`) chạy lại cùng đợt
#121-125, không phát hiện regression.
