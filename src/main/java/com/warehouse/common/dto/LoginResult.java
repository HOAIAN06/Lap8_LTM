package com.warehouse.common.dto;

import java.io.Serializable;

public record LoginResult(boolean authenticated, String username, String fullName,
                          String role, String message) implements Serializable {
}
