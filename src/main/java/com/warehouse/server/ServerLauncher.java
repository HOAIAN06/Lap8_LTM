package com.warehouse.server;

import com.warehouse.common.rmi.RmiNames;
import com.warehouse.common.rmi.LockService;
import com.warehouse.server.config.ServerConfig;
import com.warehouse.server.lock.LockManager;
import com.warehouse.server.dao.DatabaseConnection;
import com.warehouse.server.dao.ProductRepository;
import com.warehouse.server.service.WarehouseServiceImpl;
import com.warehouse.server.sync.SyncServiceImpl;
import com.warehouse.server.sync.OutboxDispatcher;

import java.io.IOException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.RemoteException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ServerLauncher {
    private static final Logger LOG = Logger.getLogger(ServerLauncher.class.getName());

    public static void main(String[] args) throws Exception {
        String configPath = args.length == 1 ? args[0] : System.getProperty("warehouse.config");
        if (configPath == null || configPath.isBlank()) {
            System.err.println("Usage: ServerLauncher <server.properties> or -Dwarehouse.config=<path>");
            return;
        }
        ServerConfig config = ServerConfig.load(configPath);
        System.setProperty("java.rmi.server.hostname", config.serverHost());
        DatabaseConnection database = new DatabaseConnection(config.dbUrl(), config.dbUsername(), config.dbPassword());
        Registry registry = createRegistry(config.registryPort());
        LockService lockService;
        if ("server-a".equalsIgnoreCase(config.serverId())) {
            lockService = new LockManager(config.leaseSeconds());
            registry.rebind(RmiNames.LOCK, lockService);
        } else {
            Registry coordinator = LocateRegistry.getRegistry(config.coordinatorHost(), config.coordinatorPort());
            lockService = (LockService) coordinator.lookup(RmiNames.LOCK);
        }
        WarehouseServiceImpl warehouse = new WarehouseServiceImpl(database.dataSource(), lockService,
                config.serverId(), config.servicePort());
        SyncServiceImpl sync = new SyncServiceImpl(database.dataSource(), config.serverId(), config.servicePort() + 1);
        registry.rebind(RmiNames.WAREHOUSE, warehouse);
        registry.rebind(RmiNames.LOCK, lockService);
        registry.rebind(RmiNames.SYNC, sync);
        Registry peerRegistry = LocateRegistry.getRegistry(config.peerHost(), config.peerPort());
        OutboxDispatcher dispatcher = new OutboxDispatcher(database.dataSource(), config.serverId(), peerRegistry);
        dispatcher.start();
        LOG.info(() -> "Started " + config.serverId() + " at " + config.serverHost()
                + ":" + config.registryPort());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            dispatcher.close();
            database.close();
        }));
        Thread.currentThread().join();
    }

    private static Registry createRegistry(int port) throws RemoteException {
        try {
            return LocateRegistry.createRegistry(port);
        } catch (ExportException alreadyRunning) {
            return LocateRegistry.getRegistry(port);
        }
    }
}
