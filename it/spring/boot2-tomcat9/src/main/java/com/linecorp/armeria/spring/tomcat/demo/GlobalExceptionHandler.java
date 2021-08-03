package com.linecorp.armeria.spring.tomcat.demo;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.google.common.collect.ImmutableMap;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(GlobalBaseException.class)
    public ResponseEntity<Map<String, Object>> onGlobalBaseException(Throwable t) {
        final Map<String, Object> body = ImmutableMap.<String, Object>builder()
                                                     .put("status", HttpStatus.INTERNAL_SERVER_ERROR.value())
                                                     .put("message", t.getMessage())
                                                     .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
