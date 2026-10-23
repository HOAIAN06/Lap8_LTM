# Kiến trúc

Server A là lock coordinator duy nhất. Mỗi server có MySQL riêng, nhận yêu cầu từ client qua RMI và gửi `SyncEvent` sang peer qua RMI. Ghi kho và outbox event nằm trong cùng transaction. Peer dùng `processed_events` để chống xử lý trùng; event chỉ chuyển `SYNCED` sau xác nhận.

Lease lock có `lock_token` và `fencing_token`. Khi coordinator không truy cập được, server không tự cấp lock mới.
