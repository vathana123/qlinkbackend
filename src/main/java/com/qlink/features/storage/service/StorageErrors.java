package com.qlink.features.storage.service;
import com.qlink.common.exception.ApiException;
import org.springframework.http.HttpStatus;
public final class StorageErrors {
    private StorageErrors() {}
    public static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_FILE",message); }
    public static ApiException tooLarge() { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"FILE_TOO_LARGE","File exceeds the configured maximum size"); }
    public static ApiException unsupported() { return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"UNSUPPORTED_FILE_TYPE","File content or filename extension is not supported"); }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"FILE_NOT_FOUND","File is unavailable"); }
    public static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE","File storage is temporarily unavailable"); }
}
