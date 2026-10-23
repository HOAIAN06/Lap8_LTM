package com.warehouse.common.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record InventoryTransaction(UUID id, String transactionCode, UUID productId,
                                   String transactionType, int quantity, int quantityBefore,
                                   int quantityAfter, String createdBy, Instant createdAt)
        implements Serializable {
}
