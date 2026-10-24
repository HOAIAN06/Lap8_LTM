package com.warehouse.server.service;

import com.warehouse.common.dto.LoginResult;
import com.warehouse.common.dto.ProductInput;
import com.warehouse.common.dto.RegistrationResult;
import com.warehouse.common.model.InventoryTransaction;
import com.warehouse.common.model.Product;
import com.warehouse.common.rmi.RmiNames;
import com.warehouse.common.rmi.SyncService;
import com.warehouse.common.rmi.WarehouseService;
import com.warehouse.common.exception.WarehouseException;
import com.warehouse.common.rmi.LockService;
import com.warehouse.server.dao.ProductRepository;
import com.warehouse.server.sync.SyncValues;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public final class WarehouseServiceImpl extends UnicastRemoteObject implements WarehouseService {
    private static final Logger LOG = Logger.getLogger(WarehouseServiceImpl.class.getName());

    private final javax.sql.DataSource dataSource;
    private final ProductRepository products;
    private final LockService lockManager;
    private final String serverId;
    private final String peerHost;
    private final int peerPort;
    private volatile Registry peerRegistry;

    public WarehouseServiceImpl(javax.sql.DataSource dataSource, LockService lockManager,
                                String serverId, int servicePort, Registry peerRegistry)
            throws RemoteException {
        this(dataSource, lockManager, serverId, servicePort, null, 0, peerRegistry);
    }

    public WarehouseServiceImpl(javax.sql.DataSource dataSource, LockService lockManager,
                                String serverId, int servicePort,
                                String peerHost, int peerPort, Registry peerRegistry)
            throws RemoteException {
        super(servicePort);
        this.dataSource = dataSource;
        this.products = new ProductRepository(dataSource);
        this.lockManager = lockManager;
        this.serverId = SyncValues.origin(serverId);
        this.peerHost = peerHost;
        this.peerPort = peerPort;
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
    public RegistrationResult register(String username, String password, String fullName)
            throws RemoteException {
        String selectedUsername = username == null ? "" : username.trim();
        String selectedFullName = fullName == null ? "" : fullName.trim();
        if (selectedUsername.isEmpty() || password == null || password.isEmpty()
                || selectedFullName.isEmpty()) {
            return new RegistrationResult(false, selectedUsername,
                    "Vui lòng nhập đầy đủ username, họ tên và mật khẩu.");
        }
        if (selectedUsername.length() > 100 || selectedFullName.length() > 255
                || password.length() > 255) {
            return new RegistrationResult(false, selectedUsername,
                    "Tài khoản, họ tên hoặc mật khẩu vượt quá độ dài cho phép.");
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users(id,username,password,full_name,role,is_active,created_at) "
                             + "VALUES (?,?,?,?, 'STAFF', 1, NOW(3))")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, selectedUsername);
            ps.setString(3, password);
            ps.setString(4, selectedFullName);
            ps.executeUpdate();
            return new RegistrationResult(true, selectedUsername,
                    "Đăng ký thành công. Bạn có thể đăng nhập.");
        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                return new RegistrationResult(false, selectedUsername,
                        "Username đã tồn tại. Vui lòng chọn username khác.");
            }
            throw new WarehouseException("Không thể đăng ký tài khoản do lỗi cơ sở dữ liệu.", e);
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
                SyncService peerSync = requirePeerSync();
                peerSync.syncCreateProduct(product);
                c.commit();
                return product;
            } catch (SQLException e) {
                c.rollback();
                if (isDuplicateKey(e)) {
                    throw new WarehouseException("Mã sản phẩm đã tồn tại.");
                }
                throw e;
            } catch (WarehouseException e) {
                c.rollback();
                throw e;
            } catch (RemoteException e) {
                c.rollback();
                throw new WarehouseException("Không thể cập nhật dữ liệu vì Server còn lại đang mất kết nối.", e);
            } catch (Exception e) {
                c.rollback();
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
                SyncService peerSync = requirePeerSync();
                peerSync.syncUpdateProduct(product);
                c.commit();
                return product;
            } catch (SQLException | WarehouseException e) {
                c.rollback();
                throw e;
            } catch (RemoteException e) {
                c.rollback();
                throw new WarehouseException("Không thể cập nhật dữ liệu vì Server còn lại đang mất kết nối.", e);
            } catch (Exception e) {
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
                SyncService peerSync = requirePeerSync();
                peerSync.syncDeleteProduct(id, fencingToken);
                c.commit();
            } catch (SQLException | WarehouseException e) {
                c.rollback();
                throw e;
            } catch (RemoteException e) {
                c.rollback();
                throw new WarehouseException("Không thể cập nhật dữ liệu vì Server còn lại đang mất kết nối.", e);
            } catch (Exception e) {
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
                SyncService peerSync = requirePeerSync();
                peerSync.syncInventory(id, after, fencingToken, result);
                c.commit();
                return result;
            } catch (SQLException | WarehouseException e) {
                c.rollback();
                throw e;
            } catch (RemoteException e) {
                c.rollback();
                throw new WarehouseException("Không thể cập nhật dữ liệu vì Server còn lại đang mất kết nối.", e);
            } catch (Exception e) {
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
        try {
            SyncService peer = getPeerSync();
            return peer != null && peer.ping();
        } catch (Exception e) {
            return false;
        }
    }

    public void setPeerRegistry(Registry peerRegistry) {
        this.peerRegistry = peerRegistry;
    }

    private SyncService getPeerSync() {
        try {
            Registry reg = this.peerRegistry;
            if (reg == null && peerHost != null && !peerHost.isBlank() && peerPort > 0) {
                reg = LocateRegistry.getRegistry(peerHost, peerPort);
            }
            if (reg != null) {
                return (SyncService) reg.lookup(RmiNames.SYNC);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private SyncService requirePeerSync() throws WarehouseException {
        SyncService peer = getPeerSync();
        if (peer == null) {
            throw new WarehouseException("Không thể cập nhật dữ liệu vì Server còn lại đang mất kết nối.");
        }
        try {
            if (!peer.ping()) {
                throw new WarehouseException("Không thể cập nhật dữ liệu vì Server còn lại đang mất kết nối.");
            }
            return peer;
        } catch (RemoteException e) {
            throw new WarehouseException("Không thể cập nhật dữ liệu vì Server còn lại đang mất kết nối.", e);
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
}
