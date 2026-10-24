package com.warehouse.server.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyncValuesTest {
    @Test void mapsServerIdsToDatabaseEnum() {
        assertEquals("A", SyncValues.origin("server-a"));
        assertEquals("B", SyncValues.origin("server-b"));
        assertEquals("A", SyncValues.origin("A"));
    }

    @Test void rejectsUnknownServerId() {
        assertThrows(IllegalArgumentException.class, () -> SyncValues.origin("server-c"));
    }

    @Test void mapsCreateToInsert() {
        assertEquals("INSERT", SyncValues.action("CREATE"));
        assertEquals("INSERT", SyncValues.action("INSERT"));
    }

    @Test void acceptsOnlySchemaActions() {
        for (String action : new String[]{"UPDATE", "DELETE", "IMPORT", "EXPORT"}) {
            assertEquals(action, SyncValues.action(action));
        }
        assertThrows(IllegalArgumentException.class, () -> SyncValues.action("UPSERT"));
    }

    @Test void mapsInventoryTypes() {
        assertEquals("IMPORT", SyncValues.transactionType(true));
        assertEquals("EXPORT", SyncValues.transactionType(false));
    }
}
