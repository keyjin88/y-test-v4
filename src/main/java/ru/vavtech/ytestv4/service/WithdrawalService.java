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
 * <p>
 * Реализует сложную бизнес-логику выдачи денег:
 * - Проверка достаточности средств в банкомате
 * - Поиск оптимального набора купюр для выдачи (жадный алгоритм)
 * - Предотвращение race conditions в многопоточной среде
 * - Атомарная операция выдачи с возможностью отката
 * - Детальное логирование для аудита и отладки
 * <p>
 * Алгоритм выдачи денег (жадный подход):
 * 1. Сортировка доступных номиналов по убыванию
 * 2. Выбор максимального количества крупных купюр
 * 3. Переход к более мелким номиналам для остатка
 * 4. Проверка возможности выдачи точной суммы
 * <p>
 * Жадный алгоритм выбран потому что:
 * - Минимизирует количество выдаваемых купюр
 * - Сохраняет мелкие номиналы для будущих операций
 * - Прост в реализации и понимании
 * - Работает оптимально для типичных банкоматных номиналов
 * <p>
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
     * <p>
     * Используется constructor injection (рекомендуемый подход):
     * - Обеспечивает immutability поля
     * - Позволяет создавать bean как final
     * - Упрощает тестирование (легко мокать)
     * - Делает зависимости явными
     */
    private final CashInventoryService cashInventoryService;

    /**
     * Главный метод для выполнения операции снятия денег.
     * <p>
     * Координирует весь процесс выдачи денег:
     * 1. Логирование начала обработки запроса
     * 2. Проверка общей достаточности средств
     * 3. Поиск оптимального набора купюр
     * 4. Физическая выдача денег
     * 5. Формирование результата операции
     * 6. Обработка исключений
     * <p>
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
            // Сложный алгоритм, который может не найти решение даже при достаточных
            // средствах
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
                    .dispensedCash(dispenseCombination) // Детали выданных купюр
                    .timestamp(LocalDateTime.now()) // Точное время операции
                    .sessionId(request.getSessionId()) // Трассировка
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
     * <p>
     * Реализует жадный алгоритм (greedy algorithm):
     * - Начинает с самых крупных номиналов
     * - Берет максимально возможное количество каждого номинала
     * - Переходит к следующему по убыванию номиналу
     * - Проверяет точность итоговой суммы
     * <p>
     * Жадный алгоритм работает оптимально для банкоматных номиналов,
     * где каждый следующий номинал кратен предыдущему или близок к этому.
     * <p>
     * Альтернативы (не выбраны из-за сложности):
     * - Динамическое программирование (для произвольных номиналов)
     * - Полный перебор (слишком медленный)
     * - Эвристические алгоритмы (излишне сложные для данной задачи)
     *
     * @param currency валюта для поиска номиналов
     * @param amount   точная сумма, которую нужно выдать
     * @return карта "номинал -> количество купюр" или пустая карта если невозможно
     */
    private Map<Cash, Integer> findOptimalDispenseCombination(Currency currency, BigDecimal amount) {
        // ШАГ 1: Получение и сортировка доступных номиналов
        // Получаем только номиналы нужной валюты с ненулевым количеством
        List<Cash> availableCash = getAvailableCashByCurrency(currency);
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
     * Получение списка доступных купюр для указанной валюты.
     * <p>
     * Фильтрует общий инвентарь банкомата по критериям:
     * - Совпадение валюты
     * - Ненулевое количество купюр
     * <p>
     * Возвращает только те номиналы, которые реально можно использовать
     * в алгоритме поиска комбинации.
     *
     * @param currency валюта для фильтрации номиналов
     * @return список доступных номиналов указанной валюты
     */
    private List<Cash> getAvailableCashByCurrency(Currency currency) {
        return cashInventoryService.getCurrentInventory().entrySet().stream()
                // Фильтр 1: только нужная валюта
                // Фильтр 2: только номиналы с ненулевым количеством
                .filter(entry -> entry.getKey().currency() == currency && entry.getValue() > 0)
                // Извлекаем только ключи (объекты Cash) из пар ключ-значение
                .map(Map.Entry::getKey)
                // Собираем в изменяемый список для последующей сортировки
                .collect(Collectors.toList());
    }

    /**
     * Выполнение физической выдачи купюр из банкомата.
     * <p>
     * Критически важная двухэтапная операция с возможностью отката:
     * ЭТАП 1: Предварительная проверка доступности всех купюр
     * ЭТАП 2: Атомарная выдача всех купюр с отслеживанием прогресса
     *
     * @param dispenseCombination карта номиналов и количества для выдачи
     * @return true если все купюры выданы успешно, false при любой ошибке
     */
    private boolean executeDispense(Map<Cash, Integer> dispenseCombination) {
        // ЭТАП 1: Предварительная проверка доступности всех купюр
        log.debug("Проверка доступности купюр перед выдачей: {}", dispenseCombination);

        for (Map.Entry<Cash, Integer> entry : dispenseCombination.entrySet()) {
            Cash cash = entry.getKey();
            Integer count = entry.getValue();

            if (!cashInventoryService.canDispense(cash, count)) {
                log.warn("Race condition: невозможно выдать {} купюр номиналом {} " +
                        "(недостаточно в текущем инвентаре)", count, cash);
                return false;
            }
        }

        // ЭТАП 2: Атомарная выдача всех купюр с отслеживанием прогресса
        log.debug("Начинаем физическую выдачу купюр");

        // Карта для отслеживания уже выданных купюр (для возможного отката)
        Map<Cash, Integer> dispensedSoFar = new HashMap<>();

        for (Map.Entry<Cash, Integer> entry : dispenseCombination.entrySet()) {
            Cash cash = entry.getKey();
            Integer count = entry.getValue();

            // Пытаемся выдать купюры данного номинала
            if (!cashInventoryService.dispenseCash(cash, count)) {
                // Критическая ошибка: не удалось выдать купюры
                log.error("Критическая ошибка: не удалось выдать {} купюр номиналом {}", count, cash);

                // Выполняем откат уже выданных купюр
                rollbackDispense(dispensedSoFar);
                return false;
            }

            // Запоминаем успешно выданные купюры для возможного отката
            dispensedSoFar.put(cash, count);
        }

        log.debug("Все купюры выданы успешно");
        return true;
    }

    /**
     * Откат операции выдачи в случае ошибки.
     * <p>
     * Возвращает уже выданные купюры обратно в инвентарь банкомата.
     * Этот метод критически важен для поддержания консистентности
     * состояния инвентаря при частичных сбоях операций.
     *
     * @param dispensedSoFar карта уже выданных купюр, которые нужно вернуть
     */
    private void rollbackDispense(Map<Cash, Integer> dispensedSoFar) {
        if (dispensedSoFar.isEmpty()) {
            log.info("Откат не требуется - купюры еще не выдавались");
            return;
        }

        log.warn("Выполняется откат операции выдачи. Возвращаем купюры: {}", dispensedSoFar);

        // Возвращаем каждый тип купюр обратно в инвентарь
        for (Map.Entry<Cash, Integer> entry : dispensedSoFar.entrySet()) {
            Cash cash = entry.getKey();
            Integer count = entry.getValue();

            try {
                // Возвращаем купюры в инвентарь
                cashInventoryService.returnCash(cash, count);
                log.debug("Возвращено {} купюр номиналом {} в инвентарь", count, cash);
            } catch (Exception e) {
                // Критическая ошибка системы: не можем даже вернуть купюры
                log.error("КРИТИЧЕСКАЯ ОШИБКА: Не удалось вернуть {} купюр номиналом {} в инвентарь. " +
                        "Требуется ручное вмешательство!", count, cash, e);
            }
        }

        log.warn("Откат операции выдачи завершен");
    }

    /**
     * Вычисление общей суммы выданных денег.
     * <p>
     * Контрольная функция для проверки корректности алгоритма:
     * - Вычисленная сумма должна точно совпадать с запрошенной
     * - Используется для заполнения поля dispensedAmount в результате
     * - Помогает выявлять ошибки в алгоритме на этапе тестирования
     * <p>
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
     * <p>
     * Utility метод для единообразного создания результатов при ошибках:
     * - Устанавливает статус ошибки
     * - Копирует основные данные из запроса
     * - Устанавливает нулевую выданную сумму
     * - Добавляет текущее время и сообщение об ошибке
     * <p>
     * Обеспечивает консистентность структуры ответа независимо от типа ошибки.
     * Упрощает основной код метода withdraw().
     *
     * @param request      исходный запрос на снятие денег
     * @param status       статус ошибки (INSUFFICIENT_FUNDS, INVALID_AMOUNT,
     *                     TECHNICAL_ERROR)
     * @param errorMessage подробное описание ошибки для клиента
     * @return объект результата с информацией об ошибке
     */
    private WithdrawalResult createErrorResult(WithdrawalRequest request,
                                               WithdrawalResult.Status status,
                                               String errorMessage) {
        return WithdrawalResult.builder()
                .status(status) // Тип ошибки
                .requestedAmount(request.getAmount()) // Запрошенная сумма
                .dispensedAmount(BigDecimal.ZERO) // Фактически выдано: 0
                .dispensedCash(Collections.emptyMap()) // Список выданных купюр: пустой
                .timestamp(LocalDateTime.now()) // Время ошибки
                .errorMessage(errorMessage) // Описание ошибки
                .sessionId(request.getSessionId()) // Трассировка сессии
                .build();
    }
}