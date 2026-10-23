package com.warehouse.client;

import com.warehouse.client.config.ClientConfig;
import com.warehouse.client.rmi.WarehouseClient;
import com.warehouse.client.ui.LoginFrame;

import javax.swing.*;

public final class ClientLauncher {
    public static void main(String[] args) throws Exception {
        String configPath = args.length == 1 ? args[0] : System.getProperty("warehouse.config");
        if (configPath == null || configPath.isBlank()) {
            System.err.println("Usage: ClientLauncher <client.properties> or -Dwarehouse.config=<path>");
            return;
        }
        ClientConfig config = ClientConfig.load(configPath);
        WarehouseClient client = WarehouseClient.connect(config.serverHost(), config.registryPort());
        SwingUtilities.invokeLater(() -> new LoginFrame(client, config.clientId(), config.username()).setVisible(true));
    }
}
