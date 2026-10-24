# MariaDB setup

MariaDB 10.4.32 đang chạy trên `127.0.0.1:3306`, database `lap8_ltm`, user `root` và password rỗng cho môi trường local. Cấu hình dùng:

```properties
db.url=jdbc:mariadb://127.0.0.1:3306/lap8_ltm
db.username=root
db.password=
```

Hai database của Server A và Server B phải độc lập trong môi trường triển khai. Ứng dụng không tạo, reset hoặc thay đổi schema/dữ liệu khi khởi động; nó chỉ chạy `SELECT 1` và `SELECT COUNT(*) FROM products` để kiểm tra kết nối/quyền đọc.
