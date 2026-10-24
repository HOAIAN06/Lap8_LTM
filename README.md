# DistributedWarehouseRMI

Hệ thống quản lý kho phân tán bằng Java 17, Java RMI, Swing và MariaDB.

Giao diện Client dùng Swing thuần, tập trung vào các thao tác cần thiết: kết nối,
đăng nhập, tìm sản phẩm, thêm/sửa, khóa, nhập/xuất kho và xem lịch sử. Server có
màn hình giám sát tối giản sau khi khởi động thành công.

## Build

```bat
mvn clean test package
```

Sao chép các file `src/main/resources/*.properties.example` thành file cấu hình cục bộ, điền IP/credential MySQL thật và không commit chúng. Mỗi server phải trỏ tới một database MySQL độc lập. Khi chạy server, ứng dụng hiển thị IPv4 LAN phát hiện được và cho phép sửa; sau đó yêu cầu nhập IP server còn lại và lưu lựa chọn. Khi chạy client, nhập IP server, bấm **Kiểm tra kết nối** hoặc **Kết nối**; IP thành công gần nhất được lưu vào file properties. Chạy IntelliJ với program argument là đường dẫn properties hoặc VM option `-Dwarehouse.config=<path>`.

Server chỉ truy cập MySQL; client chỉ gọi RMI. RMI registry và remote object dùng cổng cấu hình cố định. Xem `docs/` để biết thiết lập mạng, triển khai và kịch bản demo.
