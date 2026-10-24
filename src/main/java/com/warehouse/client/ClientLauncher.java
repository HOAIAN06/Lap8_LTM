package com.warehouse.client;

import com.warehouse.client.config.ClientConfig;
import com.warehouse.client.ui.ClientAccessFrame;

import javax.swing.*;
import java.nio.file.Path;

public final class ClientLauncher {
    public static void main(String[] args) throws Exception {
        com.warehouse.client.ui.AppTheme.install();
        String configPath = System.getProperty("warehouse.config");
        String clientId = null;
        for (String arg : args) {
            if (arg.startsWith("--client-id=")) {
                clientId = arg.substring("--client-id=".length());
            } else if (arg.startsWith("--role=")) {
                String role = arg.substring("--role=".length());
                clientId = "b".equalsIgnoreCase(role) || "client-b".equalsIgnoreCase(role)
                        ? "client-b" : "client-a";
            } else if (!arg.startsWith("--")) {
                configPath = arg;
            }
        }
        if (configPath == null || configPath.isBlank()) {
            configPath = clientId != null && clientId.equals("client-b")
                    ? "client-b.properties" : "client-a.properties";
        }
        ClientConfig config = ClientConfig.load(configPath).withClientId(clientId);
        Path path = Path.of(configPath);
        SwingUtilities.invokeLater(() -> new ClientAccessFrame(config, path).setVisible(true));
    }
}
