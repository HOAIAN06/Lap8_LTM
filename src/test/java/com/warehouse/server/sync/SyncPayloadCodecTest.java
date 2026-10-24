package com.warehouse.server.sync;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SyncPayloadCodecTest {
    @Test void roundTripsProductAndInventoryHistory() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var product = new SyncPayloadCodec.ProductState(productId, "SP001", "Bút \"xanh\"",
                "Văn phòng", new BigDecimal("12000.50"), 25, 4, 9, false);
        var transaction = new SyncPayloadCodec.TransactionState(UUID.randomUUID(), "TX-001", productId,
                "EXPORT", 5, 30, 25, userId, Instant.parse("2026-01-01T01:02:03Z"));
        var payload = new SyncPayloadCodec.Payload(product, transaction);
        assertEquals(payload, SyncPayloadCodec.decode(SyncPayloadCodec.encode(payload)));
    }

    @Test void roundTripsNullCategoryWithoutTransaction() throws Exception {
        var product = new SyncPayloadCodec.ProductState(UUID.randomUUID(), "SP011", "Sổ",
                null, BigDecimal.ZERO, 0, 0, 0, false);
        var payload = new SyncPayloadCodec.Payload(product, null);
        assertEquals(payload, SyncPayloadCodec.decode(SyncPayloadCodec.encode(payload)));
    }
}
