package com.warehouse.server.dao;

import com.warehouse.common.dto.ProductInput;
import com.warehouse.common.model.Product;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ProductRepository {
    private final DataSource dataSource;

    public ProductRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<Product> findAll(String search) throws SQLException {
        String term = search == null ? "" : search.trim().toLowerCase();
        String sql = "SELECT * FROM products WHERE NOT is_deleted AND (lower(product_code) LIKE ? OR lower(product_name) LIKE ?) ORDER BY product_code";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "%" + term + "%");
            ps.setString(2, "%" + term + "%");
            try (ResultSet rs = ps.executeQuery()) {
                List<Product> result = new ArrayList<>();
                while (rs.next()) result.add(map(rs));
                return result;
            }
        }
    }

    public Product find(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM products WHERE id=? FOR UPDATE")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public Product insert(Connection c, ProductInput input) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO products(id,product_code,product_name,category,price) VALUES (?,?,?,?,?)")) {
            ps.setObject(1, id); ps.setString(2, input.productCode()); ps.setString(3, input.productName());
            ps.setString(4, input.category()); ps.setBigDecimal(5, input.price()); ps.executeUpdate();
        }
        return findById(c, id);
    }

    public Product update(Connection c, UUID id, ProductInput input, long fencing) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE products SET product_name=?,category=?,price=?,version=version+1,last_fencing_token=?,updated_at=now() WHERE id=?")) {
            ps.setString(1, input.productName()); ps.setString(2, input.category()); ps.setBigDecimal(3, input.price());
            ps.setLong(4, fencing); ps.setObject(5, id); ps.executeUpdate();
        }
        return findById(c, id);
    }

    public void softDelete(Connection c, UUID id, long fencing) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE products SET is_deleted=true,version=version+1,last_fencing_token=?,updated_at=now() WHERE id=?")) {
            ps.setLong(1, fencing); ps.setObject(2, id); ps.executeUpdate();
        }
    }

    public Product findById(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM products WHERE id=?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    private Product map(ResultSet rs) throws SQLException {
        return new Product(rs.getObject("id", UUID.class), rs.getString("product_code"),
                rs.getString("product_name"), rs.getString("category"), rs.getBigDecimal("price"),
                rs.getInt("quantity"), rs.getLong("version"), rs.getLong("last_fencing_token"),
                rs.getBoolean("is_deleted"), rs.getTimestamp("updated_at").toInstant());
    }
}
