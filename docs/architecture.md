# Kiến trúc

Server A là lock coordinator duy nhất. Mỗi server có MySQL riêng, nhận yêu cầu từ client qua RMI và gửi `SyncEvent` sang peer qua RMI. Ghi kho và outbox event nằm trong cùng transaction. Peer dùng `processed_events` để chống xử lý trùng; event chỉ chuyển `SYNCED` sau xác nhận.

Lease lock có `lock_token` và `fencing_token`. Khi coordinator không truy cập được, server không tự cấp lock mới.

Server A lưu lease trong `product_locks`; mỗi lần cấp khóa, nó tăng `products.last_fencing_token` trong transaction và khóa dòng sản phẩm bằng `FOR UPDATE`. Vì vậy token của cùng một sản phẩm không bị tái sử dụng sau khi Server A khởi động lại. Client giữ khóa gia hạn bằng heartbeat; token hết hạn hoặc bị thay thế không hợp lệ.

Sự kiện outbox dùng `origin_server=A/B` và `action=INSERT/UPDATE/DELETE/IMPORT/EXPORT` đúng ENUM hiện có. Payload chứa snapshot sản phẩm và, với nhập/xuất kho, cả giao dịch. Peer dành chỗ cho `event_id` trong `processed_events`, kiểm tra version kế tiếp rồi ghi sản phẩm, giao dịch và dấu đã xử lý trong cùng transaction. Event trùng được xác nhận lại; event thiếu bước hoặc xung đột trả về lỗi để outbox giữ `FAILED` và thử lại. `SYNCED` chỉ được ghi sau phản hồi xác nhận từ peer.

Outbox là đồng bộ bất đồng bộ: hai database chỉ nhất quán sau khi event hoàn tất. Server A chặn cấp khóa mới cho sản phẩm có event cục bộ chưa đồng bộ. Sự kiện xung đột giữa hai máy hoặc mất kết nối kéo dài vẫn cần kiểm thử và xử lý vận hành; hệ thống không phải giao dịch nguyên tử trên hai database.
