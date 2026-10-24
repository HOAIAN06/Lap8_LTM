package com.warehouse.server.service;

import com.warehouse.common.dto.LoginResult;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WarehouseLoginTest {
    @Test void recognizesActiveEmployeeWithMatchingPlainPassword() throws Exception {
        LoginResult result = login(" staff_a ", "demo123", true, true);
        assertTrue(result.authenticated());
        assertEquals("staff_a", result.username());
        assertEquals("Nhân viên A", result.fullName());
        assertEquals("STAFF", result.role());
    }

    @Test void rejectsInactiveEmployee() throws Exception {
        assertFalse(login("staff_a", "demo123", true, false).authenticated());
    }

    @Test void rejectsUnknownEmployee() throws Exception {
        assertFalse(login("missing", "demo123", false, false).authenticated());
    }

    @Test void rejectsIncorrectPassword() throws Exception {
        assertFalse(login("staff_a", "incorrect", true, true).authenticated());
    }

    private static LoginResult login(String input, String password, boolean exists, boolean active)
            throws Exception {
        AtomicReference<String> selected = new AtomicReference<>();
        ResultSet rows = (ResultSet) Proxy.newProxyInstance(WarehouseLoginTest.class.getClassLoader(),
                new Class[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> exists;
                    case "getBoolean" -> active;
                    case "getString" -> switch ((String) args[0]) {
                        case "username" -> selected.get();
                        case "password" -> "demo123";
                        case "full_name" -> "Nhân viên A";
                        case "role" -> "STAFF";
                        default -> throw new AssertionError(args[0]);
                    };
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                WarehouseLoginTest.class.getClassLoader(), new Class[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString" -> { selected.set((String) args[1]); yield null; }
                    case "executeQuery" -> rows;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                WarehouseLoginTest.class.getClassLoader(), new Class[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        assertTrue(sql.contains("is_active"));
                        assertTrue(sql.contains("password"));
                        yield statement;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                WarehouseLoginTest.class.getClassLoader(), new Class[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) return connection;
                    throw new UnsupportedOperationException(method.getName());
                });
        WarehouseServiceImpl service = new WarehouseServiceImpl(dataSource, null, "server-a", 0, null);
        try {
            return service.login(input, password);
        } finally {
            UnicastRemoteObject.unexportObject(service, true);
        }
    }
}
