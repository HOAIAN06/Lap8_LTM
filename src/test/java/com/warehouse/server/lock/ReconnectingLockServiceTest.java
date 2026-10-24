package com.warehouse.server.lock;

import com.warehouse.common.dto.LockLease;
import com.warehouse.common.exception.WarehouseException;
import com.warehouse.common.rmi.LockService;
import org.junit.jupiter.api.Test;

import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReconnectingLockServiceTest {

    @Test
    void reconnectsOnNoSuchObjectExceptionWhenCoordinatorRestarts() throws Exception {
        UUID resourceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        LockLease expectedLease = new LockLease(resourceId, "client-b", token, 1L,
                Instant.now(), Instant.now().plusSeconds(30));

        AtomicInteger callCount = new AtomicInteger(0);
        AtomicBoolean reconnected = new AtomicBoolean(false);

        LockService firstCoordinatorStub = new LockService() {
            @Override
            public LockLease acquire(UUID resourceId, String clientId) throws RemoteException {
                callCount.incrementAndGet();
                throw new NoSuchObjectException("Server A restarted; old object removed");
            }
            @Override public LockLease heartbeat(UUID lockToken, String clientId) { return null; }
            @Override public void release(UUID lockToken, String clientId) { }
            @Override public boolean validate(UUID resourceId, UUID lockToken, long fencingToken, String clientId) {
                return false;
            }
        };

        LockService secondCoordinatorStub = new LockService() {
            @Override
            public LockLease acquire(UUID resourceId, String clientId) throws RemoteException {
                callCount.incrementAndGet();
                return expectedLease;
            }
            @Override public LockLease heartbeat(UUID lockToken, String clientId) { return null; }
            @Override public void release(UUID lockToken, String clientId) { }
            @Override public boolean validate(UUID resourceId, UUID lockToken, long fencingToken, String clientId) {
                return true;
            }
        };

        ReconnectingLockService.CoordinatorLookup lookup = (host, port) -> {
            reconnected.set(true);
            return secondCoordinatorStub;
        };

        ReconnectingLockService reconnecting = new ReconnectingLockService(
                "127.0.0.1", 1099, 0, lookup, firstCoordinatorStub);

        try {
            LockLease result = reconnecting.acquire(resourceId, "client-b");
            assertNotNull(result);
            assertEquals(expectedLease, result);
            assertEquals(2, callCount.get(), "First call threw NoSuchObjectException; second call succeeded on fresh stub");
            assertTrue(reconnected.get(), "Reconnection lookup was executed");
            assertTrue(reconnecting.isCoordinatorAvailable());
        } finally {
            java.rmi.server.UnicastRemoteObject.unexportObject(reconnecting, true);
        }
    }

    @Test
    void doesNotBlindlyRetryWriteOperationOnGenericRemoteException() throws Exception {
        UUID resourceId = UUID.randomUUID();
        AtomicInteger callCount = new AtomicInteger(0);

        LockService flakyCoordinator = new LockService() {
            @Override
            public LockLease acquire(UUID resourceId, String clientId) throws RemoteException {
                callCount.incrementAndGet();
                throw new RemoteException("Connection reset mid-stream");
            }
            @Override public LockLease heartbeat(UUID lockToken, String clientId) { return null; }
            @Override public void release(UUID lockToken, String clientId) { }
            @Override public boolean validate(UUID resourceId, UUID lockToken, long fencingToken, String clientId) {
                return false;
            }
        };

        ReconnectingLockService reconnecting = new ReconnectingLockService(
                "127.0.0.1", 1099, 0, null, flakyCoordinator);
        try {
            WarehouseException ex = assertThrows(WarehouseException.class,
                    () -> reconnecting.acquire(resourceId, "client-b"));

            assertTrue(ex.getMessage().contains("không tự động gửi lại thao tác ghi"));
            assertEquals(1, callCount.get(), "Must not blindly retry in-flight write operation when outcome is uncertain");
            assertFalse(reconnecting.isCoordinatorAvailable(), "Coordinator marked unavailable");
        } finally {
            java.rmi.server.UnicastRemoteObject.unexportObject(reconnecting, true);
        }
    }

    @Test
    void retriesIdempotentValidateOnRemoteException() throws Exception {
        UUID resourceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        AtomicInteger validateCount = new AtomicInteger(0);

        LockService firstCoordinator = new LockService() {
            @Override public LockLease acquire(UUID resourceId, String clientId) { return null; }
            @Override public LockLease heartbeat(UUID lockToken, String clientId) { return null; }
            @Override public void release(UUID lockToken, String clientId) { }
            @Override
            public boolean validate(UUID resourceId, UUID lockToken, long fencingToken, String clientId)
                    throws RemoteException {
                validateCount.incrementAndGet();
                throw new RemoteException("Socket closed");
            }
        };

        LockService secondCoordinator = new LockService() {
            @Override public LockLease acquire(UUID resourceId, String clientId) { return null; }
            @Override public LockLease heartbeat(UUID lockToken, String clientId) { return null; }
            @Override public void release(UUID lockToken, String clientId) { }
            @Override
            public boolean validate(UUID resourceId, UUID lockToken, long fencingToken, String clientId) {
                validateCount.incrementAndGet();
                return true;
            }
        };

        ReconnectingLockService.CoordinatorLookup lookup = (host, port) -> secondCoordinator;

        ReconnectingLockService reconnecting = new ReconnectingLockService(
                "127.0.0.1", 1099, 0, lookup, firstCoordinator);

        try {
            boolean valid = reconnecting.validate(resourceId, token, 1L, "client-b");
            assertTrue(valid);
            assertEquals(2, validateCount.get(), "Idempotent validation safely retried on fresh coordinator stub");
        } finally {
            java.rmi.server.UnicastRemoteObject.unexportObject(reconnecting, true);
        }
    }

    @Test
    void updateCoordinatorHostResetsCachedConnection() throws Exception {
        LockService oldCoordinator = new LockService() {
            @Override public LockLease acquire(UUID resourceId, String clientId) { return null; }
            @Override public LockLease heartbeat(UUID lockToken, String clientId) { return null; }
            @Override public void release(UUID lockToken, String clientId) { }
            @Override public boolean validate(UUID r, UUID t, long f, String c) { return true; }
        };

        ReconnectingLockService reconnecting = new ReconnectingLockService(
                "192.168.1.10", 1099, 0, null, oldCoordinator);
        try {
            assertTrue(reconnecting.isCoordinatorAvailable());
            reconnecting.updateCoordinatorHost("192.168.1.20");
            assertFalse(reconnecting.isCoordinatorAvailable(), "Host update must invalidate old cached connection");
        } finally {
            java.rmi.server.UnicastRemoteObject.unexportObject(reconnecting, true);
        }
    }
}
