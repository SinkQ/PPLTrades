package ru.sink_q.ppltrades.price;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Проверка разбора адресов прокси (запуск: gradlew testApiTransport, входит в gradlew test). */
public final class ApiTransportTest {
    private static int failures;

    public static void main(String[] args) {
        String challenge = "0123456789abcdef0123456789abcdef01234567";
        String nonce = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
        if (!"9adc201261739e42e1bee1e8eb5d91ec5d7de7c3".equals(AuthProof.derive(challenge, nonce))
                || !AuthProof.newNonce().matches("[0-9a-f]{64}")
                || AuthProof.derive(challenge, AuthProof.newNonce()).equals(AuthProof.derive(challenge, nonce))) {
            throw new AssertionError("Unexpected Mojang proof or nonce");
        }
        try {
            AuthProof.derive("invalid", nonce);
            throw new AssertionError("Invalid challenge accepted");
        } catch (IllegalArgumentException expected) {
            // Required before Mojang is contacted.
        }
        expect("127.0.0.1:10809", InetSocketAddress.createUnresolved("127.0.0.1", 10809));
        expect("http://127.0.0.1:7890/", InetSocketAddress.createUnresolved("127.0.0.1", 7890));
        expect(" http://user@proxy.local:3128 ", InetSocketAddress.createUnresolved("proxy.local", 3128));
        expect("socks5://127.0.0.1:1080", null); // java.net.http не умеет SOCKS
        expect("127.0.0.1", null);
        expect("http://:80", null);

        InetSocketAddress system = ApiTransport.detectSystemProxy();
        System.out.println("system proxy on this machine: " + (system == null ? "none" : system));

        if (failures > 0) {
            throw new AssertionError(failures + " proxy parsing check(s) failed");
        }
        System.out.println("ApiTransportTest: OK");
    }

    private static void expect(String input, InetSocketAddress expected) {
        InetSocketAddress actual = ApiTransport.parseProxy(input);
        if (!Objects.equals(actual, expected)) {
            failures++;
            System.out.println("FAIL parseProxy(\"" + input + "\") = " + actual + ", expected " + expected);
        }
    }
}
