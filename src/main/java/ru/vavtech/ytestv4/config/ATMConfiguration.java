package ru.vavtech.ytestv4.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.vavtech.ytestv4.service.CashInventoryService;

/**
 * Конфигурация банкомата.
 * Выполняет инициализацию при старте приложения.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ATMConfiguration {
    
    private final CashInventoryService cashInventoryService;
    
    /**
     * Инициализация банкомата при старте приложения
     */
    @Bean
    public CommandLineRunner initializeATM() {
        return args -> {
            log.info("Инициализация банкомата...");
            cashInventoryService.initializeInventory();
            log.info("Банкомат готов к работе");
        };
    }
} 