package com.warehouse.server.sync;

/**
 * @deprecated Đã thay thế bằng Direct RMI Sync, nơi các thao tác được đồng bộ trực tiếp và tuần tự.
 */
@Deprecated
public final class EventOrder {
    public enum Decision { APPLY, DEFER, CONFLICT }
    private EventOrder() { }

    public static Decision decide(Long currentVersion, long incomingVersion, String action) {
        if (incomingVersion < 0) throw new IllegalArgumentException("Negative entity version");
        String normalized = SyncValues.action(action);
        if (currentVersion == null) {
            return "INSERT".equals(normalized) && incomingVersion == 0
                    ? Decision.APPLY : Decision.DEFER;
        }
        if (incomingVersion <= currentVersion) return Decision.CONFLICT;
        return incomingVersion == currentVersion + 1 ? Decision.APPLY : Decision.DEFER;
    }
}
