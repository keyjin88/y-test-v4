package ru.vavtech.ytestv4.model;

/**
 * Представляет денежную единицу (купюру или монету) в банкомате.
 * Использует record для неизменяемости и автоматической генерации методов.
 */
public record Cash(
        Currency currency,
        Integer denomination
) {
    
    public Cash {
        if (currency == null) {
            throw new IllegalArgumentException("Валюта не может быть null");
        }
        if (denomination == null || denomination <= 0) {
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