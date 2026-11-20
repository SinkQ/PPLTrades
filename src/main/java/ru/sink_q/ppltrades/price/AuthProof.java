package ru.sink_q.ppltrades.price;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

final class AuthProof {
    static final int VERSION = 2;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthProof() {}

    static String newNonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String derive(String challenge, String nonce) {
        if (challenge == null || !challenge.matches("[0-9a-f]{40}")
                || nonce == null || !nonce.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Некорректный запрос подтверждения PPLTrades");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("ppltrades-auth-v2:" + challenge + ":" + nonce).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest, 0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
