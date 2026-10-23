package com.warehouse.common.rmi;

import com.warehouse.common.dto.SyncEvent;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface SyncService extends Remote {
    boolean applyEvent(SyncEvent event) throws RemoteException;
}
