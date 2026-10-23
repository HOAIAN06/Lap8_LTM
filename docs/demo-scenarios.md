# Kịch bản demo

1. Thêm SP011 ở Client A và kiểm tra hai database.
2. Xuất 5 SP001, kiểm tra tồn kho hai server.
3. Giữ lock SP003 ở Client A, thử lock từ Client B.
4. Sửa SP001 và SP002 song song.
5. Tắt client giữ lock, chờ lease hết hạn rồi xin lại.
6. Ngắt peer, xác minh outbox pending và gửi lại sau khi nối lại.
7. Xuất vượt tồn, xác minh transaction bị từ chối.
