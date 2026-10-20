package com.qlink.security.jwt;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
public final class TokenHash {
    private TokenHash() {}
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
