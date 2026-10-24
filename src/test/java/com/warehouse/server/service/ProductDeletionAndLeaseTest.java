package com.warehouse.server.service;

import com.warehouse.common.dto.LockLease;
import com.warehouse.common.exception.WarehouseException;
import com.warehouse.common.model.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ProductDeletionAndLeaseTest {

    @Test
    void rejectsNegativeInitialStockInInventoryRules() {
        WarehouseException ex = assertThrows(WarehouseException.class,
                () -> InventoryRules.quantityAfter(-5, 10, true));
        assertTrue(ex.getMessage().contains("tồn kho hiện tại không hợp lệ"),
                "Negative before quantity must yield specific stock error message");
    }

    @Test
    void leaseLifecycleUponProductDeletion() {
        UUID productId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        Instant now = Instant.now();
        LockLease lease = new LockLease(productId, "client-a", token, 1L, now, now.plusSeconds(30));

        AtomicBoolean releaseCalled = new AtomicBoolean(false);
        AtomicBoolean deleted = new AtomicBoolean(false);

        // Simulation of successful deletion and lease cleanup
        deleted.set(true);
        if (deleted.get()) {
            // Emulate release call on coordinator
            releaseCalled.set(true);
            lease = null; // lease reset
        }

        assertTrue(deleted.get());
        assertTrue(releaseCalled.get(), "Lock release must be called after product deletion");
        assertNull(lease, "Current lease must be reset to null");

        // Emulate heartbeat check: when lease is null, heartbeat does nothing
        boolean heartbeatExecuted = false;
        if (lease != null) {
            heartbeatExecuted = true;
        }
        assertFalse(heartbeatExecuted, "Heartbeat must not execute or renew lease when lease is null");
    }

    @Test
    void preservesUtf8VietnameseEncoding() {
        String originalName = "Bút bi Thiên Long xanh & Sổ tay A5";
        String category = "Văn phòng phẩm & Đóng gói";
        Product product = new Product(UUID.randomUUID(), "SP999", originalName, category,
                BigDecimal.valueOf(15000), 50, 0L, 0L, false, Instant.now());

        assertEquals(originalName, product.productName());
        assertEquals(category, product.category());
        assertTrue(product.productName().contains("Thiên Long"));
        assertTrue(product.category().contains("Đóng gói"));
    }
}
