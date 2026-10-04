package org.example.seatreservation.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e){
        return ResponseEntity.status(e.getStatus()).body(Map.of("error",e.getCode(),"message",e.getMessage()));

    }

    @ExceptionHandler(HttpMessageConversionException.class)
    public  ResponseEntity<Map<String,Object>> handleBadJson(HttpMessageNotReadableException e){
        return ResponseEntity.badRequest().body(Map.of("error","bad_request","message","Malformed JSON body"));
    }
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Last line of defence. Anything unexpected is logged WITH its stack trace and returned as JSON,
// instead of a bare Tomcat error page.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception e) {
        // Spring's own request errors (405 wrong method, 415 wrong content type, ...) already know
        // their status. Without this branch they would all be squashed into 500s.
        if (e instanceof ErrorResponse er) {
            String msg = er.getBody().getDetail() != null ? er.getBody().getDetail() : "Request error";
            return ResponseEntity.status(er.getStatusCode()).body(Map.of("error", "http_error", "message", msg));
        }
        // couldn't get a DB connection (pool exhausted or DB down): honest 503, not a vague 500
        if (e instanceof DataAccessResourceFailureException) {
            log.error("Database unavailable", e);
            return ResponseEntity.status(503).header("Retry-After", "1")
                    .body(Map.of("error", "unavailable", "message", "Database unavailable, retry shortly"));
        }
        log.error("Unhandled exception", e);
        return ResponseEntity.status(500).body(Map.of("error", "internal", "message", "Unexpected error"));
    }
}
