# Kiến trúc

Server A là lock coordinator duy nhất. Mỗi server có MariaDB riêng, nhận yêu cầu từ client qua RMI và gọi `SyncService` của peer trực tiếp trong thao tác ghi. Máy gọi chỉ commit cục bộ sau khi peer phản hồi thành công.

Lease lock có `lock_token` và `fencing_token`. Khi coordinator không truy cập được, server không tự cấp lock mới.

Server A lưu lease trong `product_locks`; mỗi lần cấp khóa, nó tăng `products.last_fencing_token` trong transaction và khóa dòng sản phẩm bằng `FOR UPDATE`. Vì vậy token của cùng một sản phẩm không bị tái sử dụng sau khi Server A khởi động lại. Client giữ khóa gia hạn bằng heartbeat; token hết hạn hoặc bị thay thế không hợp lệ.

Direct RMI Sync gọi `SyncService` của peer ngay trong thao tác ghi. Peer áp dụng snapshot bằng upsert/điều kiện version và ghi giao dịch bằng khóa chính `inventory_transactions.id`, nên cùng một giao dịch không tạo lịch sử trùng. Đây là đồng bộ trực tiếp, không có outbox retry và không có transaction coordinator; nếu peer commit trước rồi máy gọi lỗi trước local commit, cần phát hiện/đối soát thủ công trong phạm vi demo.
