package com.warehouse.server.sync;

import com.warehouse.common.dto.SyncEvent;
import com.warehouse.common.rmi.SyncService;

import javax.sql.DataSource;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.logging.Logger;

public final class SyncServiceImpl extends UnicastRemoteObject implements SyncService {
    private static final Logger LOG = Logger.getLogger(SyncServiceImpl.class.getName());
    private final DataSource dataSource;
    private final String serverId;

    public SyncServiceImpl(DataSource dataSource, String serverId, int port) throws RemoteException {
        super(port);
        this.dataSource = dataSource;
        this.serverId = SyncValues.origin(serverId);
    }

    @Override
    public boolean applyEvent(SyncEvent event) throws RemoteException {
        if (event == null || event.eventId() == null || event.entityId() == null
                || !"PRODUCT".equals(event.entityType())) {
            throw new RemoteException("Unsupported sync event");
        }
        String origin = SyncValues.origin(event.originServer());
        String action = SyncValues.action(event.action());
        if (origin.equals(serverId)) throw new RemoteException("Event originated on this server");
        SyncPayloadCodec.Payload payload;
        try {
            payload = SyncPayloadCodec.decode(event.payload());
        } catch (IOException | RuntimeException e) {
            throw new RemoteException("Invalid sync payload", e);
        }
        if (!payload.product().id().equals(event.entityId())
                || payload.product().version() != event.entityVersion()) {
            throw new RemoteException("Sync payload does not match event metadata");
        }
        boolean inventoryAction = "IMPORT".equals(action) || "EXPORT".equals(action);
        if (inventoryAction != (payload.transaction() != null)
                || (inventoryAction && (!action.equals(payload.transaction().type())
                || !event.entityId().equals(payload.transaction().productId())))) {
            throw new RemoteException("Transaction payload does not match sync action");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!reserveEvent(connection, event.eventId(), origin)) {
                    connection.rollback();
                    return true;
                }
                Long currentVersion = currentVersion(connection, event.entityId());
                EventOrder.Decision decision = EventOrder.decide(currentVersion, event.entityVersion(), action);
                if (decision != EventOrder.Decision.APPLY) {
                    connection.rollback();
                    LOG.warning(() -> "Deferred/conflicting event " + event.eventId() + " for "
                            + event.entityId() + ": current=" + currentVersion
                            + ", incoming=" + event.entityVersion());
                    return false;
                }
                applyProduct(connection, payload.product(), currentVersion);
                if (payload.transaction() != null) applyTransaction(connection, payload.transaction());
                connection.commit();
                return true;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RemoteException("Cannot apply sync event on server " + serverId, e);
        }
    }

    private static boolean reserveEvent(Connection connection, UUID eventId, String origin) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT IGNORE INTO processed_events(event_id,source_server) VALUES (?,?)")) {
            insert.setString(1, eventId.toString());
            insert.setString(2, origin);
            return insert.executeUpdate() == 1;
        }
    }

    private static Long currentVersion(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT version FROM products WHERE id=? FOR UPDATE")) {
            read.setString(1, id.toString());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? row.getLong(1) : null;
            }
        }
    }

    private static void applyProduct(Connection connection, SyncPayloadCodec.ProductState product,
                                     Long currentVersion) throws SQLException {
        if (currentVersion == null) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO products(id,product_code,product_name,category,price,quantity,version,"
                            + "last_fencing_token,is_deleted) VALUES (?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, product.id().toString());
                insert.setString(2, product.code());
                insert.setString(3, product.name());
                insert.setString(4, product.category());
                insert.setBigDecimal(5, product.price());
                insert.setInt(6, product.quantity());
                insert.setLong(7, product.version());
                insert.setLong(8, product.fencingToken());
                insert.setBoolean(9, product.deleted());
                insert.executeUpdate();
            }
        } else {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE products SET product_code=?,product_name=?,category=?,price=?,quantity=?,"
                            + "version=?,last_fencing_token=GREATEST(last_fencing_token,?),is_deleted=? "
                            + "WHERE id=? AND version=?")) {
                update.setString(1, product.code());
                update.setString(2, product.name());
                update.setString(3, product.category());
                update.setBigDecimal(4, product.price());
                update.setInt(5, product.quantity());
                update.setLong(6, product.version());
                update.setLong(7, product.fencingToken());
                update.setBoolean(8, product.deleted());
                update.setString(9, product.id().toString());
                update.setLong(10, currentVersion);
                if (update.executeUpdate() != 1) throw new SQLException("Product version changed during sync");
            }
        }
    }

    private static void applyTransaction(Connection connection, SyncPayloadCodec.TransactionState transaction)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO inventory_transactions(id,transaction_code,product_id,transaction_type,"
                        + "quantity,quantity_before,quantity_after,created_by,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, transaction.id().toString());
            insert.setString(2, transaction.code());
            insert.setString(3, transaction.productId().toString());
            insert.setString(4, transaction.type());
            insert.setInt(5, transaction.quantity());
            insert.setInt(6, transaction.before());
            insert.setInt(7, transaction.after());
            insert.setString(8, transaction.createdBy() == null ? null : transaction.createdBy().toString());
            insert.setTimestamp(9, Timestamp.from(transaction.createdAt()));
            insert.executeUpdate();
        }
    }
}
