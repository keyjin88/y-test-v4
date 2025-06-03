package ru.vavtech.ytestv4.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.vavtech.ytestv4.model.*;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты для сервиса резервирования денег по QR кодам.
 * Тестируют полный цикл: резервирование -> выдача по QR коду.
 */
@SpringBootTest
@DisplayName("Интеграционные тесты резервирования по QR")
class ReservationServiceIntegrationTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private CashInventoryService cashInventoryService;

    @BeforeEach
    void setUp() {
        // Переинициализируем инвентарь перед каждым тестом
        cashInventoryService.initializeInventory();
    }

    @Test
    @DisplayName("Полный цикл: резервирование + выдача по QR коду")
    void fullCycle_ReserveAndDispenseByQR() {
        // Given - создаем запрос на резервирование
        ReservationRequest request = ReservationRequest.builder()
                .currency(Currency.RUB)
                .amount(new BigDecimal("1500"))
                .customerId("customer-123")
                .ttl(Duration.ofMinutes(15))
                .metadata("Test reservation")
                .build();

        // When - создаем резервирование
        ReservationResult reservationResult = reservationService.reserve(request);

        // Then - проверяем успешное резервирование
        assertThat(reservationResult.getStatus()).isEqualTo(ReservationResult.Status.SUCCESS);
        assertThat(reservationResult.getQrCode()).isNotNull().startsWith("QR-");
        assertThat(reservationResult.getRequestedAmount()).isEqualTo(new BigDecimal("1500"));
        assertThat(reservationResult.getReservedAmount()).isEqualTo(new BigDecimal("1500"));
        assertThat(reservationResult.getCustomerId()).isEqualTo("customer-123");
        assertThat(reservationResult.getExpiresAt()).isNotNull();
        assertThat(reservationResult.getReservedCash()).isNotEmpty();

        // Проверяем что купюры зарезервированы (изъяты из инвентаря)
        // Изначально: 50×1000₽ + 100×500₽
        // После резервирования 1500₽ (1×1000₽ + 1×500₽): 49×1000₽ + 99×500₽
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 1000))).isEqualTo(49);
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 500))).isEqualTo(99);

        // When - выдаем деньги по QR коду
        String qrCode = reservationResult.getQrCode();
        WithdrawalResult withdrawalResult = reservationService.dispenseByQR(qrCode);

        // Then - проверяем успешную выдачу
        assertThat(withdrawalResult.getStatus()).isEqualTo(WithdrawalResult.Status.SUCCESS);
        assertThat(withdrawalResult.getDispensedAmount()).isEqualTo(new BigDecimal("1500"));
        assertThat(withdrawalResult.getRequestedAmount()).isEqualTo(new BigDecimal("1500"));
        assertThat(withdrawalResult.getSessionId()).isEqualTo("qr-" + qrCode);
        assertThat(withdrawalResult.getDispensedCash()).hasSize(2);

        // Проверяем что резервирование удалено из памяти
        assertThat(reservationService.getActiveReservations()).doesNotContainKey(qrCode);

        // Проверяем что инвентарь остался в том же состоянии (купюры уже были изъяты при резервировании)
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 1000))).isEqualTo(49);
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 500))).isEqualTo(99);
    }

    @Test
    @DisplayName("Резервирование - недостаточно средств")
    void reserve_InsufficientFunds() {
        // Given - запрашиваем больше денег чем есть в банкомате
        BigDecimal totalRubAmount = cashInventoryService.getTotalAmount(Currency.RUB);
        BigDecimal requestAmount = totalRubAmount.add(new BigDecimal("1000"));

        ReservationRequest request = ReservationRequest.builder()
                .currency(Currency.RUB)
                .amount(requestAmount)
                .customerId("customer-456")
                .build();

        // When
        ReservationResult result = reservationService.reserve(request);

        // Then
        assertThat(result.getStatus()).isEqualTo(ReservationResult.Status.INSUFFICIENT_FUNDS);
        assertThat(result.getQrCode()).isNull();
        assertThat(result.getReservedAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(result.getErrorMessage()).contains("Недостаточно средств");

        // Проверяем что инвентарь не изменился
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 1000))).isEqualTo(50);
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 500))).isEqualTo(100);
    }

    @Test
    @DisplayName("Резервирование - невозможная сумма")
    void reserve_InvalidAmount() {
        // Given - запрашиваем сумму которую нельзя выдать
        ReservationRequest request = ReservationRequest.builder()
                .currency(Currency.RUB)
                .amount(new BigDecimal("150")) // Минимальная купюра 100₽
                .customerId("customer-789")
                .build();

        // When
        ReservationResult result = reservationService.reserve(request);

        // Then
        assertThat(result.getStatus()).isEqualTo(ReservationResult.Status.INVALID_AMOUNT);
        assertThat(result.getQrCode()).isNull();
        assertThat(result.getReservedAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(result.getErrorMessage()).contains("Невозможно зарезервировать");
    }

    @Test
    @DisplayName("Выдача по недействительному QR коду")
    void dispenseByQR_InvalidQRCode() {
        // Given - несуществующий QR код
        String invalidQRCode = "QR-INVALID123456";

        // When
        WithdrawalResult result = reservationService.dispenseByQR(invalidQRCode);

        // Then
        assertThat(result.getStatus()).isEqualTo(WithdrawalResult.Status.TECHNICAL_ERROR);
        assertThat(result.getDispensedAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(result.getErrorMessage()).contains("QR код недействителен");
    }

    @Test
    @DisplayName("Повторная выдача по тому же QR коду")
    void dispenseByQR_AlreadyUsed() {
        // Given - создаем резервирование и выдаем деньги
        ReservationRequest request = ReservationRequest.builder()
                .currency(Currency.RUB)
                .amount(new BigDecimal("1000"))
                .customerId("customer-repeat")
                .build();

        ReservationResult reservationResult = reservationService.reserve(request);
        String qrCode = reservationResult.getQrCode();
        
        // Первая выдача (успешная)
        WithdrawalResult firstWithdrawal = reservationService.dispenseByQR(qrCode);
        assertThat(firstWithdrawal.getStatus()).isEqualTo(WithdrawalResult.Status.SUCCESS);

        // When - пытаемся выдать по тому же QR коду повторно
        WithdrawalResult secondWithdrawal = reservationService.dispenseByQR(qrCode);

        // Then - должна быть ошибка
        assertThat(secondWithdrawal.getStatus()).isEqualTo(WithdrawalResult.Status.TECHNICAL_ERROR);
        assertThat(secondWithdrawal.getDispensedAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(secondWithdrawal.getErrorMessage()).contains("QR код недействителен");
    }

    @Test
    @DisplayName("Множественные резервирования разных клиентов")
    void multipleReservations_DifferentCustomers() {
        // Given - создаем резервирования для двух клиентов
        ReservationRequest request1 = ReservationRequest.builder()
                .currency(Currency.RUB)
                .amount(new BigDecimal("1000"))
                .customerId("customer-1")
                .build();

        ReservationRequest request2 = ReservationRequest.builder()
                .currency(Currency.RUB)
                .amount(new BigDecimal("500"))
                .customerId("customer-2")
                .build();

        // When - создаем оба резервирования
        ReservationResult result1 = reservationService.reserve(request1);
        ReservationResult result2 = reservationService.reserve(request2);

        // Then - оба резервирования должны быть успешными
        assertThat(result1.getStatus()).isEqualTo(ReservationResult.Status.SUCCESS);
        assertThat(result2.getStatus()).isEqualTo(ReservationResult.Status.SUCCESS);
        assertThat(result1.getQrCode()).isNotEqualTo(result2.getQrCode()); // Разные QR коды

        // Проверяем что оба резервирования активны
        assertThat(reservationService.getActiveReservations()).hasSize(2);
        assertThat(reservationService.getActiveReservations())
                .containsKeys(result1.getQrCode(), result2.getQrCode());

        // Проверяем что инвентарь корректно обновился
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 1000))).isEqualTo(49); // было 50, зарезервировали 1
        assertThat(cashInventoryService.getCount(new Cash(Currency.RUB, 500))).isEqualTo(99);  // было 100, зарезервировали 1

        // When - выдаем по первому QR коду
        WithdrawalResult withdrawal1 = reservationService.dispenseByQR(result1.getQrCode());

        // Then - первое резервирование должно быть удалено, второе остается
        assertThat(withdrawal1.getStatus()).isEqualTo(WithdrawalResult.Status.SUCCESS);
        assertThat(reservationService.getActiveReservations()).hasSize(1);
        assertThat(reservationService.getActiveReservations()).containsKey(result2.getQrCode());
    }

    @Test
    @DisplayName("Резервирование долларов")
    void reserve_USD_Success() {
        // Given
        ReservationRequest request = ReservationRequest.builder()
                .currency(Currency.USD)
                .amount(new BigDecimal("130"))
                .customerId("customer-usd")
                .build();

        // When
        ReservationResult result = reservationService.reserve(request);

        // Then
        assertThat(result.getStatus()).isEqualTo(ReservationResult.Status.SUCCESS);
        assertThat(result.getReservedAmount()).isEqualTo(new BigDecimal("130"));
        
        // Проверяем правильную комбинацию купюр: 1×$100 + 1×$20 + 1×$10
        assertThat(result.getReservedCash()).hasSize(3);
        assertThat(result.getReservedCash().get(new Cash(Currency.USD, 100))).isEqualTo(1);
        assertThat(result.getReservedCash().get(new Cash(Currency.USD, 20))).isEqualTo(1);
        assertThat(result.getReservedCash().get(new Cash(Currency.USD, 10))).isEqualTo(1);

        // When - выдача по QR
        WithdrawalResult withdrawal = reservationService.dispenseByQR(result.getQrCode());

        // Then
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalResult.Status.SUCCESS);
        assertThat(withdrawal.getDispensedAmount()).isEqualTo(new BigDecimal("130"));
    }
} 