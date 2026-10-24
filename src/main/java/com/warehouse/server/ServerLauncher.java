package com.warehouse.server;

import com.warehouse.common.rmi.RmiNames;
import com.warehouse.common.rmi.LockService;
import com.warehouse.common.network.NetworkAddress;
import com.warehouse.server.config.ServerConfig;
import com.warehouse.server.lock.LockManager;
import com.warehouse.server.lock.ReconnectingLockService;
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
import java.nio.file.Path;
import java.util.Properties;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import com.warehouse.server.ui.ServerMonitorFrame;

public final class ServerLauncher {
    private static final Logger LOG = Logger.getLogger(ServerLauncher.class.getName());

    public static void main(String[] args) throws Exception {
        com.warehouse.client.ui.AppTheme.install();
        String configPath = args.length == 1 ? args[0] : System.getProperty("warehouse.config");
        if (configPath == null || configPath.isBlank()) {
            configPath = "src/main/resources/server-a.properties";
        }
        if (configPath == null || configPath.isBlank()) {
            System.err.println("Usage: ServerLauncher <server.properties> or -Dwarehouse.config=<path>");
            return;
        }
        ServerConfig config = ServerConfig.load(configPath);
        String localAddress = NetworkAddress.chooseServerAddress(config.serverHost(), config.serverId());
        boolean serverA = "server-a".equalsIgnoreCase(config.serverId());
        String peerAddress = NetworkAddress.promptPeerAddress(config.peerHost(),
                serverA ? "Server B" : "Server A", serverA);
        Properties saved = new Properties();
        try (var input = java.nio.file.Files.newInputStream(Path.of(configPath))) {
            saved.load(input);
        }
        saved.setProperty("server.host", localAddress);
        saved.setProperty("peer.server.host", peerAddress);
        NetworkAddress.saveProperties(Path.of(configPath), saved);
        String coordinatorAddress = "server-a".equalsIgnoreCase(config.serverId())
                ? localAddress : peerAddress;
        saved.setProperty("lock.coordinator.host", coordinatorAddress);
        NetworkAddress.saveProperties(Path.of(configPath), saved);
        config = new ServerConfig(config.serverId(), localAddress, config.registryPort(), config.servicePort(),
                peerAddress, config.peerPort(), coordinatorAddress, config.coordinatorPort(),
                config.dbUrl(), config.dbUsername(), config.dbPassword(), config.leaseSeconds());
        System.setProperty("java.rmi.server.hostname", localAddress);
        System.out.println("Địa chỉ LAN đã chọn: " + localAddress);
        System.out.println("Địa chỉ Server còn lại: " + peerAddress);
        DatabaseConnection database = new DatabaseConnection(config.dbUrl(), config.dbUsername(), config.dbPassword());
        try {
            int productCount = database.verify();
            System.out.println("Database connected successfully");
            System.out.println("Database products count: " + productCount);
        } catch (SQLException e) {
            database.close();
            throw new IllegalStateException("Database connection failed for " + config.dbUrl()
                    + ": " + e.getMessage(), e);
        }
        Registry registry = createRegistry(config.registryPort());
        LockService lockService;
        if ("server-a".equalsIgnoreCase(config.serverId())) {
            lockService = new LockManager(database.dataSource(), config.leaseSeconds());
            registry.rebind(RmiNames.LOCK, lockService);
        } else {
            ReconnectingLockService reconnectingLocks = new ReconnectingLockService(
                    config.coordinatorHost(), config.coordinatorPort(), config.servicePort() + 2);
            try {
                reconnectingLocks.getOrConnectCoordinator();
            } catch (Exception e) {
                System.err.println("Chưa kết nối được Lock Coordinator tại " + config.coordinatorHost()
                        + ":" + config.coordinatorPort() + " lúc khởi động. Sẽ thử kết nối lại khi có yêu cầu.");
            }
            lockService = reconnectingLocks;
        }
        Registry peerRegistry = peerAddress.isBlank() ? null
                : LocateRegistry.getRegistry(config.peerHost(), config.peerPort());
        WarehouseServiceImpl warehouse = new WarehouseServiceImpl(database.dataSource(), lockService,
                config.serverId(), config.servicePort(), peerRegistry);
        SyncServiceImpl sync = new SyncServiceImpl(database.dataSource(), config.serverId(), config.servicePort() + 1);
        registry.rebind(RmiNames.WAREHOUSE, warehouse);
        registry.rebind(RmiNames.LOCK, lockService);
        registry.rebind(RmiNames.SYNC, sync);
        if (!isPeerReachable(peerRegistry)) {
            System.err.println("Peer server chưa kết nối tại " + config.peerHost() + ":"
                    + config.peerPort() + ". Đồng bộ đang chờ; không giả định đã sẵn sàng.");
            if (!"server-a".equalsIgnoreCase(config.serverId())) {
                database.close();
                throw new IllegalStateException("Server B không thể tiếp tục khi Server A/lock coordinator chưa kết nối.");
            }
        }
        OutboxDispatcher dispatcher = peerRegistry == null ? null
                : new OutboxDispatcher(database.dataSource(), config.serverId(), peerRegistry);
        if (dispatcher != null) dispatcher.start();
        String startupMessage = "Started " + config.serverId() + " at " + config.serverHost()
                + ":" + config.registryPort();
        LOG.info(startupMessage);
        Registry monitorPeerRegistry = peerRegistry;
        ServerConfig monitorConfig = config;
        if (!GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> new ServerMonitorFrame(monitorConfig.serverId(),
                    monitorConfig.serverHost(), monitorConfig.registryPort(), monitorConfig.servicePort(),
                    monitorConfig.peerHost(), monitorConfig.peerPort(), monitorPeerRegistry,
                    database.dataSource()).setVisible(true));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (dispatcher != null) dispatcher.close();
            database.close();
        }));
        Thread.currentThread().join();
    }

    private static boolean isPeerReachable(Registry registry) {
        if (registry == null) return false;
        try {
            registry.lookup(RmiNames.SYNC);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Registry createRegistry(int port) throws RemoteException {
        try {
            return LocateRegistry.createRegistry(port);
        } catch (ExportException alreadyRunning) {
            return LocateRegistry.getRegistry(port);
        }
    }
}
