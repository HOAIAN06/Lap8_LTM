# Triển khai

Build bằng Maven trên máy phát triển, sao chép JAR và file cấu hình cục bộ đến từng máy. Khởi động Server A trước để lock coordinator, sau đó Server B và các client. Khi peer mất kết nối, event ở trạng thái `PENDING`/`FAILED`, không được hiển thị là đã đồng bộ.
