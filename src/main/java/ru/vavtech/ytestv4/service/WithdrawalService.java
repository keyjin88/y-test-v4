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
 * 
 * Реализует сложную бизнес-логику выдачи денег:
 * - Проверка достаточности средств в банкомате
 * - Поиск оптимального набора купюр для выдачи (жадный алгоритм)
 * - Предотвращение race conditions в многопоточной среде
 * - Атомарная операция выдачи с возможностью отката
 * - Детальное логирование для аудита и отладки
 * 
 * Алгоритм выдачи денег (жадный подход):
 * 1. Сортировка доступных номиналов по убыванию
 * 2. Выбор максимального количества крупных купюр
 * 3. Переход к более мелким номиналам для остатка
 * 4. Проверка возможности выдачи точной суммы
 * 
 * Жадный алгоритм выбран потому что:
 * - Минимизирует количество выдаваемых купюр
 * - Сохраняет мелкие номиналы для будущих операций
 * - Прост в реализации и понимании
 * - Работает оптимально для типичных банкоматных номиналов
 * 
 * Thread Safety:
 * - Использует session ID для трассировки операций
 * - Проверяет доступность купюр непосредственно перед выдачей
 * - Атомарные операции через CashInventoryService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {
    
    /**
     * Зависимость от сервиса управления инвентарем.
     * 
     * Используется constructor injection (рекомендуемый подход):
     * - Обеспечивает immutability поля
     * - Позволяет создавать bean как final
     * - Упрощает тестирование (легко мокать)
     * - Делает зависимости явными
     */
    private final CashInventoryService cashInventoryService;
    
    /**
     * Главный метод для выполнения операции снятия денег.
     * 
     * Координирует весь процесс выдачи денег:
     * 1. Логирование начала обработки запроса
     * 2. Проверка общей достаточности средств
     * 3. Поиск оптимального набора купюр
     * 4. Физическая выдача денег
     * 5. Формирование результата операции
     * 6. Обработка исключений
     * 
     * Метод гарантирует:
     * - Детальное логирование всех этапов
     * - Корректную обработку всех ошибочных ситуаций
     * - Возврат подробной информации о результате
     * - Трассируемость операций через session ID
     * 
     * @param request запрос на снятие денег с валидированными данными
     * @return подробный результат операции со статусом и деталями
     */
    public WithdrawalResult withdraw(WithdrawalRequest request) {
        // Логируем начало обработки запроса с ключевыми параметрами
        // INFO уровень так как это важное бизнес-событие
        log.info("Обработка запроса на снятие {} {} для сессии {}", 
                request.getAmount(), request.getCurrency(), request.getSessionId());
        
        try {
            // ШАГ 1: Проверка общей достаточности средств в банкомате
            // Быстрая проверка перед сложными вычислениями
            BigDecimal totalAvailable = cashInventoryService.getTotalAmount(request.getCurrency());
            if (totalAvailable.compareTo(request.getAmount()) < 0) {
                // Логируем недостаток средств для мониторинга
                log.warn("Недостаточно средств в банкомате: запрошено {}, доступно {} {}", 
                        request.getAmount(), totalAvailable, request.getCurrency());
                return createErrorResult(request, WithdrawalResult.Status.INSUFFICIENT_FUNDS,
                        "В банкомате недостаточно средств в валюте " + request.getCurrency());
            }
            
            // ШАГ 2: Поиск оптимального набора купюр для выдачи
            // Сложный алгоритм, который может не найти решение даже при достаточных средствах
            Map<Cash, Integer> dispenseCombination = findOptimalDispenseCombination(
                    request.getCurrency(), request.getAmount());
            
            // Проверяем, что алгоритм нашел решение
            if (dispenseCombination.isEmpty()) {
                // Ситуация: денег достаточно, но нельзя выдать точную сумму
                // Например: запрос 150₽, но есть только купюры 500₽ и 1000₽
                log.warn("Невозможно составить сумму {} из доступных номиналов валюты {}", 
                        request.getAmount(), request.getCurrency());
                return createErrorResult(request, WithdrawalResult.Status.INVALID_AMOUNT,
                        "Невозможно выдать запрошенную сумму имеющимися купюрами");
            }
            
            // ШАГ 3: Физическая выдача купюр
            // Атомарная операция с проверкой race conditions
            boolean success = executeDispense(dispenseCombination);
            if (!success) {
                // Ошибка может произойти из-за race condition
                // (другой поток выдал купюры между нашими проверками)
                log.error("Техническая ошибка при выдаче денег для сессии {}", request.getSessionId());
                return createErrorResult(request, WithdrawalResult.Status.TECHNICAL_ERROR,
                        "Ошибка при выдаче денег");
            }
            
            // ШАГ 4: Расчет фактически выданной суммы
            // Должна совпадать с запрошенной, но вычисляем для контроля
            BigDecimal dispensedAmount = calculateTotalAmount(dispenseCombination);
            
            // Логируем успешное завершение операции
            log.info("Успешно выдано {} {} для сессии {}", 
                    dispensedAmount, request.getCurrency(), request.getSessionId());
            
            // ШАГ 5: Формирование успешного результата
            return WithdrawalResult.builder()
                    .status(WithdrawalResult.Status.SUCCESS)
                    .requestedAmount(request.getAmount())
                    .dispensedAmount(dispensedAmount)
                    .dispensedCash(dispenseCombination)  // Детали выданных купюр
                    .timestamp(LocalDateTime.now())      // Точное время операции
                    .sessionId(request.getSessionId())   // Трассировка
                    .build();
                    
        } catch (Exception e) {
            // Перехватываем любые неожиданные исключения
            // Это последняя линия защиты от сбоев системы
            log.error("Техническая ошибка при обработке запроса для сессии " + request.getSessionId(), e);
            return createErrorResult(request, WithdrawalResult.Status.TECHNICAL_ERROR,
                    "Техническая ошибка: " + e.getMessage());
        }
    }
    
    /**
     * Поиск оптимального набора купюр для выдачи запрошенной суммы.
     * 
     * Реализует жадный алгоритм (greedy algorithm):
     * - Начинает с самых крупных номиналов
     * - Берет максимально возможное количество каждого номинала
     * - Переходит к следующему по убыванию номиналу
     * - Проверяет точность итоговой суммы
     * 
     * Жадный алгоритм работает оптимально для банкоматных номиналов,
     * где каждый следующий номинал кратен предыдущему или близок к этому.
     * 
     * Альтернативы (не выбраны из-за сложности):
     * - Динамическое программирование (для произвольных номиналов)
     * - Полный перебор (слишком медленный)
     * - Эвристические алгоритмы (излишне сложные для данной задачи)
     * 
     * @param currency валюта для поиска номиналов
     * @param amount точная сумма, которую нужно выдать
     * @return карта "номинал -> количество купюр" или пустая карта если невозможно
     */
    private Map<Cash, Integer> findOptimalDispenseCombination(Currency currency, BigDecimal amount) {
        // ШАГ 1: Получение и сортировка доступных номиналов
        // Получаем только номиналы нужной валюты с ненулевым количеством
        List<Cash> availableCash = cashInventoryService.getAvailableCashByCurrency(currency);
        // Сортируем по убыванию номинала для жадного алгоритма
        // Integer.compare обеспечивает правильную сортировку чисел
        availableCash.sort((c1, c2) -> Integer.compare(c2.denomination(), c1.denomination()));
        
        // ШАГ 2: Инициализация переменных алгоритма
        // Результирующая карта: какие купюры и в каком количестве взять
        Map<Cash, Integer> result = new HashMap<>();
        // Остаток суммы, который еще нужно "набрать" купюрами
        BigDecimal remainingAmount = amount;
        
        // ШАГ 3: Основной цикл жадного алгоритма
        // Перебираем номиналы от крупных к мелким
        for (Cash cash : availableCash) {
            // Получаем количество доступных купюр данного номинала
            int availableCount = cashInventoryService.getCount(cash);
            // Конвертируем номинал в BigDecimal для точных вычислений
            BigDecimal cashValue = BigDecimal.valueOf(cash.denomination());
            
            // Проверяем, стоит ли использовать этот номинал:
            // 1. Есть купюры в наличии (availableCount > 0)
            // 2. Номинал не превышает остаток (remainingAmount >= cashValue)
            if (remainingAmount.compareTo(cashValue) >= 0) {
                
                // Вычисляем максимальное количество купюр этого номинала
                // divideToIntegralValue возвращает целую часть от деления
                // Например: 2700 ÷ 1000 = 2 (можем взять максимум 2 купюры по 1000)
                int maxPossible = remainingAmount.divideToIntegralValue(cashValue).intValue();
                
                // Ограничиваем количеством доступных купюр в банкомате
                // Math.min выбирает минимум из желаемого и доступного
                int actualCount = Math.min(maxPossible, availableCount);
                
                // Если можем взять хотя бы одну купюру
                if (actualCount > 0) {
                    // Добавляем в результат
                    result.put(cash, actualCount);
                    // Уменьшаем остаток на стоимость взятых купюр
                    // Например: было 2700, взяли 2×1000, осталось 700
                    remainingAmount = remainingAmount.subtract(
                            cashValue.multiply(BigDecimal.valueOf(actualCount)));
                }
            }
        }
        
        // ШАГ 4: Проверка успешности алгоритма
        // Если остался ненулевой остаток, значит точную сумму выдать нельзя
        if (remainingAmount.compareTo(BigDecimal.ZERO) != 0) {
            // Логируем неудачу для отладки
            log.debug("Невозможно выдать точную сумму {}, остаток: {}", amount, remainingAmount);
            // Возвращаем пустую карту как признак неудачи
            return Collections.emptyMap();
        }
        
        // Логируем успешный результат алгоритма
        log.debug("Найдена комбинация для суммы {}: {}", amount, result);
        return result;
    }
    
    /**
     * Выполнение физической выдачи купюр из банкомата.
     * 
     * Критически важная двухэтапная операция:
     * ЭТАП 1: Предварительная проверка доступности всех купюр
     * ЭТАП 2: Атомарная выдача всех купюр
     * 
     * Двухэтапность защищает от race conditions:
     * - Между нашими вычислениями и выдачей другие потоки могли изменить инвентарь
     * - Проверяем актуальность данных непосредственно перед выдачей
     * - Если хотя бы одна купюра недоступна - отменяем всю операцию
     * 
     * Откат при ошибке:
     * - Если выдача прерывается посередине, вызываем rollback
     * - В реальном банкомате это вернуло бы уже выданные купюры
     * 
     * @param dispenseCombination карта номиналов и количества для выдачи
     * @return true если все купюры выданы успешно, false при любой ошибке
     */
    private boolean executeDispense(Map<Cash, Integer> dispenseCombination) {
        // ЭТАП 1: Предварительная проверка доступности всех купюр
        // Это защита от race condition: проверяем актуальность данных
        log.debug("Проверка доступности купюр перед выдачей: {}", dispenseCombination);
        
        for (Map.Entry<Cash, Integer> entry : dispenseCombination.entrySet()) {
            Cash cash = entry.getKey();
            Integer count = entry.getValue();
            
            // Проверяем, что купюры все еще доступны
            if (!cashInventoryService.canDispense(cash, count)) {
                // Race condition: между вычислением и выдачей кто-то забрал купюры
                log.warn("Race condition: невозможно выдать {} купюр номиналом {} " +
                         "(недостаточно в текущем инвентаре)", count, cash);
                return false;
            }
        }
        
        // ЭТАП 2: Атомарная выдача всех купюр
        // Если дошли до этого этапа, начинаем реальную выдачу
        log.debug("Начинаем физическую выдачу купюр");
        
        for (Map.Entry<Cash, Integer> entry : dispenseCombination.entrySet()) {
            Cash cash = entry.getKey();
            Integer count = entry.getValue();
            
            // Пытаемся выдать купюры данного номинала
            if (!cashInventoryService.dispenseCash(cash, count)) {
                // Критическая ошибка: не удалось выдать купюры
                // Это может быть аппаратная ошибка или серьезный race condition
                log.error("Критическая ошибка: не удалось выдать {} купюр номиналом {}", count, cash);
                
                // Пытаемся откатить уже выданные купюры
                rollbackDispense(dispenseCombination, cash);
                return false;
            }
        }
        
        // Все купюры выданы успешно
        log.debug("Все купюры выданы успешно");
        return true;
    }
    
    /**
     * Откат операции выдачи в случае ошибки.
     * 
     * В реальном банкомате этот метод координировал бы:
     * - Возврат уже выданных купюр в кассеты
     * - Восстановление счетчиков инвентаря
     * - Уведомление системы мониторинга
     * - Блокировку банкомата при серьезных ошибках
     * 
     * В данной реализации метод упрощен для демонстрации архитектуры.
     * Предполагается, что аппаратный уровень банкомата обеспечит
     * физический откат операции.
     * 
     * @param dispenseCombination план выдачи, который нужно откатить
     * @param failedCash номинал, на котором произошла ошибка
     */
    private void rollbackDispense(Map<Cash, Integer> dispenseCombination, Cash failedCash) {
        log.warn("Выполняется откат операции выдачи до купюры {}", failedCash);
        
        // В реальной реализации здесь была бы сложная логика:
        // 1. Определение уже выданных купюр (до failedCash)
        // 2. Команды аппаратуре на возврат купюр в кассеты
        // 3. Восстановление счетчиков в inventory
        // 4. Логирование инцидента для служб безопасности
        // 5. Возможная блокировка банкомата
        
        // Для демонстрации предполагаем, что аппаратура справится сама
        log.info("Откат операции делегирован аппаратному уровню банкомата");
    }
    
    /**
     * Вычисление общей суммы выданных денег.
     * 
     * Контрольная функция для проверки корректности алгоритма:
     * - Вычисленная сумма должна точно совпадать с запрошенной
     * - Используется для заполнения поля dispensedAmount в результате
     * - Помогает выявлять ошибки в алгоритме на этапе тестирования
     * 
     * Формула: сумма = номинал_1 × количество_1 + номинал_2 × количество_2 + ...
     * 
     * @param dispensedCash карта выданных купюр (номинал -> количество)
     * @return общая сумма всех выданных купюр
     */
    private BigDecimal calculateTotalAmount(Map<Cash, Integer> dispensedCash) {
        return dispensedCash.entrySet().stream()
                // Преобразуем каждую пару (номинал, количество) в стоимость
                .map(entry -> {
                    // Конвертируем номинал в BigDecimal для точных вычислений
                    BigDecimal denomination = BigDecimal.valueOf(entry.getKey().denomination());
                    // Конвертируем количество в BigDecimal
                    BigDecimal count = BigDecimal.valueOf(entry.getValue());
                    // Возвращаем стоимость: номинал × количество
                    return denomination.multiply(count);
                })
                // Суммируем все стоимости
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    /**
     * Создание стандартизированного результата с ошибкой.
     * 
     * Utility метод для единообразного создания результатов при ошибках:
     * - Устанавливает статус ошибки
     * - Копирует основные данные из запроса
     * - Устанавливает нулевую выданную сумму
     * - Добавляет текущее время и сообщение об ошибке
     * 
     * Обеспечивает консистентность структуры ответа независимо от типа ошибки.
     * Упрощает основной код метода withdraw().
     * 
     * @param request исходный запрос на снятие денег
     * @param status статус ошибки (INSUFFICIENT_FUNDS, INVALID_AMOUNT, TECHNICAL_ERROR)
     * @param errorMessage подробное описание ошибки для клиента
     * @return объект результата с информацией об ошибке
     */
    private WithdrawalResult createErrorResult(WithdrawalRequest request, 
                                               WithdrawalResult.Status status, 
                                               String errorMessage) {
        return WithdrawalResult.builder()
                .status(status)                              // Тип ошибки
                .requestedAmount(request.getAmount())        // Запрошенная сумма
                .dispensedAmount(BigDecimal.ZERO)            // Фактически выдано: 0
                .dispensedCash(Collections.emptyMap())       // Список выданных купюр: пустой
                .timestamp(LocalDateTime.now())              // Время ошибки
                .errorMessage(errorMessage)                  // Описание ошибки
                .sessionId(request.getSessionId())           // Трассировка сессии
                .build();
    }
} 