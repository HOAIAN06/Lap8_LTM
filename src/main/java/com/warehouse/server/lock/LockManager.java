package com.warehouse.server.lock;

import com.warehouse.common.dto.LockLease;
import com.warehouse.common.exception.WarehouseException;
import com.warehouse.common.rmi.LockService;

import javax.sql.DataSource;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Server A's lock coordinator. The product row serializes acquisition and persists fencing. */
public final class LockManager extends UnicastRemoteObject implements LockService {
    private final DataSource dataSource;
    private final Clock clock;
    private final int leaseSeconds;

    public LockManager(DataSource dataSource, int leaseSeconds) throws RemoteException {
        this(dataSource, leaseSeconds, Clock.systemUTC());
    }

    LockManager(DataSource dataSource, int leaseSeconds, Clock clock) throws RemoteException {
        super();
        if (leaseSeconds <= 0) throw new IllegalArgumentException("Lease duration must be positive");
        this.dataSource = dataSource;
        this.leaseSeconds = leaseSeconds;
        this.clock = clock;
    }

    @Override
    public LockLease acquire(UUID resourceId, String clientId) throws RemoteException {
        if (resourceId == null || clientId == null || clientId.isBlank()) {
            throw new WarehouseException("Sản phẩm và Client ID phải hợp lệ.");
        }
        Instant now = clock.instant();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long lastFencing = productFence(connection, resourceId);
                LockLease current = lockByResource(connection, resourceId);
                LockRules.ensureAvailable(current, clientId, now);
                if (hasPendingSync(connection, resourceId)) {
                    throw new WarehouseException("Sản phẩm đang chờ đồng bộ. Vui lòng thử lại sau.");
                }
                long next = LockRules.nextFencingToken(lastFencing);
                LockLease lease = new LockLease(resourceId, clientId, UUID.randomUUID(), next,
                        now, now.plusSeconds(leaseSeconds));
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE products SET last_fencing_token=? WHERE id=?")) {
                    update.setLong(1, next);
                    update.setString(2, resourceId.toString());
                    update.executeUpdate();
                }
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO product_locks(resource_id,owner_client_id,lock_token,fencing_token,"
                                + "acquired_at,expires_at) VALUES (?,?,?,?,?,?) "
                                + "ON DUPLICATE KEY UPDATE owner_client_id=VALUES(owner_client_id),"
                                + "lock_token=VALUES(lock_token),fencing_token=VALUES(fencing_token),"
                                + "acquired_at=VALUES(acquired_at),expires_at=VALUES(expires_at)")) {
                    upsert.setString(1, resourceId.toString());
                    upsert.setString(2, clientId);
                    upsert.setString(3, lease.lockToken().toString());
                    upsert.setLong(4, next);
                    upsert.setTimestamp(5, Timestamp.from(now));
                    upsert.setTimestamp(6, Timestamp.from(lease.expiresAt()));
                    upsert.executeUpdate();
                }
                connection.commit();
                return lease;
            } catch (SQLException | RuntimeException | WarehouseException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new WarehouseException("Không thể cấp khóa từ MariaDB.", e);
        }
    }

    @Override
    public LockLease heartbeat(UUID token, String clientId) throws RemoteException {
        Instant now = clock.instant();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                LockLease current = lockByToken(connection, token);
                if (current == null || !LockRules.valid(current, current.resourceId(), token,
                        current.fencingToken(), clientId, now)) {
                    throw new WarehouseException("Lock token không hợp lệ hoặc đã hết hạn.");
                }
                LockLease refreshed = new LockLease(current.resourceId(), clientId, token,
                        current.fencingToken(), current.acquiredAt(), now.plusSeconds(leaseSeconds));
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE product_locks SET expires_at=? WHERE lock_token=?")) {
                    update.setTimestamp(1, Timestamp.from(refreshed.expiresAt()));
                    update.setString(2, token.toString());
                    update.executeUpdate();
                }
                connection.commit();
                return refreshed;
            } catch (SQLException | RuntimeException | WarehouseException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new WarehouseException("Không thể gia hạn khóa.", e);
        }
    }

    @Override
    public void release(UUID token, String clientId) throws RemoteException {
        try (Connection connection = dataSource.getConnection()) {
            LockLease current = lockByToken(connection, token);
            if (current == null || !current.ownerClientId().equals(clientId)) {
                throw new WarehouseException("Không thể giải phóng khóa không thuộc Client.");
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM product_locks WHERE lock_token=? AND owner_client_id=?")) {
                delete.setString(1, token.toString());
                delete.setString(2, clientId);
                delete.executeUpdate();
            }
        } catch (SQLException e) {
            throw new WarehouseException("Không thể giải phóng khóa.", e);
        }
    }

    @Override
    public boolean validate(UUID resourceId, UUID token, long fencingToken, String clientId)
            throws RemoteException {
        if (resourceId == null || token == null || clientId == null) return false;
        try (Connection connection = dataSource.getConnection()) {
            return LockRules.valid(lockByResource(connection, resourceId), resourceId,
                    token, fencingToken, clientId, clock.instant());
        } catch (SQLException e) {
            throw new WarehouseException("Không thể kiểm tra khóa.", e);
        }
    }

    private static long productFence(Connection connection, UUID id) throws SQLException, WarehouseException {
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT last_fencing_token FROM products WHERE id=? AND NOT is_deleted FOR UPDATE")) {
            read.setString(1, id.toString());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) throw new WarehouseException("Không tìm thấy sản phẩm để khóa.");
                return row.getLong(1);
            }
        }
    }

    private static boolean hasPendingSync(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT 1 FROM sync_events WHERE entity_id=? "
                        + "AND sync_status IN ('PENDING','PROCESSING','FAILED') LIMIT 1")) {
            read.setString(1, id.toString());
            try (ResultSet row = read.executeQuery()) { return row.next(); }
        }
    }

    private static LockLease lockByResource(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT * FROM product_locks WHERE resource_id=? FOR UPDATE")) {
            read.setString(1, id.toString());
            try (ResultSet row = read.executeQuery()) { return row.next() ? map(row) : null; }
        }
    }

    private static LockLease lockByToken(Connection connection, UUID token) throws SQLException {
        if (token == null) return null;
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT * FROM product_locks WHERE lock_token=? FOR UPDATE")) {
            read.setString(1, token.toString());
            try (ResultSet row = read.executeQuery()) { return row.next() ? map(row) : null; }
        }
    }

    private static LockLease map(ResultSet row) throws SQLException {
        return new LockLease(UUID.fromString(row.getString("resource_id")),
                row.getString("owner_client_id"), UUID.fromString(row.getString("lock_token")),
                row.getLong("fencing_token"), row.getTimestamp("acquired_at").toInstant(),
                row.getTimestamp("expires_at").toInstant());
    }
}
