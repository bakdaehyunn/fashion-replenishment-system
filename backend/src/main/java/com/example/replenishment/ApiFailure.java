package com.example.replenishment;

import java.util.LinkedHashMap;
import java.util.Map;

final class ApiFailure extends RuntimeException {
    private final int status;
    private final Map<String, Object> body;

    ApiFailure(int status, String code) {
        super(code);
        this.status = status;
        this.body = new LinkedHashMap<>(Map.of("code", code, "message", code));
    }

    int status() { return status; }
    Map<String, Object> body() { return body; }

    ApiFailure with(String key, Object value) {
        body.put(key, value);
        return this;
    }

    static ApiFailure invalid() {
        return new ApiFailure(400, "VALIDATION_ERROR");
    }
}
