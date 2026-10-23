package com.warehouse.server.sync;

import com.warehouse.common.dto.SyncEvent;
import com.warehouse.common.rmi.RmiNames;
import com.warehouse.common.rmi.SyncService;

import java.rmi.registry.Registry;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

public final class OutboxDispatcher implements AutoCloseable {
    private final javax.sql.DataSource dataSource;
    private final String serverId;
    private final Registry peerRegistry;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public OutboxDispatcher(javax.sql.DataSource dataSource, String serverId, Registry peerRegistry) {
        this.dataSource = dataSource; this.serverId = serverId; this.peerRegistry = peerRegistry;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::dispatch, 2, 10, TimeUnit.SECONDS);
    }

    private void dispatch() {
        try {
            SyncService peer = (SyncService) peerRegistry.lookup(RmiNames.SYNC);
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT * FROM sync_events WHERE sync_status IN ('PENDING','FAILED') ORDER BY created_at LIMIT 20")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SyncEvent event = new SyncEvent(rs.getObject("event_id", UUID.class),
                                rs.getString("origin_server"), rs.getString("entity_type"),
                                rs.getObject("entity_id", UUID.class), rs.getString("action"),
                                rs.getString("payload"), rs.getLong("entity_version"),
                                rs.getString("sync_status"), rs.getInt("retry_count"),
                                rs.getTimestamp("created_at").toInstant());
                        boolean accepted = peer.applyEvent(event);
                        mark(c, event.eventId(), accepted ? "SYNCED" : "FAILED");
                    }
                }
            }
        } catch (Exception ignored) {
            // Pending events remain visible and are retried on the next cycle.
        }
    }

    private void mark(Connection c, UUID eventId, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sync_events SET sync_status=?,retry_count=retry_count+1,synced_at=CASE WHEN ?='SYNCED' THEN now() ELSE synced_at END WHERE event_id=?")) {
            ps.setString(1, status); ps.setString(2, status); ps.setObject(3, eventId); ps.executeUpdate();
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}
