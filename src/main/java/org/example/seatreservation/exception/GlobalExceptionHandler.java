package org.example.seatreservation.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
}
