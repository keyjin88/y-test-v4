package ru.vavtech.ytestv4.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.vavtech.ytestv4.model.Cash;
import ru.vavtech.ytestv4.model.Currency;
import ru.vavtech.ytestv4.model.WithdrawalRequest;
import ru.vavtech.ytestv4.model.WithdrawalResult;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit тесты для сервиса снятия денег
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты сервиса снятия денег")
class WithdrawalServiceTest {
    
    @Mock
    private CashInventoryService cashInventoryService;
    
    @InjectMocks
    private WithdrawalService withdrawalService;
    
    private WithdrawalRequest request;
    
    @BeforeEach
    void setUp() {
        request = WithdrawalRequest.builder()
                .currency(Currency.RUB)
                .amount(new BigDecimal("1500"))
                .sessionId("test-session")
                .build();
    }
    
    @Test
    @DisplayName("Успешное снятие денег")
    void withdraw_Success() {
        // Given
        Cash cash1000 = new Cash(Currency.RUB, new BigDecimal("1000"));
        Cash cash500 = new Cash(Currency.RUB, new BigDecimal("500"));
        
        Map<Cash, Integer> inventory = Map.of(
                cash1000, 5,
                cash500, 10
        );
        
        when(cashInventoryService.getTotalAmount(Currency.RUB))
                .thenReturn(new BigDecimal("10000"));
        when(cashInventoryService.getCurrentInventory())
                .thenReturn(inventory);
        when(cashInventoryService.getCount(cash1000)).thenReturn(5);
        when(cashInventoryService.getCount(cash500)).thenReturn(10);
        when(cashInventoryService.canDispense(eq(cash1000), eq(1))).thenReturn(true);
        when(cashInventoryService.canDispense(eq(cash500), eq(1))).thenReturn(true);
        when(cashInventoryService.dispenseCash(eq(cash1000), eq(1))).thenReturn(true);
        when(cashInventoryService.dispenseCash(eq(cash500), eq(1))).thenReturn(true);
        
        // When
        WithdrawalResult result = withdrawalService.withdraw(request);
        
        // Then
        assertThat(result.getStatus()).isEqualTo(WithdrawalResult.Status.SUCCESS);
        assertThat(result.getDispensedAmount()).isEqualTo(new BigDecimal("1500"));
        assertThat(result.getSessionId()).isEqualTo("test-session");
    }
    
    @Test
    @DisplayName("Недостаточно средств в банкомате")
    void withdraw_InsufficientFunds() {
        // Given
        when(cashInventoryService.getTotalAmount(Currency.RUB))
                .thenReturn(new BigDecimal("1000"));
        
        // When
        WithdrawalResult result = withdrawalService.withdraw(request);
        
        // Then
        assertThat(result.getStatus()).isEqualTo(WithdrawalResult.Status.INSUFFICIENT_FUNDS);
        assertThat(result.getDispensedAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(result.getErrorMessage()).contains("недостаточно средств");
    }
    
    @Test
    @DisplayName("Невозможно выдать точную сумму")
    void withdraw_InvalidAmount() {
        // Given
        Cash cash1000 = new Cash(Currency.RUB, new BigDecimal("1000"));
        Map<Cash, Integer> inventory = Map.of(cash1000, 5);
        
        // Запрашиваем 1500, но есть только купюры по 1000
        when(cashInventoryService.getTotalAmount(Currency.RUB))
                .thenReturn(new BigDecimal("5000"));
        when(cashInventoryService.getCurrentInventory())
                .thenReturn(inventory);
        when(cashInventoryService.getCount(cash1000)).thenReturn(5);
        
        // When
        WithdrawalResult result = withdrawalService.withdraw(request);
        
        // Then
        assertThat(result.getStatus()).isEqualTo(WithdrawalResult.Status.INVALID_AMOUNT);
        assertThat(result.getDispensedAmount()).isEqualTo(BigDecimal.ZERO);
    }
} 