package com.warehouse.common.dto;

import java.io.Serializable;
import java.math.BigDecimal;

public record ProductInput(String productCode, String productName, String category,
                           BigDecimal price) implements Serializable {
}
