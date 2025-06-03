package ru.vavtech.ytestv4.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.vavtech.ytestv4.model.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Сервис для управления резервированием денег в банкомате по QR кодам.
 * <p>
 * Функциональность:
 * - Резервирование денег для клиентов (изъятие из общего инвентаря)
 * - Выдача денег по QR коду
 * - Автоматическая очистка истекших резервирований
 * - Thread-safety для многопользовательской среды
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final CashInventoryService cashInventoryService;
    private final WithdrawalService withdrawalService;

    /**
     * Хранилище активных резервирований.
     * Ключ: QR код, Значение: объект резервирования
     */
    private final Map<String, Reservation> activeReservations = new ConcurrentHashMap<>();

    /**
     * Блокировка для атомарности сложных операций с резервированиями
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Создание резервирования денег в банкомате.
     * <p>
     * Алгоритм:
     * 1. Проверка достаточности средств
     * 2. Поиск оптимального набора купюр
     * 3. Резервирование купюр (изъятие из общего инвентаря)
     * 4. Генерация уникального QR кода
     * 5. Сохранение резервирования в памяти
     *
     * @param request запрос на резервирование от сервера банка
     * @return результат резервирования с QR кодом или ошибкой
     */
    public ReservationResult reserve(ReservationRequest request) {
        log.info("Создание резервирования {} {} для клиента {}", 
                request.getAmount(), request.getCurrency(), request.getCustomerId());

        lock.writeLock().lock();
        try {
            // Очищаем истекшие резервирования перед созданием нового
            cleanupExpiredReservations();

            // Шаг 1: Проверка общей достаточности средств
            BigDecimal totalAvailable = getAvailableAmount(request.getCurrency());
            if (totalAvailable.compareTo(request.getAmount()) < 0) {
                log.warn("Недостаточно средств для резервирования: запрошено {}, доступно {} {}", 
                        request.getAmount(), totalAvailable, request.getCurrency());
                return createErrorResult(request, ReservationResult.Status.INSUFFICIENT_FUNDS,
                        "Недостаточно средств для резервирования");
            }

            // Шаг 2: Поиск оптимального набора купюр для резервирования
            // Используем тот же алгоритм что и для обычной выдачи
            WithdrawalRequest withdrawalRequest = WithdrawalRequest.builder()
                    .currency(request.getCurrency())
                    .amount(request.getAmount())
                    .sessionId("reservation-" + UUID.randomUUID())
                    .build();

            // Симулируем поиск комбинации без фактической выдачи
            Map<Cash, Integer> reserveCombination = findReservationCombination(
                    request.getCurrency(), request.getAmount());

            if (reserveCombination.isEmpty()) {
                log.warn("Невозможно зарезервировать сумму {} из доступных номиналов {}", 
                        request.getAmount(), request.getCurrency());
                return createErrorResult(request, ReservationResult.Status.INVALID_AMOUNT,
                        "Невозможно зарезервировать запрошенную сумму имеющимися купюрами");
            }

            // Шаг 3: Резервирование купюр (изъятие из инвентаря)
            if (!reserveCashFromInventory(reserveCombination)) {
                log.error("Ошибка при резервировании купюр для клиента {}", request.getCustomerId());
                return createErrorResult(request, ReservationResult.Status.TECHNICAL_ERROR,
                        "Техническая ошибка при резервировании");
            }

            // Шаг 4: Генерация уникального QR кода
            String qrCode = generateQRCode();

            // Шаг 5: Создание и сохранение резервирования
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiresAt = now.plus(request.getTtl());

            Reservation reservation = Reservation.builder()
                    .qrCode(qrCode)
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .reservedCash(reserveCombination)
                    .customerId(request.getCustomerId())
                    .createdAt(now)
                    .expiresAt(expiresAt)
                    .metadata(request.getMetadata())
                    .build();

            activeReservations.put(qrCode, reservation);

            log.info("Резервирование {} {} создано успешно для клиента {}, QR: {}, истекает: {}", 
                    request.getAmount(), request.getCurrency(), request.getCustomerId(), 
                    qrCode, expiresAt);

            return ReservationResult.builder()
                    .status(ReservationResult.Status.SUCCESS)
                    .qrCode(qrCode)
                    .requestedAmount(request.getAmount())
                    .reservedAmount(request.getAmount())
                    .reservedCash(reserveCombination)
                    .expiresAt(expiresAt)
                    .timestamp(now)
                    .customerId(request.getCustomerId())
                    .build();

        } catch (Exception e) {
            log.error("Техническая ошибка при создании резервирования для клиента " + 
                    request.getCustomerId(), e);
            return createErrorResult(request, ReservationResult.Status.TECHNICAL_ERROR,
                    "Техническая ошибка: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Выдача денег по QR коду.
     * Проверяет валидность QR кода, истечение резервирования и выдает деньги.
     *
     * @param qrCode QR код резервирования
     * @return результат выдачи денег
     */
    public WithdrawalResult dispenseByQR(String qrCode) {
        log.info("Попытка выдачи денег по QR коду: {}", qrCode);

        lock.writeLock().lock();
        try {
            // Очищаем истекшие резервирования
            cleanupExpiredReservations();

            // Поиск резервирования по QR коду
            Reservation reservation = activeReservations.get(qrCode);
            if (reservation == null) {
                log.warn("QR код не найден или резервирование истекло: {}", qrCode);
                return createWithdrawalErrorResult(qrCode, WithdrawalResult.Status.TECHNICAL_ERROR,
                        "QR код недействителен или резервирование истекло");
            }

            // Проверка истечения резервирования
            if (reservation.isExpired()) {
                log.warn("Резервирование истекло для QR кода: {}", qrCode);
                // Возвращаем купюры в инвентарь
                returnReservedCash(reservation);
                activeReservations.remove(qrCode);
                return createWithdrawalErrorResult(qrCode, WithdrawalResult.Status.TECHNICAL_ERROR,
                        "Резервирование истекло");
            }

            // Выдача зарезервированных денег
            log.info("Выдача зарезервированных {} {} для клиента {} по QR: {}", 
                    reservation.getAmount(), reservation.getCurrency(), 
                    reservation.getCustomerId(), qrCode);

            // Удаляем резервирование (купюры уже зарезервированы, просто убираем из памяти)
            activeReservations.remove(qrCode);

            // Создаем результат успешной выдачи
            WithdrawalResult result = WithdrawalResult.builder()
                    .status(WithdrawalResult.Status.SUCCESS)
                    .requestedAmount(reservation.getAmount())
                    .dispensedAmount(reservation.getAmount())
                    .dispensedCash(reservation.getReservedCash())
                    .timestamp(LocalDateTime.now())
                    .sessionId("qr-" + qrCode)
                    .build();

            log.info("Успешно выданы зарезервированные {} {} по QR коду для клиента {}", 
                    reservation.getAmount(), reservation.getCurrency(), reservation.getCustomerId());

            return result;

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Получение доступного количества денег с учетом резервирований
     */
    private BigDecimal getAvailableAmount(Currency currency) {
        return cashInventoryService.getTotalAmount(currency);
    }

    /**
     * Поиск оптимального набора купюр для резервирования
     */
    private Map<Cash, Integer> findReservationCombination(Currency currency, BigDecimal amount) {
        // Делегируем логику поиска в WithdrawalService через рефлексию или создаем аналогичный алгоритм
        // Для простоты используем тот же алгоритм что и в WithdrawalService
        WithdrawalRequest tempRequest = WithdrawalRequest.builder()
                .currency(currency)
                .amount(amount)
                .sessionId("temp-reservation")
                .build();

        // Создаем временный результат только для получения комбинации купюр
        WithdrawalResult tempResult = withdrawalService.withdraw(tempRequest);
        
        if (tempResult.getStatus() == WithdrawalResult.Status.SUCCESS) {
            // Возвращаем купюры обратно в инвентарь (так как это была только симуляция)
            tempResult.getDispensedCash().forEach(cashInventoryService::returnCash);
            return tempResult.getDispensedCash();
        }
        
        return Collections.emptyMap();
    }

    /**
     * Резервирование купюр из инвентаря
     */
    private boolean reserveCashFromInventory(Map<Cash, Integer> combination) {
        // Резервируем купюры (фактически изымаем из инвентаря)
        for (Map.Entry<Cash, Integer> entry : combination.entrySet()) {
            Cash cash = entry.getKey();
            Integer count = entry.getValue();
            
            if (!cashInventoryService.dispenseCash(cash, count)) {
                // Если не удалось зарезервировать какие-то купюры, откатываем уже зарезервированные
                rollbackReservation(combination, cash);
                return false;
            }
        }
        return true;
    }

    /**
     * Откат резервирования при ошибке
     */
    private void rollbackReservation(Map<Cash, Integer> combination, Cash failedCash) {
        log.warn("Откат резервирования из-за ошибки с купюрой {}", failedCash);
        for (Map.Entry<Cash, Integer> entry : combination.entrySet()) {
            Cash cash = entry.getKey();
            Integer count = entry.getValue();
            
            // Возвращаем только те купюры, которые успели зарезервировать
            if (cash.equals(failedCash)) {
                break; // Эта и последующие купюры не были зарезервированы
            }
            cashInventoryService.returnCash(cash, count);
        }
    }

    /**
     * Возврат зарезервированных купюр в инвентарь
     */
    private void returnReservedCash(Reservation reservation) {
        log.info("Возврат зарезервированных купюр в инвентарь для QR: {}", reservation.getQrCode());
        reservation.getReservedCash().forEach(cashInventoryService::returnCash);
    }

    /**
     * Очистка истекших резервирований
     */
    private void cleanupExpiredReservations() {
        LocalDateTime now = LocalDateTime.now();
        activeReservations.entrySet().removeIf(entry -> {
            Reservation reservation = entry.getValue();
            if (reservation.isExpired()) {
                log.info("Удаление истекшего резервирования QR: {} для клиента {}", 
                        reservation.getQrCode(), reservation.getCustomerId());
                // Возвращаем купюры в инвентарь
                returnReservedCash(reservation);
                return true;
            }
            return false;
        });
    }

    /**
     * Генерация уникального QR кода
     */
    private String generateQRCode() {
        return "QR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    /**
     * Создание результата с ошибкой для резервирования
     */
    private ReservationResult createErrorResult(ReservationRequest request, 
                                              ReservationResult.Status status, 
                                              String errorMessage) {
        return ReservationResult.builder()
                .status(status)
                .requestedAmount(request.getAmount())
                .reservedAmount(BigDecimal.ZERO)
                .reservedCash(Collections.emptyMap())
                .timestamp(LocalDateTime.now())
                .customerId(request.getCustomerId())
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Создание результата с ошибкой для выдачи по QR
     */
    private WithdrawalResult createWithdrawalErrorResult(String qrCode, 
                                                       WithdrawalResult.Status status, 
                                                       String errorMessage) {
        return WithdrawalResult.builder()
                .status(status)
                .requestedAmount(BigDecimal.ZERO)
                .dispensedAmount(BigDecimal.ZERO)
                .dispensedCash(Collections.emptyMap())
                .timestamp(LocalDateTime.now())
                .sessionId("qr-" + qrCode)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Получение информации о всех активных резервированиях (для мониторинга)
     */
    public Map<String, Reservation> getActiveReservations() {
        lock.readLock().lock();
        try {
            cleanupExpiredReservations();
            return Map.copyOf(activeReservations);
        } finally {
            lock.readLock().unlock();
        }
    }
} 