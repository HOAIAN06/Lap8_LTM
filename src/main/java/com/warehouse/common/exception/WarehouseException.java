package com.warehouse.common.exception;

import java.rmi.RemoteException;

public class WarehouseException extends RemoteException {
    public WarehouseException(String message) {
        super(message);
    }

    public WarehouseException(String message, Throwable cause) {
        super(message, cause);
    }
}
