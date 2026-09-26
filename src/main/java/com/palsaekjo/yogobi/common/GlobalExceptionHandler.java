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

    /**
     * 요청 본문이 깨졌거나 필수 구조가 없으면 필수 입력 누락으로 취급한다. 문구는 사용자가 할 수 있는 일을 말한다 —
     * 여기 닿는 건 대개 오래 열어 둔 화면이라, "해석할 수 없다"보다 "다시 골라 달라"가 쓸모 있다(G-74).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400)
                .body(new ErrorResponse(new ErrorResponse.Body("YGB-REQ-001", RETRY, null)));
    }

    /** 경로·쿼리 값의 형식이 틀렸거나 빠졌다. 어느 칸인지는 field 로 준다(G-74 d). */
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleBadParameter(Exception e) {
        String field = e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException m ? m.getName()
                : ((org.springframework.web.bind.MissingServletRequestParameterException) e).getParameterName();
        return ResponseEntity.status(400)
                .body(new ErrorResponse(new ErrorResponse.Body("YGB-REQ-001", RETRY, field)));
    }

    private static final String RETRY = "입력값을 읽지 못했어요. 화면을 새로 고친 뒤 다시 골라 주세요.";
}
