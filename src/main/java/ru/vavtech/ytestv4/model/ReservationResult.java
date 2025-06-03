package ru.vavtech.ytestv4.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Результат операции резервирования денег в банкомате.
 */
@Value
@Builder
public class ReservationResult {
    
    Status status;
    
    /**
     * QR код для получения денег (если резервирование успешно)
     */
    String qrCode;
    
    /**
     * Запрошенная сумма
     */
    BigDecimal requestedAmount;
    
    /**
     * Зарезервированная сумма
     */
    BigDecimal reservedAmount;
    
    /**
     * Детали зарезервированных купюр
     */
    Map<Cash, Integer> reservedCash;
    
    /**
     * Время истечения резервирования
     */
    LocalDateTime expiresAt;
    
    /**
     * Время создания резервирования
     */
    LocalDateTime timestamp;
    
    /**
     * ID клиента
     */
    String customerId;
    
    /**
     * Сообщение об ошибке (если есть)
     */
    String errorMessage;
    
    /**
     * Статусы операции резервирования
     */
    public enum Status {
        /**
         * Резервирование выполнено успешно
         */
        SUCCESS,
        
        /**
         * Недостаточно средств в банкомате
         */
        INSUFFICIENT_FUNDS,
        
        /**
         * Невозможно выдать точную сумму имеющимися купюрами
         */
        INVALID_AMOUNT,
        
        /**
         * Техническая ошибка банкомата
         */
        TECHNICAL_ERROR
    }
} 