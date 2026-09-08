package com.palsaekjo.yogobi.common;

import java.util.List;

/** 성공 응답 래퍼. docs/architecture.md §5: { "data": {...}, "warnings": [...] } */
public record ApiResponse<T>(T data, List<Warning> warnings) {
    public record Warning(String code, String message) {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, List.of());
    }
}
