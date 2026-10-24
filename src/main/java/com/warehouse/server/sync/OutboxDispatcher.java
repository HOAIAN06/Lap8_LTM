package com.warehouse.server.sync;

import com.warehouse.common.dto.SyncEvent;
import com.warehouse.common.rmi.RmiNames;
import com.warehouse.common.rmi.SyncService;

import javax.sql.DataSource;
import java.rmi.registry.Registry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @deprecated Đã thay thế bằng Direct RMI Sync (đồng bộ trực tiếp qua SyncService RMI).
 * Không còn được khởi chạy trong ServerLauncher.
 */
@Deprecated
public final class OutboxDispatcher implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(OutboxDispatcher.class.getName());
    private final DataSource dataSource;
    private final String origin;
    private final Registry peerRegistry;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public OutboxDispatcher(DataSource dataSource, String serverId, Registry peerRegistry) {
        this.dataSource = dataSource;
        this.origin = SyncValues.origin(serverId);
        this.peerRegistry = peerRegistry;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::dispatch, 2, 10, TimeUnit.SECONDS);
    }

    private void dispatch() {
        try {
            for (SyncEvent event : pendingEvents()) {
                mark(event.eventId(), "PROCESSING", null, false);
                try {
                    SyncService peer = (SyncService) peerRegistry.lookup(RmiNames.SYNC);
                    if (peer.applyEvent(event)) {
                        mark(event.eventId(), "SYNCED", null, false);
                    } else {
                        LOG.warning("Peer deferred or rejected event " + event.eventId());
                        mark(event.eventId(), "FAILED", "Peer deferred or rejected event", true);
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Sync failed for event " + event.eventId(), e);
                    mark(event.eventId(), "FAILED", e.toString(), true);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Cannot read or update sync outbox", e);
        }
    }

    private List<SyncEvent> pendingEvents() throws SQLException {
        String sql = "SELECT e.* FROM sync_events e WHERE e.origin_server=? "
                + "AND e.sync_status IN ('PENDING','FAILED','PROCESSING') "
                + "AND NOT EXISTS (SELECT 1 FROM sync_events earlier "
                + "WHERE earlier.origin_server=e.origin_server AND earlier.entity_id=e.entity_id "
                + "AND earlier.sync_status IN ('PENDING','FAILED','PROCESSING') "
                + "AND (earlier.entity_version<e.entity_version "
                + "OR (earlier.entity_version=e.entity_version AND earlier.created_at<e.created_at) "
                + "OR (earlier.entity_version=e.entity_version AND earlier.created_at=e.created_at "
                + "AND earlier.event_id<e.event_id))) "
                + "ORDER BY e.created_at,e.event_id LIMIT 20";
        List<SyncEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, origin);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    events.add(new SyncEvent(UUID.fromString(row.getString("event_id")),
                            row.getString("origin_server"), row.getString("entity_type"),
                            UUID.fromString(row.getString("entity_id")), row.getString("action"),
                            row.getString("payload"), row.getLong("entity_version"),
                            row.getString("sync_status"), row.getInt("retry_count"),
                            row.getTimestamp("created_at").toInstant()));
                }
            }
        }
        return events;
    }

    private void mark(UUID eventId, String status, String error, boolean retry) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE sync_events SET sync_status=?,last_error=?,"
                             + "retry_count=retry_count+?,"
                             + "synced_at=CASE WHEN ?='SYNCED' THEN NOW(3) ELSE synced_at END "
                             + "WHERE event_id=?")) {
            update.setString(1, status);
            update.setString(2, error == null ? null : error.substring(0, Math.min(error.length(), 2000)));
            update.setInt(3, retry ? 1 : 0);
            update.setString(4, status);
            update.setString(5, eventId.toString());
            update.executeUpdate();
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}
