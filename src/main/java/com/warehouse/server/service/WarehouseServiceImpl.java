package com.warehouse.server.service;

import com.warehouse.common.dto.LoginResult;
import com.warehouse.common.dto.ProductInput;
import com.warehouse.common.model.InventoryTransaction;
import com.warehouse.common.model.Product;
import com.warehouse.common.rmi.WarehouseService;
import com.warehouse.common.exception.WarehouseException;
import com.warehouse.common.rmi.LockService;
import com.warehouse.server.dao.ProductRepository;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WarehouseServiceImpl extends UnicastRemoteObject implements WarehouseService {
    private final javax.sql.DataSource dataSource;
    private final ProductRepository products;
    private final LockService lockManager;
    private final String serverId;

    public WarehouseServiceImpl(javax.sql.DataSource dataSource, LockService lockManager,
                                String serverId, int servicePort)
            throws RemoteException {
        super(servicePort);
        this.dataSource = dataSource;
        this.products = new ProductRepository(dataSource);
        this.lockManager = lockManager;
        this.serverId = serverId;
    }

    @Override
    public LoginResult login(String username, String password) throws RemoteException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT full_name, role, password_hash FROM users WHERE username=? AND is_active")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !org.mindrot.jbcrypt.BCrypt.checkpw(password, rs.getString("password_hash"))) {
                    return new LoginResult(false, username, "", "", "Sai tài khoản hoặc mật khẩu.");
                }
                return new LoginResult(true, username, rs.getString("full_name"), rs.getString("role"), "OK");
            }
        } catch (SQLException e) {
            throw new WarehouseException("Không thể đăng nhập do lỗi cơ sở dữ liệu.", e);
        }
    }

    @Override
    public List<Product> listProducts(String search) throws RemoteException {
        try {
            return products.findAll(search);
        } catch (SQLException e) {
            throw new WarehouseException("Không thể tải danh sách sản phẩm.", e);
        }
    }

    @Override
    public Product createProduct(ProductInput input, String clientId, String username) throws RemoteException {
        validateInput(input);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            Product product = products.insert(c, input);
            writeEvent(c, product.id(), "CREATE", product.version());
            c.commit();
            return product;
        } catch (SQLException e) {
            throw new WarehouseException("Không thể thêm sản phẩm.", e);
        }
    }

    @Override
    public Product updateProduct(UUID id, ProductInput input, UUID lockToken, long fencingToken,
                                 String clientId, String username) throws RemoteException {
        validateInput(input);
        requireLock(id, lockToken, fencingToken, clientId);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            Product product = products.update(c, id, input, fencingToken);
            if (product == null) throw new WarehouseException("Không tìm thấy sản phẩm.");
            writeEvent(c, id, "UPDATE", product.version());
            c.commit();
            return product;
        } catch (SQLException e) {
            throw new WarehouseException("Không thể cập nhật sản phẩm.", e);
        }
    }

    @Override
    public void deleteProduct(UUID id, UUID lockToken, long fencingToken,
                              String clientId, String username) throws RemoteException {
        requireLock(id, lockToken, fencingToken, clientId);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            products.softDelete(c, id, fencingToken);
            writeEvent(c, id, "DELETE", 0);
            c.commit();
        } catch (SQLException e) {
            throw new WarehouseException("Không thể xóa sản phẩm.", e);
        }
    }

    @Override
    public InventoryTransaction changeInventory(UUID id, int amount, boolean importing,
                                                UUID lockToken, long fencingToken,
                                                String clientId, String username)
            throws RemoteException {
        if (amount <= 0) throw new WarehouseException("Số lượng phải lớn hơn 0.");
        requireLock(id, lockToken, fencingToken, clientId);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            Product product = products.find(c, id);
            if (product == null || product.deleted()) throw new WarehouseException("Không tìm thấy sản phẩm.");
            int after = importing ? product.quantity() + amount : product.quantity() - amount;
            if (after < 0) throw new WarehouseException("Số lượng xuất vượt quá tồn kho.");
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE products SET quantity=?,version=version+1,last_fencing_token=?,updated_at=now() WHERE id=?")) {
                ps.setInt(1, after); ps.setLong(2, fencingToken); ps.setObject(3, id); ps.executeUpdate();
            }
            InventoryTransaction result = new InventoryTransaction(UUID.randomUUID(),
                    "TX-" + UUID.randomUUID(), id, importing ? "IMPORT" : "EXPORT", amount,
                    product.quantity(), after, username, Instant.now());
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO inventory_transactions(id,transaction_code,product_id,transaction_type,quantity,quantity_before,quantity_after,created_by) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, result.id()); ps.setString(2, result.transactionCode()); ps.setObject(3, id);
                ps.setString(4, result.transactionType()); ps.setInt(5, amount); ps.setInt(6, result.quantityBefore());
                ps.setInt(7, after); ps.setString(8, username); ps.executeUpdate();
            }
            writeEvent(c, id, importing ? "IMPORT" : "EXPORT", product.version() + 1);
            c.commit();
            return result;
        } catch (SQLException e) {
            throw new WarehouseException("Không thể cập nhật tồn kho.", e);
        }
    }

    @Override
    public List<InventoryTransaction> transactionHistory(UUID productId) throws RemoteException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM inventory_transactions WHERE product_id=? ORDER BY created_at DESC")) {
            ps.setObject(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                List<InventoryTransaction> result = new ArrayList<>();
                while (rs.next()) result.add(new InventoryTransaction(rs.getObject("id", UUID.class),
                        rs.getString("transaction_code"), rs.getObject("product_id", UUID.class),
                        rs.getString("transaction_type"), rs.getInt("quantity"),
                        rs.getInt("quantity_before"), rs.getInt("quantity_after"),
                        rs.getString("created_by"), rs.getTimestamp("created_at").toInstant()));
                return result;
            }
        } catch (SQLException e) {
            throw new WarehouseException("Không thể tải lịch sử giao dịch.", e);
        }
    }

    @Override
    public boolean isPeerAvailable() {
        return true;
    }

    private void requireLock(UUID id, UUID token, long fencing, String clientId)
            throws RemoteException {
        if (token == null || !lockManager.validate(id, token, fencing, clientId)) {
            throw new WarehouseException("Lock không hợp lệ, đã hết hạn hoặc đã bị thay thế.");
        }
    }

    private void validateInput(ProductInput input) throws WarehouseException {
        if (input == null || input.productCode().isBlank() || input.productName().isBlank()
                || input.category().isBlank() || input.price() == null || input.price().signum() < 0) {
            throw new WarehouseException("Thông tin sản phẩm không hợp lệ.");
        }
    }

    private void writeEvent(Connection c, UUID entityId, String action, long version) throws SQLException {
        String payload;
        try (PreparedStatement read = c.prepareStatement(
                "SELECT id,product_code,product_name,category,price,quantity,version,last_fencing_token,is_deleted "
                        + "FROM products WHERE id=?")) {
            read.setObject(1, entityId);
            try (ResultSet rs = read.executeQuery()) {
                if (!rs.next()) return;
                payload = "{\"id\":\"" + rs.getObject("id") + "\","
                        + "\"product_code\":\"" + json(rs.getString("product_code")) + "\","
                        + "\"product_name\":\"" + json(rs.getString("product_name")) + "\","
                        + "\"category\":\"" + json(rs.getString("category")) + "\","
                        + "\"price\":" + rs.getBigDecimal("price") + ","
                        + "\"quantity\":" + rs.getInt("quantity") + ","
                        + "\"version\":" + rs.getLong("version") + ","
                        + "\"last_fencing_token\":" + rs.getLong("last_fencing_token") + ","
                        + "\"is_deleted\":" + rs.getBoolean("is_deleted") + "}";
            }
        }
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO sync_events(event_id,origin_server,entity_type,entity_id,action,payload,entity_version,sync_status) "
                        + "VALUES (?,?,?,?,?,?,?,'PENDING')")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, serverId);
            insert.setString(3, "PRODUCT");
            insert.setObject(4, entityId);
            insert.setString(5, action);
            insert.setString(6, payload);
            insert.setLong(7, version);
            insert.executeUpdate();
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
