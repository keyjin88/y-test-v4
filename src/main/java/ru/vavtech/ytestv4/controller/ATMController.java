package ru.vavtech.ytestv4.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.vavtech.ytestv4.model.Currency;
import ru.vavtech.ytestv4.model.WithdrawalRequest;
import ru.vavtech.ytestv4.model.WithdrawalResult;
import ru.vavtech.ytestv4.service.CashInventoryService;
import ru.vavtech.ytestv4.service.WithdrawalService;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * REST контроллер для взаимодействия с банкоматом.
 * Предоставляет API для операций снятия денег и проверки состояния.
 */
@Slf4j
@RestController
@RequestMapping("/api/atm")
@RequiredArgsConstructor
public class ATMController {
    
    private final WithdrawalService withdrawalService;
    private final CashInventoryService cashInventoryService;
    
    /**
     * Снятие денег из банкомата
     */
    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawalResult> withdraw(@RequestBody WithdrawRequest request) {
        
        // Генерируем уникальный идентификатор сессии для операции
        String sessionId = UUID.randomUUID().toString();
        
        WithdrawalRequest withdrawalRequest = WithdrawalRequest.builder()
                .currency(request.currency())
                .amount(request.amount())
                .sessionId(sessionId)
                .build();
        
        WithdrawalResult result = withdrawalService.withdraw(withdrawalRequest);
        
        HttpStatus status = switch (result.getStatus()) {
            case SUCCESS -> HttpStatus.OK;
            case INSUFFICIENT_FUNDS, INVALID_AMOUNT -> HttpStatus.BAD_REQUEST;
            case TECHNICAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        
        return ResponseEntity.status(status).body(result);
    }
    
    /**
     * Получение информации о доступных средствах в банкомате
     */
    @GetMapping("/balance")
    public ResponseEntity<Map<Currency, BigDecimal>> getBalance() {
        try {
            Map<Currency, BigDecimal> balances = Map.of(
                    Currency.RUB, cashInventoryService.getTotalAmount(Currency.RUB),
                    Currency.USD, cashInventoryService.getTotalAmount(Currency.USD),
                    Currency.EUR, cashInventoryService.getTotalAmount(Currency.EUR)
            );
            return ResponseEntity.ok(balances);
        } catch (Exception e) {
            log.error("Ошибка при получении баланса банкомата", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Проверка работоспособности банкомата
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
    
    /**
     * DTO для запроса снятия денег
     */
    public record WithdrawRequest(
            Currency currency,
            BigDecimal amount
    ) {
        public WithdrawRequest {
            if (currency == null) {
                throw new IllegalArgumentException("Валюта обязательна");
            }
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Сумма должна быть положительной");
            }
        }
    }
} 