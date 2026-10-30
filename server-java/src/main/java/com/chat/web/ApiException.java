package com.chat.web;

/** Application error mapped to an HTTP status plus a stable code for the UI. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "AUTH_ACCESS_DENIED", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "RES_001", message);
    }

    public static ApiException invalid(String message) {
        return new ApiException(400, "VAL_001", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, "AUTH_INVALID_CREDENTIALS", message);
    }
}
