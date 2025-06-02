package ru.vavtech.ytestv4.model;

/**
 * Перечисление поддерживаемых валют в банкомате.
 * Легко расширяется добавлением новых валют.
 */
public enum Currency {
    
    RUB("Российский рубль", "₽"),
    USD("Доллар США", "$"),
    EUR("Евро", "€");
    
    private final String name;
    private final String symbol;
    
    Currency(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }
    
    public String getName() {
        return name;
    }
    
    public String getSymbol() {
        return symbol;
    }
} 