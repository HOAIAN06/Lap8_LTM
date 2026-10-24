package com.warehouse.server.service;

import com.warehouse.common.dto.LoginResult;
import com.warehouse.common.dto.ProductInput;
import com.warehouse.common.model.InventoryTransaction;
import com.warehouse.common.model.Product;
import com.warehouse.common.rmi.WarehouseService;
import com.warehouse.common.exception.WarehouseException;
import com.warehouse.common.rmi.LockService;
import com.warehouse.server.dao.ProductRepository;
import com.warehouse.server.sync.SyncPayloadCodec;
import com.warehouse.server.sync.SyncValues;

import java.rmi.RemoteException;
import java.rmi.registry.Registry;
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
    private final Registry peerRegistry;

    public WarehouseServiceImpl(javax.sql.DataSource dataSource, LockService lockManager,
                                String serverId, int servicePort, Registry peerRegistry)
            throws RemoteException {
        super(servicePort);
        this.dataSource = dataSource;
        this.products = new ProductRepository(dataSource);
        this.lockManager = lockManager;
        this.serverId = SyncValues.origin(serverId);
        this.peerRegistry = peerRegistry;
    }

    @Override
    public LoginResult login(String username, String password) throws RemoteException {
        String selectedUsername = username == null ? "" : username.trim();
        if (selectedUsername.isEmpty() || password == null || password.isEmpty()) {
            return new LoginResult(false, selectedUsername, "", "", "Vui lòng nhập username và mật khẩu.");
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT username, password, full_name, role, is_active FROM users WHERE username=?")) {
            ps.setString(1, selectedUsername);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new LoginResult(false, selectedUsername, "", "", "Sai username hoặc mật khẩu.");
                }
                if (!rs.getBoolean("is_active")) {
                    return new LoginResult(false, selectedUsername, "", "", "Tài khoản nhân viên đã bị vô hiệu hóa.");
                }
                if (!password.equals(rs.getString("password"))) {
                    return new LoginResult(false, selectedUsername, "", "", "Sai username hoặc mật khẩu.");
                }
                return new LoginResult(true, rs.getString("username"), rs.getString("full_name"),
                        rs.getString("role"), "OK");
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
            try {
                Product product = products.insert(c, input);
                writeEvent(c, product.id(), "INSERT", null);
                c.commit();
                return product;
            } catch (SQLException e) {
                c.rollback();
                if (isDuplicateKey(e)) {
                    throw new WarehouseException("Mã sản phẩm đã tồn tại.");
                }
                throw e;
            }
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
            try {
                Product product = products.update(c, id, input, fencingToken);
                if (product == null) throw new WarehouseException("Không tìm thấy sản phẩm.");
                writeEvent(c, id, "UPDATE", null);
                c.commit();
                return product;
            } catch (SQLException | WarehouseException | RuntimeException e) {
                c.rollback();
                throw e;
            }
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
            try {
                products.softDelete(c, id, fencingToken);
                writeEvent(c, id, "DELETE", null);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new WarehouseException("Không thể xóa sản phẩm.", e);
        }
    }

    @Override
    public InventoryTransaction changeInventory(UUID id, int amount, boolean importing,
                                                UUID lockToken, long fencingToken,
                                                String clientId, String username)
            throws RemoteException {
        requireLock(id, lockToken, fencingToken, clientId);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Product product = products.find(c, id);
                if (product == null || product.deleted()) throw new WarehouseException("Không tìm thấy sản phẩm.");
                if (product.lastFencingToken() > fencingToken) {
                    throw new WarehouseException("Khóa đã bị thay thế bởi phiên mới.");
                }
                int after = InventoryRules.quantityAfter(product.quantity(), amount, importing);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE products SET quantity=?,version=version+1,last_fencing_token=?,updated_at=now() "
                                + "WHERE id=? AND is_deleted = 0 AND last_fencing_token<=?")) {
                    ps.setInt(1, after);
                    ps.setLong(2, fencingToken);
                    ps.setString(3, id.toString());
                    ps.setLong(4, fencingToken);
                    int affectedRows = ps.executeUpdate();
                    if (affectedRows != 1) {
                        throw new WarehouseException("Khóa đã hết hạn, bị thay thế hoặc sản phẩm đã bị xóa.");
                    }
                }
                UUID userId = userId(c, username);
                InventoryTransaction result = new InventoryTransaction(UUID.randomUUID(),
                        "TX-" + UUID.randomUUID(), id, SyncValues.transactionType(importing), amount,
                        product.quantity(), after, username, Instant.now());
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO inventory_transactions(id,transaction_code,product_id,transaction_type,quantity,quantity_before,quantity_after,created_by) VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, result.id().toString());
                    ps.setString(2, result.transactionCode());
                    ps.setString(3, id.toString());
                    ps.setString(4, result.transactionType());
                    ps.setInt(5, amount);
                    ps.setInt(6, result.quantityBefore());
                    ps.setInt(7, after);
                    ps.setString(8, userId.toString());
                    ps.executeUpdate();
                }
                writeEvent(c, id, SyncValues.transactionType(importing), result.id());
                c.commit();
                return result;
            } catch (SQLException | WarehouseException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new WarehouseException("Không thể cập nhật tồn kho.", e);
        }
    }

    @Override
    public List<InventoryTransaction> transactionHistory(UUID productId) throws RemoteException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT t.*,u.username AS created_by_username FROM inventory_transactions t "
                             + "LEFT JOIN users u ON u.id=t.created_by WHERE t.product_id=? ORDER BY t.created_at DESC")) {
            ps.setString(1, productId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<InventoryTransaction> result = new ArrayList<>();
                while (rs.next()) result.add(new InventoryTransaction(UUID.fromString(rs.getString("id")),
                        rs.getString("transaction_code"), UUID.fromString(rs.getString("product_id")),
                        rs.getString("transaction_type"), rs.getInt("quantity"),
                        rs.getInt("quantity_before"), rs.getInt("quantity_after"),
                        rs.getString("created_by_username"), rs.getTimestamp("created_at").toInstant()));
                return result;
            }
        } catch (SQLException e) {
            throw new WarehouseException("Không thể tải lịch sử giao dịch.", e);
        }
    }

    @Override
    public boolean isPeerAvailable() {
        if (peerRegistry == null) return false;
        try {
            peerRegistry.lookup(com.warehouse.common.rmi.RmiNames.SYNC);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDuplicateKey(SQLException e) {
        SQLException current = e;
        while (current != null) {
            if (current.getErrorCode() == 1062) return true;
            String state = current.getSQLState();
            if (state != null && (state.equals("23000") || state.equals("23505"))) {
                return true;
            }
            Throwable cause = current.getCause();
            current = (cause instanceof SQLException sqlEx) ? sqlEx : null;
        }
        return false;
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

    private UUID userId(Connection connection, String username) throws SQLException, WarehouseException {
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT id FROM users WHERE username=? AND is_active=1")) {
            read.setString(1, username);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) throw new WarehouseException("Tài khoản không tồn tại hoặc đã bị khóa.");
                return UUID.fromString(row.getString(1));
            }
        }
    }

    private void writeEvent(Connection c, UUID entityId, String action, UUID transactionId) throws SQLException {
        String normalizedAction = SyncValues.action(action);
        String payload = SyncPayloadCodec.fromDatabase(c, entityId, transactionId);
        long version;
        try (PreparedStatement read = c.prepareStatement("SELECT version FROM products WHERE id=?")) {
            read.setString(1, entityId.toString());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) throw new SQLException("Product missing before outbox insert");
                version = row.getLong(1);
            }
        }
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO sync_events(event_id,origin_server,entity_type,entity_id,action,payload,entity_version,sync_status) "
                        + "VALUES (?,?,?,?,?,?,?,'PENDING')")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, serverId);
            insert.setString(3, "PRODUCT");
            insert.setString(4, entityId.toString());
            insert.setString(5, normalizedAction);
            insert.setString(6, payload);
            insert.setLong(7, version);
            insert.executeUpdate();
        }
    }
}
