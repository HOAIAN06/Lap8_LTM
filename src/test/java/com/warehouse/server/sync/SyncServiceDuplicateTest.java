package com.warehouse.server.sync;

import com.warehouse.common.dto.SyncEvent;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SyncServiceDuplicateTest {
    @Test void acknowledgesAlreadyProcessedEventWithoutApplyingItAgain() throws Exception {
        AtomicBoolean rolledBack = new AtomicBoolean();
        PreparedStatement insert = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "executeUpdate" -> 0; // processed_events already contains this event_id
                    case "close", "setString" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> insert;
                    case "rollback" -> { rolledBack.set(true); yield null; }
                    case "setAutoCommit", "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) return connection;
                    throw new UnsupportedOperationException(method.getName());
                });
        UUID productId = UUID.randomUUID();
        var product = new SyncPayloadCodec.ProductState(productId, "SP011", "Sổ", "Giấy",
                BigDecimal.ONE, 0, 0, 0, false);
        var event = new SyncEvent(UUID.randomUUID(), "A", "PRODUCT", productId, "INSERT",
                SyncPayloadCodec.encode(new SyncPayloadCodec.Payload(product, null)),
                0, "PENDING", 0, Instant.now());
        SyncServiceImpl service = new SyncServiceImpl(dataSource, "server-b", 0);
        try {
            assertTrue(service.applyEvent(event));
            assertTrue(rolledBack.get());
        } finally {
            UnicastRemoteObject.unexportObject(service, true);
        }
    }
}
