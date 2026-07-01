package com.nectarsoft.meetai.core.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MeetAiException.class)
    public ResponseEntity<Map<String, Object>> handleMeetAi(MeetAiException ex) {
        log.error("[{}] {}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "code", ex.getCode(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", "EX-400",
                "message", "필수 헤더 누락: " + ex.getHeaderName()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError().body(Map.of(
                "code", "EX-000",
                "message", "서버 내부 오류: " + ex.getMessage()
        ));
    }
}
