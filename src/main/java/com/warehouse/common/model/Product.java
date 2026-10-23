package com.warehouse.common.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Product(UUID id, String productCode, String productName, String category,
                      BigDecimal price, int quantity, long version, long lastFencingToken,
                      boolean deleted, Instant updatedAt) implements Serializable {
}
