package com.palsaekjo.yogobi.common;

/** docs/architecture.md §5 에러 코드. code·HTTP status·field 를 함께 실어 GlobalExceptionHandler 가 렌더링한다. */
public class ApiException extends RuntimeException {
    private final String code;
    private final int status;
    private final String field;

    public ApiException(String code, int status, String message, String field) {
        super(message);
        this.code = code;
        this.status = status;
        this.field = field;
    }

    public static ApiException requiredMissing(String field, String message) {
        return new ApiException("YGB-REQ-001", 400, message, field);
    }

    public static ApiException noCandidate(String message) {
        return new ApiException("YGB-CAL-001", 422, message, null);
    }

    public static ApiException planNotFound(String message) {
        return new ApiException("YGB-CAT-001", 404, message, null);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public String field() {
        return field;
    }
}
