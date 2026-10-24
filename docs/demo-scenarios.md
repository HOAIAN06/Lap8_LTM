# Kịch bản demo

1. Thêm SP011 ở Client A và kiểm tra hai database.
2. Xuất 5 SP001, kiểm tra tồn kho hai server.
3. Mở thao tác sửa SP003 ở Client A để giữ khóa, rồi thử sửa SP003 từ Client B.
4. Sửa SP001 và SP002 song song.
5. Tắt client giữ lock, chờ lease hết hạn rồi xin lại.
6. Ngắt peer, xác minh thao tác ghi bị từ chối và dữ liệu cục bộ không đổi; nối lại peer trước khi thao tác tiếp.
7. Xuất vượt tồn, xác minh transaction bị từ chối.
