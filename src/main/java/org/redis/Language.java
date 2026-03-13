package org.redis;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class Language {

    private String name;
    private boolean official;
    private BigDecimal percentage;
}
