package com.warehouse.server.sync;

import com.warehouse.common.model.InventoryTransaction;
import com.warehouse.common.model.Product;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SyncServiceDuplicateTest {

    @Test
    void directSyncMethodsExecuteProperJdbcStatements() throws Exception {
        List<String> executedStatements = new ArrayList<>();

        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "executeUpdate" -> 1;
                    case "close", "setString", "setBigDecimal", "setInt", "setLong", "setBoolean", "setTimestamp" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        executedStatements.add((String) args[0]);
                        yield statement;
                    }
                    case "setAutoCommit", "commit", "rollback", "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        DataSource dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) return connection;
                    throw new UnsupportedOperationException(method.getName());
                });

        SyncServiceImpl service = new SyncServiceImpl(dataSource, "server-b", 0);
        try {
            assertTrue(service.ping());

            UUID productId = UUID.randomUUID();
            Product product = new Product(productId, "SP011", "Sổ tay", "Giấy",
                    BigDecimal.valueOf(10000), 10, 1L, 1L, false, Instant.now());

            assertTrue(service.syncCreateProduct(product));
            assertTrue(service.syncUpdateProduct(product));
            assertTrue(service.syncDeleteProduct(productId, 2L));

            InventoryTransaction tx = new InventoryTransaction(UUID.randomUUID(), "TX-01", productId,
                    "IMPORT", 5, 10, 15, "staff_a", Instant.now());
            assertTrue(service.syncInventory(productId, 15, 2L, tx));

            boolean hasProductInsert = executedStatements.stream().anyMatch(s -> s.contains("INSERT INTO products"));
            boolean hasProductUpdate = executedStatements.stream().anyMatch(s -> s.contains("UPDATE products"));
            boolean hasInventoryInsert = executedStatements.stream().anyMatch(s -> s.contains("inventory_transactions"));

            assertTrue(hasProductInsert, "Must execute product insert/upsert");
            assertTrue(hasProductUpdate, "Must execute product update");
            assertTrue(hasInventoryInsert, "Must execute inventory transaction insert");
        } finally {
            UnicastRemoteObject.unexportObject(service, true);
        }
    }
}
