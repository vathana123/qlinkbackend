package com.qlink.common.response;
import java.util.Map;
public record ApiError(boolean success, String message, Details data) {
    public record Details(String code, Map<String, String> fields) {}
    public static ApiError of(String code, String message) {
        return new ApiError(false, message, new Details(code, Map.of()));
    }
}
