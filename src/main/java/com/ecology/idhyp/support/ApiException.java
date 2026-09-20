package com.ecology.idhyp.support;

import java.util.List;
import java.util.Map;

/** Carries an HTTP status and a structured payload for the global error handler. */
public class ApiException extends RuntimeException {
    private final int status;
    private final transient Map<String, Object> body;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
        this.body = Map.of("error", message);
    }

    public ApiException(int status, String message, Map<String, Object> body) {
        super(message);
        this.status = status;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    public Map<String, Object> getBody() {
        return body;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException unprocessable(String message, List<Map<String, Object>> conflicts) {
        return new ApiException(422, message, Map.of("error", message, "conflicts", conflicts));
    }
}
