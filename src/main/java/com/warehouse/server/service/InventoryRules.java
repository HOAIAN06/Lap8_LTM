package com.warehouse.server.service;

import com.warehouse.common.exception.WarehouseException;

public final class InventoryRules {
    private InventoryRules() { }

    public static int quantityAfter(int before, int amount, boolean importing) throws WarehouseException {
        if (before < 0) {
            throw new WarehouseException("Số lượng tồn kho hiện tại không hợp lệ (< 0).");
        }
        if (amount <= 0) {
            throw new WarehouseException("Số lượng phải lớn hơn 0.");
        }
        if (!importing && amount > before) {
            throw new WarehouseException("Số lượng xuất vượt quá tồn kho.");
        }
        try {
            return importing ? Math.addExact(before, amount) : before - amount;
        } catch (ArithmeticException e) {
            throw new WarehouseException("Số lượng tồn kho vượt giới hạn.");
        }
    }
}
