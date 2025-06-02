package ru.vavtech.ytestv4.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Глобальный обработчик исключений для REST API банкомата.
 * Обеспечивает единообразную обработку ошибок.
 */
@Slf4j
@ControllerAdvice
public class ATMExceptionHandler {
    
    /**
     * Обработка общих исключений
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex, WebRequest request) {
        log.error("Необработанная ошибка в банкомате", ex);
        
        Map<String, Object> errorDetails = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "error", "Техническая ошибка банкомата",
                "message", "Попробуйте позже или обратитесь в службу поддержки",
                "path", request.getDescription(false)
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorDetails);
    }
    
    /**
     * Обработка ошибок валидации входных данных
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(IllegalArgumentException ex, WebRequest request) {
        log.warn("Ошибка валидации: {}", ex.getMessage());
        
        Map<String, Object> errorDetails = Map.of(
                "timestamp", LocalDateTime.now(),
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", "Ошибка в данных запроса",
                "message", ex.getMessage(),
                "path", request.getDescription(false)
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorDetails);
    }
} 