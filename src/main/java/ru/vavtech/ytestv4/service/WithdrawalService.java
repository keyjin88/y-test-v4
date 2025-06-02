package ru.vavtech.ytestv4.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.vavtech.ytestv4.model.Cash;
import ru.vavtech.ytestv4.model.Currency;
import ru.vavtech.ytestv4.model.WithdrawalRequest;
import ru.vavtech.ytestv4.model.WithdrawalResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Сервис для обработки операций снятия денег из банкомата.
 * Реализует алгоритм жадного поиска для оптимальной выдачи купюр.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {
    
    private final CashInventoryService cashInventoryService;
    
    /**
     * Выполнение операции снятия денег
     */
    public WithdrawalResult withdraw(WithdrawalRequest request) {
        log.info("Обработка запроса на снятие {} {} для сессии {}", 
                request.getAmount(), request.getCurrency(), request.getSessionId());
        
        try {
            // Проверка наличия достаточных средств в банкомате
            BigDecimal totalAvailable = cashInventoryService.getTotalAmount(request.getCurrency());
            if (totalAvailable.compareTo(request.getAmount()) < 0) {
                return createErrorResult(request, WithdrawalResult.Status.INSUFFICIENT_FUNDS,
                        "В банкомате недостаточно средств в валюте " + request.getCurrency());
            }
            
            // Поиск оптимального набора купюр
            Map<Cash, Integer> dispenseCombination = findOptimalDispenseCombination(
                    request.getCurrency(), request.getAmount());
            
            if (dispenseCombination.isEmpty()) {
                return createErrorResult(request, WithdrawalResult.Status.INVALID_AMOUNT,
                        "Невозможно выдать запрошенную сумму имеющимися купюрами");
            }
            
            // Выполнение операции выдачи
            boolean success = executeDispense(dispenseCombination);
            if (!success) {
                return createErrorResult(request, WithdrawalResult.Status.TECHNICAL_ERROR,
                        "Ошибка при выдаче денег");
            }
            
            BigDecimal dispensedAmount = calculateTotalAmount(dispenseCombination);
            
            log.info("Успешно выдано {} {} для сессии {}", 
                    dispensedAmount, request.getCurrency(), request.getSessionId());
            
            return WithdrawalResult.builder()
                    .status(WithdrawalResult.Status.SUCCESS)
                    .requestedAmount(request.getAmount())
                    .dispensedAmount(dispensedAmount)
                    .dispensedCash(dispenseCombination)
                    .timestamp(LocalDateTime.now())
                    .sessionId(request.getSessionId())
                    .build();
                    
        } catch (Exception e) {
            log.error("Техническая ошибка при обработке запроса для сессии " + request.getSessionId(), e);
            return createErrorResult(request, WithdrawalResult.Status.TECHNICAL_ERROR,
                    "Техническая ошибка: " + e.getMessage());
        }
    }
    
    /**
     * Поиск оптимального набора купюр для выдачи запрошенной суммы.
     * Использует жадный алгоритм: начинает с самых крупных номиналов.
     */
    private Map<Cash, Integer> findOptimalDispenseCombination(Currency currency, BigDecimal amount) {
        // Получаем доступные купюры для валюты, отсортированные по убыванию номинала
        List<Cash> availableCash = getAvailableCashByCurrency(currency);
        availableCash.sort((c1, c2) -> Integer.compare(c2.denomination(), c1.denomination()));
        
        Map<Cash, Integer> result = new HashMap<>();
        BigDecimal remainingAmount = amount;
        
        // Жадный алгоритм: берем максимальное количество крупных купюр
        for (Cash cash : availableCash) {
            int availableCount = cashInventoryService.getCount(cash);
            BigDecimal cashValue = BigDecimal.valueOf(cash.denomination());
            
            if (availableCount > 0 && remainingAmount.compareTo(cashValue) >= 0) {
                
                // Вычисляем максимальное количество купюр этого номинала
                int maxPossible = remainingAmount.divideToIntegralValue(cashValue).intValue();
                int actualCount = Math.min(maxPossible, availableCount);
                
                if (actualCount > 0) {
                    result.put(cash, actualCount);
                    remainingAmount = remainingAmount.subtract(
                            cashValue.multiply(BigDecimal.valueOf(actualCount)));
                }
            }
        }
        
        // Проверяем, удалось ли набрать точную сумму
        if (remainingAmount.compareTo(BigDecimal.ZERO) != 0) {
            log.debug("Невозможно выдать точную сумму {}, остаток: {}", amount, remainingAmount);
            return Collections.emptyMap();
        }
        
        return result;
    }
    
    /**
     * Получение списка доступных купюр для указанной валюты
     */
    private List<Cash> getAvailableCashByCurrency(Currency currency) {
        return cashInventoryService.getCurrentInventory().entrySet().stream()
                .filter(entry -> entry.getKey().currency() == currency && entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
    
    /**
     * Выполнение физической выдачи купюр
     */
    private boolean executeDispense(Map<Cash, Integer> dispenseCombination) {
        // Сначала проверяем, что все купюры еще доступны (защита от race condition)
        for (Map.Entry<Cash, Integer> entry : dispenseCombination.entrySet()) {
            if (!cashInventoryService.canDispense(entry.getKey(), entry.getValue())) {
                log.warn("Невозможно выдать {} купюр номиналом {}", entry.getValue(), entry.getKey());
                return false;
            }
        }
        
        // Выполняем выдачу
        for (Map.Entry<Cash, Integer> entry : dispenseCombination.entrySet()) {
            if (!cashInventoryService.dispenseCash(entry.getKey(), entry.getValue())) {
                // В случае ошибки пытаемся откатить уже выданные купюры
                rollbackDispense(dispenseCombination, entry.getKey());
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Откат операции выдачи в случае ошибки
     */
    private void rollbackDispense(Map<Cash, Integer> dispenseCombination, Cash failedCash) {
        log.warn("Выполняется откат операции выдачи до купюры {}", failedCash);
        // В реальном банкомате здесь была бы логика возврата уже выданных купюр
        // Для упрощения предполагаем, что откат происходит на уровне hardware
    }
    
    /**
     * Вычисление общей суммы выданных денег
     */
    private BigDecimal calculateTotalAmount(Map<Cash, Integer> dispensedCash) {
        return dispensedCash.entrySet().stream()
                .map(entry -> BigDecimal.valueOf(entry.getKey().denomination())
                        .multiply(BigDecimal.valueOf(entry.getValue())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    /**
     * Создание результата с ошибкой
     */
    private WithdrawalResult createErrorResult(WithdrawalRequest request, 
                                               WithdrawalResult.Status status, 
                                               String errorMessage) {
        return WithdrawalResult.builder()
                .status(status)
                .requestedAmount(request.getAmount())
                .dispensedAmount(BigDecimal.ZERO)
                .dispensedCash(Collections.emptyMap())
                .timestamp(LocalDateTime.now())
                .errorMessage(errorMessage)
                .sessionId(request.getSessionId())
                .build();
    }
} 