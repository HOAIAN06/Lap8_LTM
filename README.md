# DistributedWarehouseRMI

Hệ thống quản lý kho phân tán bằng Java 17, Java RMI, Swing và MySQL.

## Build

```bat
mvn clean test package
```

Sao chép các file `src/main/resources/*.properties.example` thành file cấu hình cục bộ, điền IP/credential MySQL thật và không commit chúng. Mỗi server phải trỏ tới một database MySQL độc lập. Chạy IntelliJ với program argument là đường dẫn properties hoặc VM option `-Dwarehouse.config=<path>`.

Server chỉ truy cập MySQL; client chỉ gọi RMI. RMI registry và remote object dùng cổng cấu hình cố định. Xem `docs/` để biết thiết lập mạng, triển khai và kịch bản demo.
