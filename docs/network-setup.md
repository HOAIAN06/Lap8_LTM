# Thiết lập mạng

Dùng Bridged Adapter cho hai máy ảo, cấp IP có thể truy cập giữa bốn máy, mở TCP cho registry/service port và MySQL outbound. Kiểm tra bằng `ping`, `Test-NetConnection <host> -Port <port>`. Không dùng `localhost` trong cấu hình liên máy.
