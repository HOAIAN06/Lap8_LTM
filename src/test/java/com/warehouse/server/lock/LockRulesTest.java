package com.warehouse.server.lock;

import com.warehouse.common.dto.LockLease;
import com.warehouse.common.exception.WarehouseException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LockRulesTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private LockLease lease(UUID product, String owner, long fence, Instant expiry) {
        return new LockLease(product, owner, UUID.randomUUID(), fence, NOW, expiry);
    }

    @Test void deniesAnotherClientOnSameProduct() {
        LockLease current = lease(UUID.randomUUID(), "client-a", 1, NOW.plusSeconds(30));
        assertThrows(WarehouseException.class, () -> LockRules.ensureAvailable(current, "client-b", NOW));
    }

    @Test void allowsDifferentProductsIndependently() throws WarehouseException {
        LockLease first = lease(UUID.randomUUID(), "client-a", 1, NOW.plusSeconds(30));
        UUID secondProduct = UUID.randomUUID();
        assertNotEquals(first.resourceId(), secondProduct);
        assertDoesNotThrow(() -> LockRules.ensureAvailable(null, "client-b", NOW));
    }

    @Test void expiredLeaseCanBeReplaced() {
        LockLease expired = lease(UUID.randomUUID(), "client-a", 1, NOW);
        assertDoesNotThrow(() -> LockRules.ensureAvailable(expired, "client-b", NOW));
        assertFalse(LockRules.valid(expired, expired.resourceId(), expired.lockToken(), 1, "client-a", NOW));
    }

    @Test void rejectsWrongTokenOrFence() {
        LockLease current = lease(UUID.randomUUID(), "client-a", 4, NOW.plusSeconds(30));
        assertFalse(LockRules.valid(current, current.resourceId(), UUID.randomUUID(), 4, "client-a", NOW));
        assertFalse(LockRules.valid(current, current.resourceId(), current.lockToken(), 3, "client-a", NOW));
        assertTrue(LockRules.valid(current, current.resourceId(), current.lockToken(), 4, "client-a", NOW));
    }

    @Test void persistedFenceContinuesAfterRestart() {
        long persisted = 41;
        assertEquals(42, LockRules.nextFencingToken(persisted));
        assertEquals(43, LockRules.nextFencingToken(42));
    }
}
