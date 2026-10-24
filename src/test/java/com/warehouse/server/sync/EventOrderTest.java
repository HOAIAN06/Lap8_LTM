package com.warehouse.server.sync;

import org.junit.jupiter.api.Test;

import static com.warehouse.server.sync.EventOrder.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

class EventOrderTest {
    @Test void insertsNewProductAtVersionZero() {
        assertEquals(APPLY, EventOrder.decide(null, 0, "INSERT"));
    }

    @Test void defersUpdateUntilProductExists() {
        assertEquals(DEFER, EventOrder.decide(null, 1, "UPDATE"));
    }

    @Test void appliesNextVersion() {
        assertEquals(APPLY, EventOrder.decide(3L, 4, "EXPORT"));
    }

    @Test void defersVersionGap() {
        assertEquals(DEFER, EventOrder.decide(3L, 5, "IMPORT"));
    }

    @Test void rejectsOldOrEqualVersion() {
        assertEquals(CONFLICT, EventOrder.decide(3L, 2, "UPDATE"));
        assertEquals(CONFLICT, EventOrder.decide(3L, 3, "UPDATE"));
    }
}
