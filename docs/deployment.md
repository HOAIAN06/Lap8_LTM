# Triển khai

Build bằng Maven trên máy phát triển, sao chép `target\Lap8_LTM-1.0.0-SNAPSHOT-all.jar` và file cấu hình cục bộ đến từng máy. Đây là shaded JAR có đủ dependency; JAR không có hậu tố `-all` không tự chứa thư viện ngoài.

Khởi động Server A trước để làm lock coordinator, sau đó Server B và các client. Mỗi thao tác ghi gọi trực tiếp `SyncService` của peer và chỉ commit cục bộ sau khi peer phản hồi thành công. Nếu peer đã commit nhưng kết nối bị mất trước khi máy gọi commit được, hai database có thể lệch; rollback cục bộ không thể rollback commit đã xảy ra trên peer. Đây là giới hạn của demo Direct RMI, không phải giao dịch phân tán 2 pha.
