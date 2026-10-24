package com.warehouse.server.dao;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class DatabaseConnection implements AutoCloseable {
    private final HikariDataSource dataSource;

    public DatabaseConnection(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        config.setPoolName("warehouse-mysql-pool");
        this.dataSource = new HikariDataSource(config);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public int verify() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var ping = connection.prepareStatement("SELECT 1");
             ResultSet pingResult = ping.executeQuery()) {
            if (!pingResult.next() || pingResult.getInt(1) != 1) {
                throw new SQLException("SELECT 1 did not return 1.");
            }
        }
        try (Connection connection = dataSource.getConnection();
             var count = connection.prepareStatement("SELECT COUNT(*) FROM products");
             ResultSet countResult = count.executeQuery()) {
            if (!countResult.next()) {
                throw new SQLException("SELECT COUNT(*) FROM products returned no row.");
            }
            return countResult.getInt(1);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
