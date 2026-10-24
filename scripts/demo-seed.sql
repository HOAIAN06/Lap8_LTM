-- REVIEW BEFORE RUNNING. Execute manually in HeidiSQL on each independent lap8_ltm database.
-- Intended for empty users/products tables. Do not run on a populated database.
-- Review the users schema first; this sample expects a plain-text password column.
-- Demo passwords are visible in this file and are suitable only for this lab.

SELECT DATABASE() AS database_name;
SELECT COUNT(*) AS existing_users FROM users;
SELECT COUNT(*) AS existing_products FROM products;
SELECT COUNT(*) AS existing_sync_events FROM sync_events;

START TRANSACTION;

INSERT INTO users(id, username, password, full_name, role, is_active, created_at) VALUES
('10000000-0000-4000-8000-000000000001', 'admin_demo', 'Demo@12345', 'Quản trị demo', 'ADMIN', 1, NOW(3)),
('10000000-0000-4000-8000-000000000002', 'staff_a', 'Demo@12345', 'Nhân viên A', 'STAFF', 1, NOW(3)),
('10000000-0000-4000-8000-000000000003', 'staff_b', 'Demo@12345', 'Nhân viên B', 'STAFF', 1, NOW(3));

INSERT INTO products(id, product_code, product_name, category, price, quantity,
                     version, last_fencing_token, is_deleted) VALUES
('20000000-0000-4000-8000-000000000001', 'SP001', 'Bút bi xanh',      'Văn phòng phẩm',  5000.00, 20, 0, 0, 0),
('20000000-0000-4000-8000-000000000002', 'SP002', 'Bút bi đen',       'Văn phòng phẩm',  5000.00, 35, 0, 0, 0),
('20000000-0000-4000-8000-000000000003', 'SP003', 'Sổ tay A5',        'Sổ giấy',        25000.00, 40, 0, 0, 0),
('20000000-0000-4000-8000-000000000004', 'SP004', 'Sổ tay A4',        'Sổ giấy',        38000.00, 28, 0, 0, 0),
('20000000-0000-4000-8000-000000000005', 'SP005', 'Bìa hồ sơ',        'Văn phòng phẩm', 12000.00, 50, 0, 0, 0),
('20000000-0000-4000-8000-000000000006', 'SP006', 'Kẹp giấy hộp',     'Văn phòng phẩm',  9000.00, 60, 0, 0, 0),
('20000000-0000-4000-8000-000000000007', 'SP007', 'Băng keo trong',   'Đóng gói',       14000.00, 32, 0, 0, 0),
('20000000-0000-4000-8000-000000000008', 'SP008', 'Thùng carton nhỏ', 'Đóng gói',       18000.00, 24, 0, 0, 0),
('20000000-0000-4000-8000-000000000009', 'SP009', 'Nhãn dán',         'Đóng gói',        7000.00, 80, 0, 0, 0),
('20000000-0000-4000-8000-000000000010', 'SP010', 'Kéo văn phòng',    'Văn phòng phẩm', 32000.00, 15, 0, 0, 0);

COMMIT;

SELECT COUNT(*) AS users_after FROM users;
SELECT COUNT(*) AS products_after FROM products;
