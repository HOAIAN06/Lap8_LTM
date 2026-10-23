package com.warehouse.client.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ClientConfig(String serverHost, int registryPort, String clientId, String username) {
    public static ClientConfig load(String file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(file))) { p.load(in); }
        return new ClientConfig(required(p, "server.host"),
                Integer.parseInt(p.getProperty("rmi.registry.port", "1099")),
                required(p, "client.id"), p.getProperty("client.username", ""));
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing configuration: " + key);
        return value;
    }
}
