package com.palsaekjo.yogobi.common;

/** 에러 응답. docs/architecture.md §5: { "error": { "code", "message", "field" } } */
public record ErrorResponse(Body error) {
    public record Body(String code, String message, String field) {
    }
}
