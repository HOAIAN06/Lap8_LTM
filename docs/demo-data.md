# Dữ liệu demo cho hai MariaDB độc lập

Ứng dụng kiểm tra `username`, mật khẩu văn bản thường trong cột `users.password` và `is_active`. Đây là cơ chế cho bài lab, không phải xác thực an toàn. Không dùng mật khẩu thật.

Trên MariaDB A hiện đã có 3 tài khoản (`admin`, `staff_a`, `staff_b`) và 10 sản phẩm khi kiểm tra. Cột cũ `password_hash` vẫn tồn tại và cả ba giá trị đang `NULL`. **Không chạy lại** [`scripts/demo-seed.sql`](../scripts/demo-seed.sql) trên database này vì script dành riêng cho database trống.

Trong HeidiSQL, tự đổi tên cột cũ thành `password` (kiểu `VARCHAR(255)`), rồi điền mật khẩu văn bản thường cho từng tài khoản hiện có. Kiểm tra `username`, `password` và `is_active` của cả ba tài khoản trước khi chạy Client. Thực hiện tương tự trên MariaDB B với cùng tài khoản và UUID; đồng bộ tài khoản chưa được triển khai. Mình không thực hiện các thay đổi database này.

Với database **thực sự trống**, file SQL mẫu tạo 3 tài khoản và 10 sản phẩm có UUID giống nhau trên A/B. Xem các lệnh `SELECT` ở đầu script và toàn bộ dữ liệu trước khi tự chạy trong HeidiSQL. Mật khẩu demo trong script là `Demo@12345`, được lưu nguyên văn; chỉ dùng trong môi trường lab.

Sau khi chuẩn bị bảng `users`, chạy lại Server A và Client A, kết nối IP Server, nhập `staff_a` cùng mật khẩu đã điền. Server B/Client B dùng database riêng và tài khoản tương ứng.
