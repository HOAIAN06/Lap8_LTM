package com.warehouse.client.rmi;

import com.warehouse.common.rmi.LockService;
import com.warehouse.common.rmi.WarehouseService;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public record WarehouseClient(WarehouseService warehouse, LockService locks) {
    public static WarehouseClient connect(String host, int port) throws Exception {
        Registry registry = LocateRegistry.getRegistry(host, port);
        return new WarehouseClient((WarehouseService) registry.lookup("warehouse"),
                (LockService) registry.lookup("lock"));
    }
}
