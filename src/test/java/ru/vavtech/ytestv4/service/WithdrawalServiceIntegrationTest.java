package ru.vavtech.ytestv4.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.vavtech.ytestv4.model.Cash;
import ru.vavtech.ytestv4.model.Currency;
import ru.vavtech.ytestv4.model.WithdrawalRequest;
import ru.vavtech.ytestv4.model.WithdrawalResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты для сервиса снятия денег.
 * Тестируют реальное взаимодействие между WithdrawalService и CashInventoryService
 * с подъемом полного Spring контекста.
 */
@SpringBootTest
@DisplayName("Интеграционные тесты снятия денег")
class WithdrawalServiceIntegrationTest {

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private CashInventoryService cashInventoryService;

    @BeforeEach
    void setUp() {
        // Перед каждым тестом переинициализируем инвентарь
        // чтобы тесты были изолированы друг от друга
        cashInventoryService.initializeInventory();
    }

    @Test
    @DisplayName("Успешное снятие 1500 рублей")
    void withdraw_Success_1500_RUB() {
        // Given
        WithdrawalRequest request = WithdrawalRequest.builder()
                .currency(Currency.RUB)
                .amount(new BigDecimal("1500"))
                .sessionId("integration-test-session")
                .build();

        // When
        WithdrawalResult result = withdrawalService.withdraw(request);

        // Then
        assertThat(result.getStatus()).isEqualTo(WithdrawalResult.Status.SUCCESS);
        assertThat(result.getDispensedAmount()).isEqualTo(new BigDecimal("1500"));
        assertThat(result.getSessionId()).isEqualTo("integration-test-session");
        assertThat(result.getRequestedAmount()).isEqualTo(new BigDecimal("1500"));
        assertThat(result.getDispensedCash()).isNotEmpty();

        // Проверяем, что выданы правильные купюры (жадный алгоритм: 1×1000₽ + 1×500₽)
        assertThat(result.getDispensedCash()).hasSize(2);
        assertThat(result.getDispensedCash().get(new Cash(Currency.RUB, 1000))).isEqualTo(1);
        assertThat(result.getDispensedCash().get(new Cash(Currency.RUB, 500))).isEqualTo(1);

        // Проверяем, что инвентарь обновился корректно
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 1000))).isEqualTo(49); // было 50, стало 49
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 500))).isEqualTo(99);  // было 100, стало 99
    }

    @Test
    @DisplayName("Успешное снятие 700 рублей")
    void withdraw_Success_700_RUB() {
        // Given
        WithdrawalRequest request = WithdrawalRequest.builder()
                .currency(Currency.RUB)
                .amount(new BigDecimal("700"))
                .sessionId("integration-test-700")
                .build();

        // When
        WithdrawalResult result = withdrawalService.withdraw(request);

        // Then
        assertThat(result.getStatus()).isEqualTo(WithdrawalResult.Status.SUCCESS);
        assertThat(result.getDispensedAmount()).isEqualTo(new BigDecimal("700"));

        // Проверяем оптимальную комбинацию: 1×500₽ + 2×100₽
        assertThat(result.getDispensedCash()).hasSize(2);
        assertThat(result.getDispensedCash().get(new Cash(Currency.RUB, 500))).isEqualTo(1);
        assertThat(result.getDispensedCash().get(new Cash(Currency.RUB, 100))).isEqualTo(2);

        // Проверяем обновление инвентаря
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 500))).isEqualTo(99);  // было 100, стало 99
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 100))).isEqualTo(198); // было 200, стало 198
    }

    @Test
    @DisplayName("Успешное снятие долларов $130")
    void withdraw_Success_130_USD() {
        // Given
        WithdrawalRequest request = WithdrawalRequest.builder()
                .currency(Currency.USD)
                .amount(new BigDecimal("130"))
                .sessionId("integration-test-usd")
                .build();

        // When
        WithdrawalResult result = withdrawalService.withdraw(request);

        // Then
        assertThat(result.getStatus()).isEqualTo(WithdrawalResult.Status.SUCCESS);
        assertThat(result.getDispensedAmount()).isEqualTo(new BigDecimal("130"));

        // Проверяем оптимальную комбинацию: 1×$100 + 1×$20 + 1×$10
        assertThat(result.getDispensedCash()).hasSize(3);
        assertThat(result.getDispensedCash().get(new Cash(Currency.USD, 100))).isEqualTo(1);
        assertThat(result.getDispensedCash().get(new Cash(Currency.USD, 20))).isEqualTo(1);
        assertThat(result.getDispensedCash().get(new Cash(Currency.USD, 10))).isEqualTo(1);

        // Проверяем обновление инвентаря
        assertThat(cashInventoryService.getCount(new Cash(Currency.USD, 100))).isEqualTo(19); // было 20, стало 19
        assertThat(cashInventoryService.getCount(new Cash(Currency.USD, 20))).isEqualTo(49);  // было 50, стало 49
        assertThat(cashInventoryService.getCount(new Cash(Currency.USD, 10))).isEqualTo(99);  // было 100, стало 99
    }

    @Test
    @DisplayName("Невозможно выдать нестандартную сумму")
    void withdraw_InvalidAmount_NotPossible() {
        // Given - запрашиваем сумму, которую нельзя выдать имеющимися номиналами
        WithdrawalRequest request = WithdrawalRequest.builder()
                .currency(Currency.RUB)
                .amount(new BigDecimal("150")) // Нельзя выдать - минимальная купюра 100₽
                .sessionId("integration-test-invalid")
                .build();

        // When
        WithdrawalResult result = withdrawalService.withdraw(request);

        // Then
        assertThat(result.getStatus()).isEqualTo(WithdrawalResult.Status.INVALID_AMOUNT);
        assertThat(result.getDispensedAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(result.getDispensedCash()).isEmpty();
        assertThat(result.getErrorMessage()).contains("Невозможно выдать запрошенную сумму");

        // Проверяем, что инвентарь не изменился
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 100))).isEqualTo(200);
    }

    @Test
    @DisplayName("Недостаточно средств в банкомате")
    void withdraw_InsufficientFunds() {
        // Given - запрашиваем больше денег чем есть в банкомате
        BigDecimal totalRubAmount = cashInventoryService.getTotalAmount(Currency.RUB);
        BigDecimal requestAmount = totalRubAmount.add(new BigDecimal("1000"));

        WithdrawalRequest request = WithdrawalRequest.builder()
                .currency(Currency.RUB)
                .amount(requestAmount)
                .sessionId("integration-test-insufficient")
                .build();

        // When
        WithdrawalResult result = withdrawalService.withdraw(request);

        // Then
        assertThat(result.getStatus()).isEqualTo(WithdrawalResult.Status.INSUFFICIENT_FUNDS);
        assertThat(result.getDispensedAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(result.getDispensedCash()).isEmpty();
        assertThat(result.getErrorMessage()).contains("недостаточно средств");
    }

    @Test
    @DisplayName("Проверка что sessionId может быть null")
    void withdraw_Success_NullSessionId() {
        // Given
        WithdrawalRequest request = WithdrawalRequest.builder()
                .currency(Currency.RUB)
                .amount(new BigDecimal("1000"))
                .sessionId(null) // Проверяем что null sessionId не ломает логику
                .build();

        // When
        WithdrawalResult result = withdrawalService.withdraw(request);

        // Then
        assertThat(result.getStatus()).isEqualTo(WithdrawalResult.Status.SUCCESS);
        assertThat(result.getDispensedAmount()).isEqualTo(new BigDecimal("1000"));
        assertThat(result.getSessionId()).isNull(); // sessionId должен остаться null
        
        // Проверяем выданные купюры
        assertThat(result.getDispensedCash()).hasSize(1);
        assertThat(result.getDispensedCash().get(new Cash(Currency.RUB, 1000))).isEqualTo(1);
    }
} 