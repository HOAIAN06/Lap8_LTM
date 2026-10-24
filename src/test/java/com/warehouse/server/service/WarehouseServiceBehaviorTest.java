package com.warehouse.server.service;

import com.warehouse.common.dto.ProductInput;
import com.warehouse.common.exception.WarehouseException;
import com.warehouse.common.rmi.LockService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WarehouseServiceBehaviorTest {

    @Test
    void rejectsDuplicateProductCodeWithFriendlyMessage() throws Exception {
        AtomicBoolean rolledBack = new AtomicBoolean(false);

        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "close" -> null;
                    case "rollback" -> {
                        rolledBack.set(true);
                        yield null;
                    }
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        if (sql.contains("INSERT INTO products")) {
                            throw new SQLException("Duplicate entry 'SP001' for key 'product_code'", "23000", 1062);
                        }
                        throw new UnsupportedOperationException("Unexpected query: " + sql);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{DataSource.class},
                (proxy, method, args) -> "getConnection".equals(method.getName()) ? connection : null);

        WarehouseServiceImpl service = new WarehouseServiceImpl(dataSource, null, "server-a", 0, null);
        try {
            ProductInput input = new ProductInput("SP001", "Bút bi xanh", "Văn phòng phẩm", BigDecimal.valueOf(5000));
            WarehouseException ex = assertThrows(WarehouseException.class,
                    () -> service.createProduct(input, "client-a", "staff_a"));

            assertEquals("Mã sản phẩm đã tồn tại.", ex.getMessage());
            assertTrue(rolledBack.get(), "Transaction must be rolled back on duplicate key");
        } finally {
            UnicastRemoteObject.unexportObject(service, true);
        }
    }

    @Test
    void rejectsInventoryUpdateAndDoesNotCreateTransactionWhenProductIsDeleted() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID lockToken = UUID.randomUUID();
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        List<String> executedQueries = new ArrayList<>();

        LockService lockService = (LockService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{LockService.class},
                (proxy, method, args) -> "validate".equals(method.getName()) ? true : null);

        // Mock ResultSet representing an already soft-deleted product
        ResultSet productRow = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> true;
                    case "getString" -> switch ((String) args[0]) {
                        case "id" -> productId.toString();
                        case "product_code" -> "SP001";
                        case "product_name" -> "Bút";
                        case "category" -> "Văn phòng";
                        default -> "";
                    };
                    case "getBigDecimal" -> BigDecimal.valueOf(5000);
                    case "getInt" -> "quantity".equals(args[0]) ? 10 : 0;
                    case "getLong" -> 1L;
                    case "getBoolean" -> "is_deleted".equals(args[0]) ? true : false; // DELETED!
                    case "getTimestamp" -> new Timestamp(System.currentTimeMillis());
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        PreparedStatement findStatement = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "close" -> null;
                    case "executeQuery" -> productRow;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "close" -> null;
                    case "rollback" -> {
                        rolledBack.set(true);
                        yield null;
                    }
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        executedQueries.add(sql);
                        if (sql.contains("SELECT * FROM products")) yield findStatement;
                        throw new UnsupportedOperationException("Unexpected statement: " + sql);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{DataSource.class},
                (proxy, method, args) -> "getConnection".equals(method.getName()) ? connection : null);

        WarehouseServiceImpl service = new WarehouseServiceImpl(dataSource, lockService, "server-a", 0, null);
        try {
            WarehouseException ex = assertThrows(WarehouseException.class,
                    () -> service.changeInventory(productId, 5, true, lockToken, 1L, "client-a", "staff_a"));

            assertEquals("Không tìm thấy sản phẩm.", ex.getMessage());
            assertTrue(rolledBack.get(), "Transaction must rollback when product is deleted");

            // Ensure no insert into inventory_transactions or sync_events
            for (String sql : executedQueries) {
                assertFalse(sql.contains("inventory_transactions"), "Must not create inventory transaction");
                assertFalse(sql.contains("sync_events"), "Must not create sync event");
            }
        } finally {
            UnicastRemoteObject.unexportObject(service, true);
        }
    }

    @Test
    void rejectsUpdateAndRollsBackWhenAffectedRowsIsZero() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID lockToken = UUID.randomUUID();
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        List<String> executedStatements = new ArrayList<>();

        LockService lockService = (LockService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{LockService.class},
                (proxy, method, args) -> "validate".equals(method.getName()) ? true : null);

        // Product find returns active product initially
        ResultSet productRow = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> true;
                    case "getString" -> switch ((String) args[0]) {
                        case "id" -> productId.toString();
                        case "product_code" -> "SP001";
                        case "product_name" -> "Bút";
                        case "category" -> "Văn phòng";
                        default -> "";
                    };
                    case "getBigDecimal" -> BigDecimal.valueOf(5000);
                    case "getInt" -> "quantity".equals(args[0]) ? 10 : 0;
                    case "getLong" -> 1L;
                    case "getBoolean" -> false; // not deleted initially
                    case "getTimestamp" -> new Timestamp(System.currentTimeMillis());
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        PreparedStatement findStmt = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "close" -> null;
                    case "executeQuery" -> productRow;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        // UPDATE products returns 0 affected rows (e.g. concurrent soft deletion or stale fencing)
        PreparedStatement updateStmt = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setInt", "setLong", "setString", "close" -> null;
                    case "executeUpdate" -> 0; // 0 rows affected!
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "close" -> null;
                    case "rollback" -> {
                        rolledBack.set(true);
                        yield null;
                    }
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        executedStatements.add(sql);
                        if (sql.contains("SELECT * FROM products")) yield findStmt;
                        if (sql.contains("UPDATE products")) yield updateStmt;
                        throw new UnsupportedOperationException("Unexpected query: " + sql);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{DataSource.class},
                (proxy, method, args) -> "getConnection".equals(method.getName()) ? connection : null);

        WarehouseServiceImpl service = new WarehouseServiceImpl(dataSource, lockService, "server-a", 0, null);
        try {
            WarehouseException ex = assertThrows(WarehouseException.class,
                    () -> service.changeInventory(productId, 5, true, lockToken, 1L, "client-a", "staff_a"));

            assertTrue(ex.getMessage().contains("sản phẩm đã bị xóa hoặc khóa không hợp lệ")
                    || ex.getMessage().contains("Khóa đã hết hạn, bị thay thế hoặc sản phẩm đã bị xóa."));
            assertTrue(rolledBack.get(), "Transaction must rollback when UPDATE affects 0 rows");

            for (String sql : executedStatements) {
                assertFalse(sql.contains("INSERT INTO inventory_transactions"), "No transaction record when update fails");
                assertFalse(sql.contains("INSERT INTO sync_events"), "No sync event when update fails");
            }
        } finally {
            UnicastRemoteObject.unexportObject(service, true);
        }
    }
}
