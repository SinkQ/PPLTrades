package ru.sink_q.ppltrades.price;

import ru.sink_q.ppltrades.Ppltrades;
import ru.sink_q.ppltrades.PpltradesConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Сетевой слой для бэкенда цен, рассчитанный на нестабильный доступ к Cloudflare.
 * <ul>
 *     <li>Перебирает адреса: основной {@code apiUrl}, затем зеркала {@code mirrorUrls} из конфига.</li>
 *     <li>Для каждого адреса пробует маршруты: через прокси (системный или из конфига), затем напрямую.</li>
 *     <li>Запоминает последнюю рабочую пару «адрес + маршрут» и начинает с неё.</li>
 * </ul>
 * Прокси: {@code "system"} (по умолчанию: системный прокси Windows или переменные HTTPS_PROXY/HTTP_PROXY),
 * {@code "none"}, либо явный {@code "http://host:port"}. Встроенный HTTP-клиент Java работает только с HTTP-прокси
 * (у большинства VPN-клиентов это «mixed»/HTTP-порт, например 127.0.0.1:10809 или :7890).
 * Вызывается только из сетевого потока PriceApi.
 */
final class ApiTransport {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final long PROXY_REDETECT_MS = 60_000;

    /** Маршрут: null = напрямую, иначе адрес HTTP-прокси. */
    private record Route(InetSocketAddress proxy) {
        String label() {
            return proxy == null ? "напрямую" : "через прокси " + proxy.getHostString() + ":" + proxy.getPort();
        }
    }

    private static final Map<Route, HttpClient> CLIENTS = new LinkedHashMap<>();
    private static InetSocketAddress detectedProxy;
    private static long proxyDetectedAt;
    private static String lastBase;
    private static Route lastRoute;

    private ApiTransport() {
    }

    /** Основной адрес, затем зеркала (без повторов). */
    static List<String> baseUrls() {
        List<String> urls = new ArrayList<>();
        urls.add(PpltradesConfig.activeApiUrl());
        List<String> mirrors = PpltradesConfig.get().mirrorUrls;
        if (mirrors != null) {
            for (String m : mirrors) {
                if (m != null && !m.isBlank()) {
                    String url = PpltradesConfig.normalizeApiUrl(m);
                    if (!urls.contains(url)) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    /**
     * Отправить запрос, перебирая адреса и маршруты до первого ответа сервера (любой HTTP-статус).
     * @throws IOException если ни один вариант не ответил
     */
    static HttpResponse<String> send(Function<String, HttpRequest> request) throws IOException, InterruptedException {
        List<Map.Entry<String, Route>> attempts = new ArrayList<>();
        for (String base : baseUrls()) {
            for (Route route : routes()) {
                attempts.add(Map.entry(base, route));
            }
        }
        // последняя рабочая пара — первой
        attempts.sort((a, b) -> Boolean.compare(!isLast(a), !isLast(b)));
        IOException last = null;
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, Route> attempt : attempts) {
            String base = attempt.getKey();
            Route route = attempt.getValue();
            try {
                HttpResponse<String> response = client(route).send(request.apply(base),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (!base.equals(lastBase) || !route.equals(lastRoute)) {
                    Ppltrades.LOGGER.info("Сервер цен доступен: {} ({})", base, route.label());
                }
                lastBase = base;
                lastRoute = route;
                return response;
            } catch (IOException e) {
                last = e;
                errors.add(base + " " + route.label() + ": " + e);
            }
        }
        // все варианты в лог: иначе видна только последняя ошибка (обычно "напрямую"), а причина
        // отказа прокси теряется
        Ppltrades.LOGGER.warn("Сервер цен недоступен ни одним способом:\n  {}", String.join("\n  ", errors));
        proxyDetectedAt = 0; // при следующей попытке заново определим системный прокси (VPN могли включить)
        throw last != null ? last : new IOException("нет адресов сервера цен");
    }

    private static boolean isLast(Map.Entry<String, Route> attempt) {
        return attempt.getKey().equals(lastBase) && attempt.getValue().equals(lastRoute);
    }

    /** Есть ли вообще прокси для попытки (для текста подсказки игроку). */
    static boolean hasProxy() {
        return routes().stream().anyMatch(r -> r.proxy() != null);
    }

    private static List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        InetSocketAddress proxy = configuredProxy();
        if (proxy != null) {
            routes.add(new Route(proxy));
        }
        routes.add(new Route(null));
        return routes;
    }

    private static HttpClient client(Route route) {
        return CLIENTS.computeIfAbsent(route, r -> {
            HttpClient.Builder b = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL);
            b.proxy(r.proxy() == null ? HttpClient.Builder.NO_PROXY : ProxySelector.of(r.proxy()));
            return b.build();
        });
    }

    private static InetSocketAddress configuredProxy() {
        String mode = PpltradesConfig.get().proxy;
        mode = mode == null ? "system" : mode.trim();
        if (mode.isEmpty() || mode.equalsIgnoreCase("none") || mode.equalsIgnoreCase("off")) {
            return null;
        }
        if (!mode.equalsIgnoreCase("system")) {
            InetSocketAddress explicit = parseProxy(mode);
            if (explicit == null) {
                Ppltrades.LOGGER.warn("Неверный proxy в конфиге PPLTrades: {} (нужно http://host:port)", mode);
            }
            return explicit;
        }
        long now = System.currentTimeMillis();
        if (now - proxyDetectedAt > PROXY_REDETECT_MS) {
            InetSocketAddress found = detectSystemProxy();
            if (found == null ? detectedProxy != null : !found.equals(detectedProxy)) {
                Ppltrades.LOGGER.info("Системный прокси: {}",
                        found == null ? "нет" : found.getHostString() + ":" + found.getPort());
            }
            detectedProxy = found;
            proxyDetectedAt = now;
        }
        return detectedProxy;
    }

    /** Системный прокси: настройки Windows («Параметры → Сеть → Прокси»), иначе переменные окружения. */
    static InetSocketAddress detectSystemProxy() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            InetSocketAddress win = windowsProxy();
            if (win != null) {
                return win;
            }
        }
        for (String var : new String[]{"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"}) {
            String value = System.getenv(var);
            if (value != null && !value.isBlank()) {
                InetSocketAddress p = parseProxy(value);
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }

    private static InetSocketAddress windowsProxy() {
        String key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
        Map<String, String> values = new LinkedHashMap<>();
        try {
            Process process = new ProcessBuilder("reg", "query", key).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+", 3);
                    if (parts.length == 3 && parts[1].startsWith("REG_")) {
                        values.put(parts[0], parts[2].trim());
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            return null;
        }
        String enabled = values.getOrDefault("ProxyEnable", "0x0");
        String server = values.get("ProxyServer");
        if (!"0x1".equalsIgnoreCase(enabled) || server == null || server.isBlank()) {
            return null;
        }
        // "host:port" или "http=host:port;https=host:port;socks=host:port"
        if (server.contains("=")) {
            String http = null;
            for (String part : server.split(";")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2) {
                    String scheme = kv[0].trim().toLowerCase(Locale.ROOT);
                    if (scheme.equals("https")) {
                        return parseProxy(kv[1]);
                    }
                    if (scheme.equals("http")) {
                        http = kv[1];
                    }
                }
            }
            return http == null ? null : parseProxy(http);
        }
        return parseProxy(server);
    }

    /** "host:port", "http://host:port", "http://user@host:port/" -> адрес; SOCKS не поддерживается. */
    static InetSocketAddress parseProxy(String value) {
        String v = value.trim();
        if (v.toLowerCase(Locale.ROOT).startsWith("socks")) {
            return null;
        }
        if (!v.contains("://")) {
            v = "http://" + v;
        }
        try {
            URI uri = URI.create(v);
            if (uri.getHost() == null || uri.getPort() <= 0) {
                return null;
            }
            return InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
