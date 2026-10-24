package com.warehouse.server.service;

import com.warehouse.common.dto.ProductInput;
import com.warehouse.common.exception.WarehouseException;
import com.warehouse.common.model.InventoryTransaction;
import com.warehouse.common.model.Product;
import com.warehouse.common.rmi.LockService;
import com.warehouse.common.rmi.RmiNames;
import com.warehouse.common.rmi.SyncService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DirectRmiSyncIntegrationTest {

    @Test
    void rejectsWriteOperationWhenPeerIsDisconnected() throws Exception {
        AtomicBoolean rolledBack = new AtomicBoolean(false);

        // Mock statement for product insertion
        PreparedStatement stmt = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "executeUpdate" -> 1;
                    case "close", "setString", "setBigDecimal" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        ResultSet productRs = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> true;
                    case "getString" -> "20000000-0000-4000-8000-000000000001";
                    case "getBigDecimal" -> BigDecimal.valueOf(5000);
                    case "getInt" -> 10;
                    case "getLong" -> 0L;
                    case "getBoolean" -> false;
                    case "getTimestamp" -> new Timestamp(System.currentTimeMillis());
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        PreparedStatement findStmt = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "close" -> null;
                    case "executeQuery" -> productRs;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "close" -> null;
                    case "rollback" -> {
                        rolledBack.set(true);
                        yield null;
                    }
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        if (sql.contains("INSERT INTO products")) yield stmt;
                        if (sql.contains("SELECT * FROM products")) yield findStmt;
                        throw new UnsupportedOperationException("Unexpected statement: " + sql);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        DataSource dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{DataSource.class}, (proxy, method, args) -> "getConnection".equals(method.getName()) ? connection : null);

        // peerRegistry is null -> peer is disconnected!
        WarehouseServiceImpl service = new WarehouseServiceImpl(dataSource, null, "server-a", 0, null);
        try {
            ProductInput input = new ProductInput("SP999", "Bút bi xanh", "Văn phòng phẩm", BigDecimal.valueOf(5000));
            WarehouseException ex = assertThrows(WarehouseException.class,
                    () -> service.createProduct(input, "client-a", "staff_a"));

            assertEquals("Không thể cập nhật dữ liệu vì Server còn lại đang mất kết nối.", ex.getMessage());
            assertTrue(rolledBack.get(), "Local transaction must be rolled back when peer is disconnected");
        } finally {
            UnicastRemoteObject.unexportObject(service, true);
        }
    }

    @Test
    void synchronizesDirectlyToPeerWhenPeerIsReachable() throws Exception {
        AtomicBoolean committed = new AtomicBoolean(false);
        AtomicBoolean peerCreateCalled = new AtomicBoolean(false);

        PreparedStatement stmt = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "executeUpdate" -> 1;
                    case "close", "setString", "setBigDecimal" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        ResultSet productRs = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> true;
                    case "getString" -> "20000000-0000-4000-8000-000000000001";
                    case "getBigDecimal" -> BigDecimal.valueOf(5000);
                    case "getInt" -> 10;
                    case "getLong" -> 0L;
                    case "getBoolean" -> false;
                    case "getTimestamp" -> new Timestamp(System.currentTimeMillis());
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        PreparedStatement findStmt = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "close" -> null;
                    case "executeQuery" -> productRs;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "close" -> null;
                    case "commit" -> {
                        committed.set(true);
                        yield null;
                    }
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        if (sql.contains("INSERT INTO products")) yield stmt;
                        if (sql.contains("SELECT * FROM products")) yield findStmt;
                        throw new UnsupportedOperationException("Unexpected statement: " + sql);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        DataSource dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{DataSource.class}, (proxy, method, args) -> "getConnection".equals(method.getName()) ? connection : null);

        // Mock peer SyncService
        SyncService peerSync = (SyncService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{SyncService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "ping" -> true;
                    case "syncCreateProduct" -> {
                        peerCreateCalled.set(true);
                        yield true;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        Registry peerRegistry = (Registry) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{Registry.class}, (proxy, method, args) -> {
                    if ("lookup".equals(method.getName()) && RmiNames.SYNC.equals(args[0])) {
                        return peerSync;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        WarehouseServiceImpl service = new WarehouseServiceImpl(dataSource, null, "server-a", 0, peerRegistry);
        try {
            assertTrue(service.isPeerAvailable(), "Peer should be reported as available");

            ProductInput input = new ProductInput("SP999", "Bút bi xanh", "Văn phòng phẩm", BigDecimal.valueOf(5000));
            Product created = service.createProduct(input, "client-a", "staff_a");

            assertNotNull(created);
            assertTrue(peerCreateCalled.get(), "Must invoke syncCreateProduct directly on peer");
            assertTrue(committed.get(), "Must commit local transaction when peer synchronization succeeds");
        } finally {
            UnicastRemoteObject.unexportObject(service, true);
        }
    }
}
