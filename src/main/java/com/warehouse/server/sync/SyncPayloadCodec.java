package com.warehouse.server.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** JSON snapshot shared by the transactional outbox and its RMI receiver. */
public final class SyncPayloadCodec {
    public record ProductState(UUID id, String code, String name, String category, BigDecimal price,
                               int quantity, long version, long fencingToken, boolean deleted) { }
    public record TransactionState(UUID id, String code, UUID productId, String type, int quantity,
                                   int before, int after, UUID createdBy, Instant createdAt) { }
    public record Payload(ProductState product, TransactionState transaction) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private SyncPayloadCodec() { }

    public static String fromDatabase(Connection connection, UUID productId, UUID transactionId)
            throws SQLException {
        ProductState product;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM products WHERE id=?")) {
            query.setString(1, productId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Product disappeared before outbox insert: " + productId);
                product = new ProductState(UUID.fromString(row.getString("id")),
                        row.getString("product_code"), row.getString("product_name"),
                        row.getString("category"), row.getBigDecimal("price"), row.getInt("quantity"),
                        row.getLong("version"), row.getLong("last_fencing_token"), row.getBoolean("is_deleted"));
            }
        }
        TransactionState transaction = null;
        if (transactionId != null) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT * FROM inventory_transactions WHERE id=?")) {
                query.setString(1, transactionId.toString());
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) throw new SQLException("Transaction disappeared before outbox insert: " + transactionId);
                    String userId = row.getString("created_by");
                    transaction = new TransactionState(UUID.fromString(row.getString("id")),
                            row.getString("transaction_code"), UUID.fromString(row.getString("product_id")),
                            row.getString("transaction_type"), row.getInt("quantity"),
                            row.getInt("quantity_before"), row.getInt("quantity_after"),
                            userId == null ? null : UUID.fromString(userId),
                            row.getTimestamp("created_at").toInstant());
                }
            }
        }
        return encode(new Payload(product, transaction));
    }

    public static String encode(Payload payload) {
        ProductState p = payload.product();
        ObjectNode root = JSON.createObjectNode();
        ObjectNode product = root.putObject("product");
        product.put("id", p.id().toString());
        product.put("product_code", p.code());
        product.put("product_name", p.name());
        if (p.category() == null) product.putNull("category");
        else product.put("category", p.category());
        product.put("price", p.price().toPlainString());
        product.put("quantity", p.quantity());
        product.put("version", p.version());
        product.put("last_fencing_token", p.fencingToken());
        product.put("is_deleted", p.deleted());
        TransactionState t = payload.transaction();
        if (t != null) {
            ObjectNode transaction = root.putObject("transaction");
            transaction.put("id", t.id().toString());
            transaction.put("transaction_code", t.code());
            transaction.put("product_id", t.productId().toString());
            transaction.put("transaction_type", t.type());
            transaction.put("quantity", t.quantity());
            transaction.put("quantity_before", t.before());
            transaction.put("quantity_after", t.after());
            if (t.createdBy() == null) transaction.putNull("created_by");
            else transaction.put("created_by", t.createdBy().toString());
            transaction.put("created_at", t.createdAt().toString());
        }
        return root.toString();
    }

    public static Payload decode(String json) throws IOException {
        JsonNode root = JSON.readTree(json);
        JsonNode p = required(root, "product");
        ProductState product = new ProductState(UUID.fromString(text(p, "id")),
                text(p, "product_code"), text(p, "product_name"), nullableText(p, "category"),
                new BigDecimal(text(p, "price")), required(p, "quantity").intValue(),
                required(p, "version").longValue(), required(p, "last_fencing_token").longValue(),
                required(p, "is_deleted").booleanValue());
        JsonNode t = root.get("transaction");
        TransactionState transaction = null;
        if (t != null && !t.isNull()) {
            String userId = nullableText(t, "created_by");
            transaction = new TransactionState(UUID.fromString(text(t, "id")),
                    text(t, "transaction_code"), UUID.fromString(text(t, "product_id")),
                    text(t, "transaction_type"), required(t, "quantity").intValue(),
                    required(t, "quantity_before").intValue(), required(t, "quantity_after").intValue(),
                    userId == null ? null : UUID.fromString(userId), Instant.parse(text(t, "created_at")));
        }
        return new Payload(product, transaction);
    }

    private static JsonNode required(JsonNode node, String field) throws IOException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) throw new IOException("Missing sync payload field: " + field);
        return value;
    }

    private static String text(JsonNode node, String field) throws IOException {
        return required(node, field).asText();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
