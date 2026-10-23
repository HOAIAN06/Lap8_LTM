package com.warehouse.server.sync;

import com.warehouse.common.dto.SyncEvent;
import com.warehouse.common.rmi.SyncService;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class SyncServiceImpl extends UnicastRemoteObject implements SyncService {
    private final javax.sql.DataSource dataSource;
    private final String serverId;

    public SyncServiceImpl(javax.sql.DataSource dataSource, String serverId, int port) throws RemoteException {
        super(port);
        this.dataSource = dataSource;
        this.serverId = serverId;
    }

    @Override
    public boolean applyEvent(SyncEvent event) throws RemoteException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement check = c.prepareStatement("SELECT 1 FROM processed_events WHERE event_id=?")) {
                check.setObject(1, event.eventId());
                if (check.executeQuery().next()) {
                    c.rollback();
                    return true;
                }
            }
            if ("PRODUCT".equals(event.entityType())) {
                try (PreparedStatement merge = c.prepareStatement(
                        "INSERT INTO products(id,product_code,product_name,category,price,quantity,version,last_fencing_token,is_deleted,updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP) "
                                + "ON DUPLICATE KEY UPDATE product_code=VALUES(product_code),product_name=VALUES(product_name),"
                                + "category=VALUES(category),price=VALUES(price),quantity=VALUES(quantity),version=VALUES(version),"
                                + "last_fencing_token=VALUES(last_fencing_token),is_deleted=VALUES(is_deleted),updated_at=CURRENT_TIMESTAMP")) {
                    var json = event.payload();
                    merge.setString(1, jsonValue(json, "id"));
                    merge.setString(2, jsonValue(json, "product_code"));
                    merge.setString(3, jsonValue(json, "product_name"));
                    merge.setString(4, jsonValue(json, "category"));
                    merge.setString(5, jsonValue(json, "price"));
                    merge.setString(6, jsonValue(json, "quantity"));
                    merge.setString(7, jsonValue(json, "version"));
                    merge.setString(8, jsonValue(json, "last_fencing_token"));
                    merge.setString(9, jsonValue(json, "is_deleted"));
                    merge.executeUpdate();
                }
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO processed_events(event_id,source_server) VALUES (?,?)")) {
                insert.setObject(1, event.eventId());
                insert.setString(2, event.originServer());
                insert.executeUpdate();
            }
            c.commit();
            return true;
        } catch (SQLException e) {
            throw new RemoteException("Không thể nhận sync event trên " + serverId, e);
        }
    }

    private static String jsonValue(String json, String key) throws RemoteException {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) throw new RemoteException("Payload thiếu trường " + key);
        start += marker.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(start, end).trim();
    }
}
