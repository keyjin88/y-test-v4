package ru.vavtech.ytestv4.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.vavtech.ytestv4.model.*;
import ru.vavtech.ytestv4.service.CashInventoryService;
import ru.vavtech.ytestv4.service.ReservationService;
import ru.vavtech.ytestv4.service.WithdrawalService;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * REST контроллер для взаимодействия с банкоматом.
 * Предоставляет API для операций снятия денег, резервирования по QR и проверки состояния.
 */
@Slf4j
@RestController
@RequestMapping("/api/atm")
@RequiredArgsConstructor
public class ATMController {
    
    private final WithdrawalService withdrawalService;
    private final CashInventoryService cashInventoryService;
    private final ReservationService reservationService;
    
    /**
     * Снятие денег из банкомата
     */
    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawalResult> withdraw(@RequestBody @Valid WithdrawalRequest request) {
        
        // Если sessionId не передан в запросе, генерируем автоматически
        WithdrawalRequest requestWithSession = request.getSessionId() != null 
            ? request 
            : WithdrawalRequest.builder()
                .currency(request.getCurrency())
                .amount(request.getAmount())
                .sessionId(UUID.randomUUID().toString())
                .build();
        
        WithdrawalResult result = withdrawalService.withdraw(requestWithSession);
        
        HttpStatus status = switch (result.getStatus()) {
            case SUCCESS -> HttpStatus.OK;
            case INSUFFICIENT_FUNDS, INVALID_AMOUNT -> HttpStatus.BAD_REQUEST;
            case TECHNICAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        
        return ResponseEntity.status(status).body(result);
    }

    /**
     * Резервирование денег в банкомате (от сервера банка)
     */
    @PostMapping("/reserve")
    public ResponseEntity<ReservationResult> reserve(@RequestBody @Valid ReservationRequest request) {
        log.info("Запрос на резервирование {} {} для клиента {}", 
                request.getAmount(), request.getCurrency(), request.getCustomerId());
        
        ReservationResult result = reservationService.reserve(request);
        
        HttpStatus status = switch (result.getStatus()) {
            case SUCCESS -> HttpStatus.OK;
            case INSUFFICIENT_FUNDS, INVALID_AMOUNT -> HttpStatus.BAD_REQUEST;
            case TECHNICAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        
        return ResponseEntity.status(status).body(result);
    }

    /**
     * Выдача денег по QR коду
     */
    @PostMapping("/dispense/{qrCode}")
    public ResponseEntity<WithdrawalResult> dispenseByQR(@PathVariable String qrCode) {
        log.info("Запрос на выдачу денег по QR коду: {}", qrCode);
        
        WithdrawalResult result = reservationService.dispenseByQR(qrCode);
        
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
     * Получение информации об активных резервированиях (для мониторинга)
     */
    @GetMapping("/reservations")
    public ResponseEntity<Map<String, Reservation>> getActiveReservations() {
        try {
            Map<String, Reservation> reservations = reservationService.getActiveReservations();
            return ResponseEntity.ok(reservations);
        } catch (Exception e) {
            log.error("Ошибка при получении активных резервирований", e);
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
} 