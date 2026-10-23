package com.warehouse.server.lock;

import com.warehouse.common.dto.LockLease;
import com.warehouse.common.exception.WarehouseException;
import com.warehouse.common.rmi.LockService;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LockManager extends UnicastRemoteObject implements LockService {
    private final Map<UUID, LockLease> locks = new ConcurrentHashMap<>();
    private final AtomicLong fencing = new AtomicLong();
    private final int leaseSeconds;

    public LockManager(int leaseSeconds) throws RemoteException {
        super();
        this.leaseSeconds = leaseSeconds;
    }

    @Override
    public synchronized LockLease acquire(UUID resourceId, String clientId) throws WarehouseException {
        LockLease current = locks.get(resourceId);
        if (current != null && current.expiresAt().isAfter(Instant.now())
                && !current.ownerClientId().equals(clientId)) {
            throw new WarehouseException("Sản phẩm đang được nhân viên khác chỉnh sửa. Vui lòng thử lại sau.");
        }
        Instant now = Instant.now();
        LockLease lease = new LockLease(resourceId, clientId, UUID.randomUUID(), fencing.incrementAndGet(),
                now, now.plusSeconds(leaseSeconds));
        locks.put(resourceId, lease);
        return lease;
    }

    @Override
    public synchronized LockLease heartbeat(UUID token, String clientId) throws WarehouseException {
        LockLease current = byToken(token);
        if (current == null || !current.ownerClientId().equals(clientId) || expired(current)) {
            throw new WarehouseException("Lock token không hợp lệ hoặc đã hết hạn.");
        }
        LockLease refreshed = new LockLease(current.resourceId(), clientId, token, current.fencingToken(),
                current.acquiredAt(), Instant.now().plusSeconds(leaseSeconds));
        locks.put(current.resourceId(), refreshed);
        return refreshed;
    }

    @Override
    public synchronized void release(UUID token, String clientId) throws WarehouseException {
        LockLease current = byToken(token);
        if (current == null || !current.ownerClientId().equals(clientId)) {
            throw new WarehouseException("Không thể giải phóng lock không thuộc client.");
        }
        locks.remove(current.resourceId());
    }

    @Override
    public synchronized boolean validate(UUID resourceId, UUID token, long tokenValue, String clientId) {
        LockLease current = locks.get(resourceId);
        return current != null && !expired(current) && current.lockToken().equals(token)
                && current.fencingToken() == tokenValue && current.ownerClientId().equals(clientId);
    }

    private LockLease byToken(UUID token) {
        return locks.values().stream().filter(lock -> lock.lockToken().equals(token)).findFirst().orElse(null);
    }

    private boolean expired(LockLease lease) {
        if (lease.expiresAt().isAfter(Instant.now())) return false;
        locks.remove(lease.resourceId());
        return true;
    }
}
