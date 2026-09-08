package com.palsaekjo.yogobi.common;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handle(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(new ErrorResponse(new ErrorResponse.Body(e.code(), e.getMessage(), e.field())));
    }

    /** 요청 본문이 깨졌거나 필수 구조가 없으면 필수 입력 누락으로 취급한다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400)
                .body(new ErrorResponse(new ErrorResponse.Body("YGB-REQ-001", "요청 본문을 해석할 수 없습니다.", null)));
    }
}
