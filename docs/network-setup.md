# Thiết lập mạng

Dùng Bridged Adapter cho hai máy ảo, cấp IP có thể truy cập giữa bốn máy, mở TCP cho registry/service port và MySQL outbound. Server tự phát hiện IPv4 LAN nhưng luôn cho phép nhập thủ công. Client có thể kiểm tra và lưu IP server thành công gần nhất. Kiểm tra bằng `ping`, `Test-NetConnection <host> -Port <port>`. Không dùng `localhost` trong cấu hình RMI liên máy.
