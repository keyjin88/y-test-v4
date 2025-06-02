package ru.vavtech.ytestv4.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Запрос на снятие денег из банкомата.
 * Использует Lombok для генерации кода и Bean Validation для проверок.
 */
@Value
@Builder
public class WithdrawalRequest {
    
    @NotNull(message = "Валюта обязательна")
    Currency currency;
    
    @NotNull(message = "Сумма обязательна")
    @Positive(message = "Сумма должна быть положительной")
    BigDecimal amount;
    
    /**
     * Идентификатор сессии для обеспечения thread-safety
     */
    @NotNull(message = "Идентификатор сессии обязателен")
    String sessionId;
} 