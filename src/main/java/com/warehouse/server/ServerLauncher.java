package com.warehouse.server;

import com.warehouse.common.network.NetworkAddress;
import com.warehouse.common.rmi.LockService;
import com.warehouse.common.rmi.RmiNames;
import com.warehouse.server.config.ServerConfig;
import com.warehouse.server.dao.DatabaseConnection;
import com.warehouse.server.lock.LockManager;
import com.warehouse.server.lock.ReconnectingLockService;
import com.warehouse.server.service.WarehouseServiceImpl;
import com.warehouse.server.sync.SyncServiceImpl;
import com.warehouse.server.ui.ServerMonitorFrame;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.SQLException;
import java.util.Properties;

public final class ServerLauncher {
    private ServerLauncher() { }

    public static void main(String[] args) throws Exception {
        com.warehouse.client.ui.AppTheme.install();
        String configPath = args.length == 1 ? args[0] : System.getProperty("warehouse.config");
        if (configPath == null || configPath.isBlank()) {
            configPath = "src/main/resources/server-a.properties";
        }
        ServerConfig baseConfig = ServerConfig.load(configPath);
        Path propertiesPath = Path.of(configPath);
        Runnable show = () -> {
            ServerMonitorFrame frame = new ServerMonitorFrame(
                    baseConfig.serverId(), baseConfig.serverHost(), baseConfig.registryPort(),
                    baseConfig.servicePort(), baseConfig.peerHost(),
                    (local, peer, registryPort) ->
                            startServer(baseConfig, propertiesPath, local, peer, registryPort));
            frame.setVisible(true);
        };
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Server cần môi trường có giao diện để nhập IP và khởi động.");
        }
        SwingUtilities.invokeLater(show);
    }

    private static ServerMonitorFrame.RunningServer startServer(
            ServerConfig base, Path propertiesPath, String localAddress, String peerAddress,
            int registryPort) throws Exception {
        boolean serverA = "server-a".equalsIgnoreCase(base.serverId());
        String coordinatorAddress = serverA ? localAddress : peerAddress;
        ServerConfig config = new ServerConfig(base.serverId(), localAddress, registryPort,
                base.servicePort(), peerAddress, base.peerPort(), coordinatorAddress,
                base.coordinatorPort(), base.dbUrl(), base.dbUsername(), base.dbPassword(),
                base.leaseSeconds());
        saveNetworkDefaults(propertiesPath, localAddress, peerAddress, coordinatorAddress);
        System.setProperty("java.rmi.server.hostname", localAddress);

        DatabaseConnection database = new DatabaseConnection(config.dbUrl(),
                config.dbUsername(), config.dbPassword());
        try {
            database.verify();
            Registry registry = createRegistry(config.registryPort());
            LockService lockService;
            if (serverA) {
                lockService = new LockManager(database.dataSource(), config.leaseSeconds());
            } else {
                ReconnectingLockService reconnecting = new ReconnectingLockService(
                        config.coordinatorHost(), config.coordinatorPort(), config.servicePort() + 2);
                try {
                    reconnecting.getOrConnectCoordinator();
                } catch (Exception ignored) {
                    // Server B remains available and reconnects when a lock operation is requested.
                }
                lockService = reconnecting;
            }
            Registry peerRegistry = peerAddress.isBlank() ? null
                    : LocateRegistry.getRegistry(peerAddress, config.peerPort());
            WarehouseServiceImpl warehouse = new WarehouseServiceImpl(database.dataSource(), lockService,
                    config.serverId(), config.servicePort(), peerAddress, config.peerPort(), peerRegistry);
            SyncServiceImpl sync = new SyncServiceImpl(database.dataSource(), config.serverId(),
                    config.servicePort() + 1);
            registry.rebind(RmiNames.WAREHOUSE, warehouse);
            registry.rebind(RmiNames.LOCK, lockService);
            registry.rebind(RmiNames.SYNC, sync);
            return new RunningServerImpl(database, registry, warehouse, sync, lockService, peerRegistry);
        } catch (Exception error) {
            database.close();
            throw error;
        }
    }

    private static void saveNetworkDefaults(Path path, String local, String peer, String coordinator)
            throws Exception {
        Properties saved = new Properties();
        if (Files.exists(path)) {
            try (var input = Files.newInputStream(path)) {
                saved.load(input);
            }
        }
        saved.setProperty("server.host", local);
        saved.setProperty("peer.server.host", peer);
        saved.setProperty("lock.coordinator.host", coordinator);
        NetworkAddress.saveProperties(path, saved);
    }

    private static Registry createRegistry(int port) throws RemoteException {
        try {
            return LocateRegistry.createRegistry(port);
        } catch (ExportException alreadyRunning) {
            return LocateRegistry.getRegistry(port);
        }
    }

    private static final class RunningServerImpl implements ServerMonitorFrame.RunningServer {
        private final DatabaseConnection database;
        private final Registry registry;
        private final Object warehouse;
        private final Object sync;
        private final Object lock;
        private final Registry peerRegistry;

        private RunningServerImpl(DatabaseConnection database, Registry registry, Object warehouse,
                                  Object sync, Object lock, Registry peerRegistry) {
            this.database = database;
            this.registry = registry;
            this.warehouse = warehouse;
            this.sync = sync;
            this.lock = lock;
            this.peerRegistry = peerRegistry;
        }

        @Override
        public boolean peerAvailable() {
            try {
                if (peerRegistry == null) return false;
                return peerRegistry.lookup(RmiNames.SYNC) != null;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void stop() {
            try { registry.unbind(RmiNames.WAREHOUSE); } catch (Exception ignored) { }
            try { registry.unbind(RmiNames.LOCK); } catch (Exception ignored) { }
            try { registry.unbind(RmiNames.SYNC); } catch (Exception ignored) { }
            unexport(warehouse);
            unexport(sync);
            unexport(lock);
            database.close();
        }

        private static void unexport(Object remote) {
            if (remote instanceof java.rmi.Remote object) {
                try {
                    UnicastRemoteObject.unexportObject(object, true);
                } catch (RemoteException ignored) {
                    // The object may already have been unexported during shutdown.
                }
            }
        }
    }
}
