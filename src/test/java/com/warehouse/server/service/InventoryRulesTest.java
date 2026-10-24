package com.warehouse.server.service;

import com.warehouse.common.exception.WarehouseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventoryRulesTest {
    @Test void importsAndExports() throws WarehouseException {
        assertEquals(30, InventoryRules.quantityAfter(20, 10, true));
        assertEquals(25, InventoryRules.quantityAfter(30, 5, false));
    }

    @Test void rejectsZeroAndNegativeAmount() {
        assertThrows(WarehouseException.class, () -> InventoryRules.quantityAfter(20, 0, true));
        assertThrows(WarehouseException.class, () -> InventoryRules.quantityAfter(20, -2, false));
    }

    @Test void rejectsOverstockExport() {
        assertThrows(WarehouseException.class, () -> InventoryRules.quantityAfter(4, 5, false));
    }

    @Test void rejectsIntegerOverflow() {
        assertThrows(WarehouseException.class,
                () -> InventoryRules.quantityAfter(Integer.MAX_VALUE, 1, true));
    }
}
