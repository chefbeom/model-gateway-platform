package com.aiconnect.llmgateway.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> handleApiException(ApiException exception, HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/v1/")) {
            return ResponseEntity.status(exception.getStatus()).body(OpenAiError.of(exception.getMessage(), "invalid_request_error", exception.getCode(), null));
        }
        return ResponseEntity.status(exception.getStatus()).body(Map.of("code", exception.getCode(), "message", exception.getMessage()));
    }
}
