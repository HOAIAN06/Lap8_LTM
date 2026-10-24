package com.warehouse.server.lock;

import com.warehouse.common.dto.LockLease;
import com.warehouse.common.exception.WarehouseException;
import com.warehouse.common.rmi.LockService;
import com.warehouse.common.rmi.RmiNames;

import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resilient LockService proxy for Server B that delegates to Server A (Lock Coordinator)
 * with automatic reconnection upon coordinator restart or failure.
 */
public class ReconnectingLockService extends UnicastRemoteObject implements LockService {
    @FunctionalInterface
    public interface CoordinatorLookup {
        LockService lookup(String host, int port) throws Exception;
    }

    private static final CoordinatorLookup DEFAULT_LOOKUP = (host, port) -> {
        Registry registry = LocateRegistry.getRegistry(host, port);
        return (LockService) registry.lookup(RmiNames.LOCK);
    };

    private final AtomicReference<String> coordinatorHost;
    private final int coordinatorPort;
    private final CoordinatorLookup lookupFunction;
    private volatile LockService cachedStub;
    private volatile boolean coordinatorAvailable;

    public ReconnectingLockService(String coordinatorHost, int coordinatorPort, int servicePort)
            throws RemoteException {
        this(coordinatorHost, coordinatorPort, servicePort, DEFAULT_LOOKUP, null);
    }

    public ReconnectingLockService(String coordinatorHost, int coordinatorPort, int servicePort,
                                   CoordinatorLookup lookupFunction, LockService initialStub)
            throws RemoteException {
        super(servicePort);
        this.coordinatorHost = new AtomicReference<>(coordinatorHost == null ? "" : coordinatorHost.trim());
        this.coordinatorPort = coordinatorPort;
        this.lookupFunction = lookupFunction != null ? lookupFunction : DEFAULT_LOOKUP;
        this.cachedStub = initialStub;
        this.coordinatorAvailable = (initialStub != null);
    }

    public void updateCoordinatorHost(String newHost) {
        if (newHost != null && !newHost.isBlank()) {
            coordinatorHost.set(newHost.trim());
            synchronized (this) {
                cachedStub = null;
                coordinatorAvailable = false;
            }
        }
    }

    public boolean isCoordinatorAvailable() {
        return coordinatorAvailable;
    }

    public synchronized LockService getOrConnectCoordinator() throws RemoteException {
        if (cachedStub != null && coordinatorAvailable) {
            return cachedStub;
        }
        String host = coordinatorHost.get();
        if (host.isBlank()) {
            coordinatorAvailable = false;
            throw new WarehouseException("Địa chỉ Lock Coordinator chưa được cấu hình.");
        }
        try {
            LockService stub = lookupFunction.lookup(host, coordinatorPort);
            cachedStub = stub;
            coordinatorAvailable = true;
            return stub;
        } catch (Exception e) {
            cachedStub = null;
            coordinatorAvailable = false;
            throw new WarehouseException("Không thể kết nối đến Lock Coordinator tại " + host + ":" + coordinatorPort + ".", e);
        }
    }

    @Override
    public LockLease acquire(UUID resourceId, String clientId) throws RemoteException {
        return execute(service -> service.acquire(resourceId, clientId), false);
    }

    @Override
    public LockLease heartbeat(UUID lockToken, String clientId) throws RemoteException {
        return execute(service -> service.heartbeat(lockToken, clientId), false);
    }

    @Override
    public void release(UUID lockToken, String clientId) throws RemoteException {
        execute(service -> {
            service.release(lockToken, clientId);
            return null;
        }, false);
    }

    @Override
    public boolean validate(UUID resourceId, UUID lockToken, long fencingToken, String clientId)
            throws RemoteException {
        return execute(service -> service.validate(resourceId, lockToken, fencingToken, clientId), true);
    }

    private <T> T execute(LockOperation<T> op, boolean isReadOnly) throws RemoteException {
        LockService stub = getOrConnectCoordinator();
        try {
            return op.execute(stub);
        } catch (NoSuchObjectException e) {
            // Coordinator restarted - old stub is rejected before server execution
            synchronized (this) {
                cachedStub = null;
                coordinatorAvailable = false;
            }
            LockService fresh = getOrConnectCoordinator();
            try {
                return op.execute(fresh);
            } catch (RemoteException retryEx) {
                synchronized (this) {
                    cachedStub = null;
                    coordinatorAvailable = false;
                }
                throw new WarehouseException("Không thể kết nối lại Lock Coordinator sau khi khởi động lại: "
                        + retryEx.getMessage(), retryEx);
            }
        } catch (RemoteException e) {
            synchronized (this) {
                cachedStub = null;
                coordinatorAvailable = false;
            }
            if (isReadOnly) {
                // Idempotent validation can be safely retried once
                LockService fresh = getOrConnectCoordinator();
                return op.execute(fresh);
            }
            // State-modifying operations are not blindly retried if execution outcome is unknown
            throw new WarehouseException("Mất kết nối tới Lock Coordinator (không tự động gửi lại thao tác ghi): "
                    + e.getMessage(), e);
        }
    }

    public void setCachedStubForTest(LockService stub) {
        synchronized (this) {
            this.cachedStub = stub;
            this.coordinatorAvailable = (stub != null);
        }
    }

    @FunctionalInterface
    private interface LockOperation<T> {
        T execute(LockService service) throws RemoteException;
    }
}
