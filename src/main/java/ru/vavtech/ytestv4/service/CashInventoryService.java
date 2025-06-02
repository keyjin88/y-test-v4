package ru.vavtech.ytestv4.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.vavtech.ytestv4.model.Cash;
import ru.vavtech.ytestv4.model.Currency;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Сервис для управления инвентарем денег в банкомате.
 * Обеспечивает thread-safety через использование concurrent коллекций и блокировок.
 */
@Slf4j
@Service
public class CashInventoryService {
    
    private final Map<Cash, Integer> inventory = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    /**
     * Инициализация банкомата начальным набором купюр
     */
    public void initializeInventory() {
        lock.writeLock().lock();
        try {
            // Инициализация рублевыми купюрами
            inventory.put(new Cash(Currency.RUB, new BigDecimal("5000")), 10);
            inventory.put(new Cash(Currency.RUB, new BigDecimal("1000")), 50);
            inventory.put(new Cash(Currency.RUB, new BigDecimal("500")), 100);
            inventory.put(new Cash(Currency.RUB, new BigDecimal("100")), 200);
            
            // Инициализация долларовыми купюрами
            inventory.put(new Cash(Currency.USD, new BigDecimal("100")), 20);
            inventory.put(new Cash(Currency.USD, new BigDecimal("50")), 30);
            inventory.put(new Cash(Currency.USD, new BigDecimal("20")), 50);
            inventory.put(new Cash(Currency.USD, new BigDecimal("10")), 100);
            
            log.info("Инвентарь банкомата инициализирован");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Получение текущего количества купюр определенного номинала
     */
    public int getCount(Cash cash) {
        lock.readLock().lock();
        try {
            return inventory.getOrDefault(cash, 0);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Проверка возможности выдачи указанного количества купюр
     */
    public boolean canDispense(Cash cash, int count) {
        lock.readLock().lock();
        try {
            return getCount(cash) >= count;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Выдача купюр (уменьшение инвентаря)
     * @param cash тип купюры
     * @param count количество
     * @return true если операция прошла успешно
     */
    public boolean dispenseCash(Cash cash, int count) {
        lock.writeLock().lock();
        try {
            int currentCount = inventory.getOrDefault(cash, 0);
            if (currentCount >= count) {
                inventory.put(cash, currentCount - count);
                log.debug("Выдано {} купюр номиналом {}", count, cash);
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Получение копии текущего состояния инвентаря
     */
    public Map<Cash, Integer> getCurrentInventory() {
        lock.readLock().lock();
        try {
            return Map.copyOf(inventory);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Получение общей суммы по валюте
     */
    public BigDecimal getTotalAmount(Currency currency) {
        lock.readLock().lock();
        try {
            return inventory.entrySet().stream()
                    .filter(entry -> entry.getKey().currency() == currency)
                    .map(entry -> entry.getKey().denomination().multiply(BigDecimal.valueOf(entry.getValue())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } finally {
            lock.readLock().unlock();
        }
    }
} 