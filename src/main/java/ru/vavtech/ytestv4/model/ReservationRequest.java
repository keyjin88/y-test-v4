package ru.vavtech.ytestv4.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Запрос на резервирование денег в банкомате.
 * Приходит от сервера банка когда клиент создает резервирование через мобильное приложение.
 */
@Value
@Builder
public class ReservationRequest {
    
    @NotNull(message = "Валюта обязательна")
    Currency currency;
    
    @NotNull(message = "Сумма обязательна")
    @Positive(message = "Сумма должна быть положительной")
    BigDecimal amount;
    
    /**
     * Уникальный идентификатор клиента от сервера банка
     */
    @NotBlank(message = "ID клиента обязателен")
    String customerId;
    
    /**
     * Время жизни резервирования (по умолчанию 15 минут)
     */
    @Builder.Default
    Duration ttl = Duration.ofMinutes(15);
    
    /**
     * Дополнительная информация от сервера банка
     */
    String metadata;
} 