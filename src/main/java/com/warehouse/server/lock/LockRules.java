package com.warehouse.server.lock;

import com.warehouse.common.dto.LockLease;
import com.warehouse.common.exception.WarehouseException;

import java.time.Instant;
import java.util.UUID;

/** Pure lease rules, shared by JDBC coordination and database-free tests. */
public final class LockRules {
    private LockRules() { }

    public static void ensureAvailable(LockLease current, String clientId, Instant now)
            throws WarehouseException {
        if (current != null && current.expiresAt().isAfter(now)
                && !current.ownerClientId().equals(clientId)) {
            throw new WarehouseException("Sản phẩm đang được nhân viên khác chỉnh sửa. Vui lòng thử lại sau.");
        }
    }

    public static boolean valid(LockLease lease, UUID resourceId, UUID token, long fencingToken,
                                String clientId, Instant now) {
        return lease != null && lease.expiresAt().isAfter(now)
                && lease.resourceId().equals(resourceId) && lease.lockToken().equals(token)
                && lease.fencingToken() == fencingToken && lease.ownerClientId().equals(clientId);
    }

    public static long nextFencingToken(long persisted) {
        if (persisted < 0 || persisted == Long.MAX_VALUE) {
            throw new IllegalStateException("Fencing token exhausted or invalid");
        }
        return persisted + 1;
    }
}
