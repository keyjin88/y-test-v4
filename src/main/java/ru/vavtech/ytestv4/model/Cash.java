package ru.vavtech.ytestv4.model;

import java.math.BigDecimal;

/**
 * Представляет денежную единицу (купюру или монету) в банкомате.
 * Использует record для неизменяемости и автоматической генерации методов.
 */
public record Cash(
        Currency currency,
        BigDecimal denomination
) {
    
    public Cash {
        if (currency == null) {
            throw new IllegalArgumentException("Валюта не может быть null");
        }
        if (denomination == null || denomination.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Номинал должен быть положительным числом");
        }
    }
    
    /**
     * Возвращает строковое представление денежной единицы.
     */
    @Override
    public String toString() {
        return denomination + " " + currency.getSymbol();
    }
} 