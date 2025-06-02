package ru.vavtech.ytestv4.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Результат операции снятия денег из банкомата.
 * Содержит информацию о выданных купюрах и статусе операции.
 */
@Value
@Builder
public class WithdrawalResult {
    
    /**
     * Статус операции снятия
     */
    Status status;
    
    /**
     * Запрошенная сумма
     */
    BigDecimal requestedAmount;
    
    /**
     * Фактически выданная сумма
     */
    BigDecimal dispensedAmount;
    
    /**
     * Выданные купюры: номинал -> количество
     */
    Map<Cash, Integer> dispensedCash;
    
    /**
     * Время операции
     */
    LocalDateTime timestamp;
    
    /**
     * Сообщение об ошибке (если есть)
     */
    String errorMessage;
    
    /**
     * Идентификатор сессии
     */
    String sessionId;
    
    /**
     * Статусы операции снятия денег
     */
    public enum Status {
        SUCCESS("Операция выполнена успешно"),
        INSUFFICIENT_FUNDS("Недостаточно средств в банкомате"),
        INVALID_AMOUNT("Невозможно выдать запрошенную сумму имеющимися купюрами"),
        TECHNICAL_ERROR("Техническая ошибка");
        
        private final String description;
        
        Status(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
} 