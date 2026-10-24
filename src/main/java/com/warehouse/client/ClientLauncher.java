package com.warehouse.client;

import com.warehouse.client.config.ClientConfig;
import com.warehouse.client.rmi.WarehouseClient;
import com.warehouse.client.ui.LoginFrame;
import com.warehouse.client.ui.ConnectionFrame;

import javax.swing.*;
import java.nio.file.Path;

public final class ClientLauncher {
    public static void main(String[] args) throws Exception {
        com.warehouse.client.ui.AppTheme.install();
        String configPath = args.length == 1 ? args[0] : System.getProperty("warehouse.config");
        if (configPath == null || configPath.isBlank()) {
            System.err.println("Usage: ClientLauncher <client.properties> or -Dwarehouse.config=<path>");
            return;
        }
        ClientConfig config = ClientConfig.load(configPath);
        Path path = Path.of(configPath);
        SwingUtilities.invokeLater(() -> new ConnectionFrame(config, path).setVisible(true));
    }
}
