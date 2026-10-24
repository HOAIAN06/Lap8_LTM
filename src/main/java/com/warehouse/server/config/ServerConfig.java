package com.warehouse.server.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ServerConfig(String serverId, String serverHost, int registryPort, int servicePort,
                           String peerHost, int peerPort, String coordinatorHost,
                           int coordinatorPort, String dbUrl, String dbUsername,
                           String dbPassword, int leaseSeconds) {
    public static ServerConfig load(String file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(file))) {
            p.load(in);
        }
        return new ServerConfig(req(p, "server.id"), p.getProperty("server.host", "auto"),
                integer(p, "rmi.registry.port", 1099), integer(p, "rmi.service.port", 1100),
                p.getProperty("peer.server.host", ""), integer(p, "peer.rmi.port", 1099),
                req(p, "lock.coordinator.host"), integer(p, "lock.coordinator.port", 1099),
                req(p, "db.url"), req(p, "db.username"), p.getProperty("db.password", ""),
                integer(p, "lock.lease.seconds", 30));
    }

    private static String req(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank() || value.equals("replace-me")) {
            throw new IllegalArgumentException("Missing configuration: " + key);
        }
        return value;
    }

    private static int integer(Properties p, String key, int fallback) {
        return Integer.parseInt(p.getProperty(key, String.valueOf(fallback)));
    }
}
