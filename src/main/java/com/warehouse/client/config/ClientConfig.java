package com.warehouse.client.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ClientConfig(String serverHost, int registryPort, String clientId, String username) {
    public static ClientConfig load(String file) throws IOException {
        Properties p = new Properties();
        if (file != null && !file.isBlank() && Files.exists(Path.of(file))) {
            try (InputStream in = Files.newInputStream(Path.of(file))) { p.load(in); }
        }
        return new ClientConfig(p.getProperty("server.host", ""),
                Integer.parseInt(p.getProperty("rmi.registry.port", "1099")),
                p.getProperty("client.id", "client-a"),
                p.getProperty("client.username", ""));
    }

    public ClientConfig withClientId(String value) {
        return new ClientConfig(serverHost, registryPort,
                value == null || value.isBlank() ? clientId : value.trim(), username);
    }
}
