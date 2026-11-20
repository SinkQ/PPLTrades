package ru.sink_q.ppltrades.price;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Проверка разбора адресов прокси (запуск: gradlew testApiTransport, входит в gradlew test). */
public final class ApiTransportTest {
    private static int failures;

    public static void main(String[] args) {
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
