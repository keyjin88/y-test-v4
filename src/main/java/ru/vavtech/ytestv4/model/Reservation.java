package ru.vavtech.ytestv4.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Активное резервирование денег в банкомате.
 * Хранится в памяти банкомата до истечения времени или выдачи денег.
 */
@Value
@Builder
public class Reservation {
    
    /**
     * Уникальный QR код резервирования
     */
    String qrCode;
    
    /**
     * Зарезервированная сумма
     */
    BigDecimal amount;
    
    /**
     * Валюта резервирования
     */
    Currency currency;
    
    /**
     * Зарезервированные купюры (изъяты из общего инвентаря)
     */
    Map<Cash, Integer> reservedCash;
    
    /**
     * ID клиента
     */
    String customerId;
    
    /**
     * Время создания резервирования
     */
    LocalDateTime createdAt;
    
    /**
     * Время истечения резервирования
     */
    LocalDateTime expiresAt;
    
    /**
     * Дополнительная информация
     */
    String metadata;
    
    /**
     * Проверка не истекло ли резервирование
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
    
    /**
     * Проверка активности резервирования
     */
    public boolean isActive() {
        return !isExpired();
    }
} 