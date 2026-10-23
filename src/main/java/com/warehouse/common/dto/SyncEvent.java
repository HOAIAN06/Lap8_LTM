package com.warehouse.common.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record SyncEvent(UUID eventId, String originServer, String entityType, UUID entityId,
                        String action, String payload, long entityVersion,
                        String syncStatus, int retryCount, Instant createdAt)
        implements Serializable {
}
