package com.warehouse.common.rmi;

import com.warehouse.common.dto.LockLease;
import com.warehouse.common.exception.WarehouseException;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.UUID;

public interface LockService extends Remote {
    LockLease acquire(UUID resourceId, String clientId) throws RemoteException;
    LockLease heartbeat(UUID lockToken, String clientId) throws RemoteException;
    void release(UUID lockToken, String clientId) throws RemoteException;
    boolean validate(UUID resourceId, UUID lockToken, long fencingToken, String clientId)
            throws RemoteException;
}
