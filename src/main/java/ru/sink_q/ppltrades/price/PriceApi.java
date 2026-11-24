package ru.sink_q.ppltrades.price;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import ru.sink_q.ppltrades.Ppltrades;
import ru.sink_q.ppltrades.PpltradesConfig;
import ru.sink_q.ppltrades.PrivateJsonFiles;

import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Клиент бэкенда цен: авторизация через сессию Mojang, отправка наблюдений из бочек, загрузка статистики.
 * Вся сеть выполняется в отдельном потоке; методы для игрового потока не блокируют.
 */
public final class PriceApi {
    private static final Gson GSON = new Gson();
    private static final int MAX_QUEUE = 2000;
    private static final int MAX_BATCH = 50;
    private static final long AUTH_RETRY_DELAY_MS = 3 * 60 * 1000L;
    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]++=*");

    private static final class LocalFiles {
        /** Токен переживает перезапуск игры, чтобы не дёргать сессионный сервер Mojang. */
        private static final Path TOKEN = FabricLoader.getInstance().getConfigDir().resolve("ppltrades-token.json");
        /** Неотправленные наблюдения сохраняются при выходе и отправляются при следующем запуске. */
        private static final Path QUEUE = FabricLoader.getInstance().getConfigDir().resolve("ppltrades-queue.json");
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PPLTrades-Network");
        t.setDaemon(true);
        return t;
    });

    private static final ConcurrentLinkedQueue<JsonObject> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean FLUSHING = new AtomicBoolean();
    private static final AtomicBoolean FETCHING = new AtomicBoolean();

    private static volatile Map<String, Map<PriceStats.Window, PriceStats.Trend>> prices = Map.of();

    // доступ только из сетевого потока
    private record Session(String base, String token, long expiresAtMs) {
        Session {
            // HttpRequest includes an invalid header value in its diagnostic; reject it first.
            if (token == null || token.length() > 4096 || !BEARER_TOKEN.matcher(token).matches()) {
                throw new IllegalArgumentException("Некорректный токен сервиса цен");
            }
        }
    }
    private static Session session;
    private static long authBlockedUntilMs;
    private static boolean unreachableNotified;

    private PriceApi() {
    }

    /** Статистика по предмету (id без "minecraft:") или null, если данных нет. */
    public static Map<PriceStats.Window, PriceStats.Trend> getPrices(String itemPath) {
        return prices.get(itemPath);
    }

    public static void clearPrices() {
        prices = Map.of();
    }

    /** Сохранить неотправленные наблюдения на диск (при выходе с сервера / из игры). */
    public static synchronized void saveQueue() {
        try {
            if (QUEUE.isEmpty()) {
                PrivateJsonFiles.delete(LocalFiles.QUEUE);
                return;
            }
            QueueJson.write(LocalFiles.QUEUE, QUEUE, MAX_QUEUE);
        } catch (Exception e) {
            Ppltrades.LOGGER.warn("Не удалось сохранить очередь цен");
        }
    }

    /** Загрузить сохранённые с прошлого раза наблюдения в очередь. */
    public static synchronized void loadQueue() {
        try {
            List<JsonObject> saved = QueueJson.read(LocalFiles.QUEUE, Math.max(0, MAX_QUEUE - QUEUE.size()));
            if (saved == null) {
                return;
            }
            requeue(saved);
            PrivateJsonFiles.delete(LocalFiles.QUEUE);
            Ppltrades.LOGGER.info("Загружено {} неотправленных наблюдений цен", QUEUE.size());
        } catch (Exception e) {
            Ppltrades.LOGGER.warn("Не удалось загрузить очередь цен");
        }
    }

    /** Поставить наблюдение в очередь на отправку. */
    public static void enqueue(JsonObject report) {
        if (QUEUE.size() >= MAX_QUEUE) {
            QUEUE.poll();
        }
        QUEUE.add(report);
    }

    public static boolean hasQueued() {
        return !QUEUE.isEmpty();
    }

    /** Накопилась полная пачка — можно отправить, не дожидаясь таймера. */
    public static boolean hasFullBatch() {
        return QUEUE.size() >= MAX_BATCH;
    }

    /** Отправить накопленные наблюдения (асинхронно). */
    public static void flush(String serverAddress) {
        if (QUEUE.isEmpty() || !FLUSHING.compareAndSet(false, true)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                while (PpltradesConfig.get().collectPrices && tradingWorlds != null && !QUEUE.isEmpty()) {
                    List<JsonObject> batch = new ArrayList<>();
                    JsonObject item;
                    while (batch.size() < MAX_BATCH && (item = QUEUE.poll()) != null) {
                        JsonElement world = item.get("world");
                        if (world != null && world.isJsonPrimitive() && isTradingWorld(world.getAsString())) {
                            batch.add(item);
                        }
                    }
                    if (!batch.isEmpty()) {
                        try {
                            if (!sendBatch(serverAddress, batch)) {
                                break;
                            }
                        } catch (Exception e) {
                            requeue(batch);
                            throw e;
                        }
                    }
                }
            } catch (Exception e) {
                Ppltrades.LOGGER.warn("Не удалось отправить цены: {}", e.toString());
            } finally {
                FLUSHING.set(false);
            }
        });
    }

    /** Пока список не получен (или если он пуст), никакие миры не разрешены. */
    private static volatile Set<String> tradingWorlds;
    private static final AtomicBoolean CONFIG_FETCHING = new AtomicBoolean();

    /** Можно ли собирать бочки в этом мире. */
    public static boolean isTradingWorld(String worldId) {
        Set<String> worlds = tradingWorlds;
        return worlds != null && worldId != null && worlds.contains(worldId);
    }

    /**
     * GET запрос к серверу цен (например "/v1/history?key=..."), ответ разбирается в сетевом потоке,
     * колбэк вызывается в игровом потоке; при ошибке — с null.
     */
    public static void fetchJson(String pathAndQuery, java.util.function.Consumer<JsonObject> onDone) {
        EXECUTOR.execute(() -> {
            JsonObject result = null;
            try {
                HttpResponse<String> response = get(pathAndQuery);
                if (response.statusCode() == 200) {
                    result = JsonParser.parseString(response.body()).getAsJsonObject();
                }
            } catch (Exception e) {
                Ppltrades.LOGGER.warn("Не удалось загрузить {}: {}", pathAndQuery, e.toString());
            }
            JsonObject done = result;
            Minecraft.getInstance().execute(() -> onDone.accept(done));
        });
    }

    /** Загрузить настройки сбора с сервера (список торговых миров), асинхронно. */
    public static void refreshConfig() {
        if (!CONFIG_FETCHING.compareAndSet(false, true)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                HttpResponse<String> response = get("/v1/config");
                if (response.statusCode() == 200) {
                    Set<String> worlds = new HashSet<>();
                    JsonObject config = JsonParser.parseString(response.body()).getAsJsonObject();
                    JsonElement latest = config.get("latestVersion");
                    JsonElement url = config.get("downloadUrl");
                    if (latest != null && latest.isJsonPrimitive() && url != null && url.isJsonPrimitive()) {
                        ru.sink_q.ppltrades.UpdateNotice.onConfig(latest.getAsString(), url.getAsString());
                    }
                    JsonElement list = config.get("tradingWorlds");
                    if (list == null || !list.isJsonArray()) {
                        return;
                    }
                    for (JsonElement w : list.getAsJsonArray()) {
                        if (!w.isJsonPrimitive() || !w.getAsString().matches("[0-9a-fA-F]{1,16}")) {
                            return;
                        }
                        worlds.add(w.getAsString().toLowerCase(java.util.Locale.ROOT));
                    }
                    tradingWorlds = Set.copyOf(worlds);
                }
            } catch (Exception e) {
                Ppltrades.LOGGER.warn("Не удалось загрузить настройки сервера цен: {}", e.toString());
            } finally {
                CONFIG_FETCHING.set(false);
            }
        });
    }

    /** Загрузить актуальную статистику цен (асинхронно). */
    public static void refreshPrices() {
        if (!FETCHING.compareAndSet(false, true)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                HttpResponse<String> response = get("/v1/prices");
                if (response.statusCode() != 200) {
                    Ppltrades.LOGGER.warn("Сервер цен ответил {}", response.statusCode());
                    return;
                }
                prices = parsePrices(JsonParser.parseString(response.body()).getAsJsonObject());
            } catch (Exception e) {
                Ppltrades.LOGGER.warn("Не удалось загрузить цены: {}", e.toString());
            } finally {
                FETCHING.set(false);
            }
        });
    }

    static Map<String, Map<PriceStats.Window, PriceStats.Trend>> parsePrices(JsonObject root) {
        Map<String, Map<PriceStats.Window, PriceStats.Trend>> result = new HashMap<>();
        JsonElement itemsElement = root.get("items");
        if (itemsElement == null || !itemsElement.isJsonObject()) {
            throw new IllegalArgumentException("Missing or invalid prices items object");
        }
        JsonObject items = itemsElement.getAsJsonObject();
        for (Map.Entry<String, JsonElement> itemEntry : items.entrySet()) {
            if (!itemEntry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject windows = itemEntry.getValue().getAsJsonObject();
            Map<PriceStats.Window, PriceStats.Trend> stats = new EnumMap<>(PriceStats.Window.class);
            for (PriceStats.Window window : PriceStats.Window.values()) {
                PriceStats current = parseStats(windows.get(window.key));
                PriceStats previous = parseStats(windows.get(window.key + "_prev"));
                if (current != null || previous != null) {
                    stats.put(window, new PriceStats.Trend(current, previous));
                }
            }
            if (!stats.isEmpty()) {
                result.put(itemEntry.getKey(), stats);
            }
        }
        return result;
    }

    private static final double MAX_STAT_PRICE = 900_000.0;

    static PriceStats parseStats(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject s = el.getAsJsonObject();
        try {
            double min = statPrice(s.get("min"));
            double avg = statPrice(s.get("avg"));
            double median = statPrice(s.get("median"));
            int n = statCount(s.get("n"));
            // The backend parses at most 100,000 payment units, with diamond blocks worth 9;
            // min is no greater than either summary.
            if (min > avg || min > median) {
                return null;
            }
            return new PriceStats(min, avg, median, n);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double statPrice(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing or nonnumeric price statistic");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value <= 0 || value > MAX_STAT_PRICE) {
            throw new IllegalArgumentException("Price statistic outside accepted range");
        }
        return value;
    }

    private static int statCount(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing or nonnumeric listing count");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < 1 || value > Integer.MAX_VALUE || value != Math.rint(value)) {
            throw new IllegalArgumentException("Listing count outside accepted range");
        }
        return (int) value;
    }

    /** @return false, если отправку стоит прекратить до следующей попытки */
    private static boolean sendBatch(String serverAddress, List<JsonObject> batch) throws Exception {
        if (!ensureToken()) {
            requeue(batch);
            return false;
        }

        JsonObject body = new JsonObject();
        body.addProperty("modVersion", modVersion());
        body.addProperty("server", serverAddress);
        JsonArray reports = new JsonArray();
        batch.forEach(reports::add);
        body.add("reports", reports);

        HttpResponse<String> response;
        try {
            response = post(session.base(), "/v1/reports", body, session.token());
        } catch (java.io.IOException e) {
            forgetToken(); // следующее подключение может выбрать другое зеркало, но получит новый токен
            throw e;
        }
        if (response.statusCode() >= 500) {
            requeue(batch);
            return false;
        }
        switch (response.statusCode()) {
            case 200 -> {
                return true;
            }
            case 401 -> {
                forgetToken(); // токен истёк или ключ сервера сменился, получим новый в следующий раз
                requeue(batch);
                return false;
            }
            case 429 -> {
                requeue(batch);
                return false;
            }
            default -> {
                // 400/403 и т.п.: повторять бессмысленно, отбрасываем пачку
                Ppltrades.LOGGER.warn("Сервер цен отклонил отчёт: HTTP {}", response.statusCode());
                return true;
            }
        }
    }

    private static void requeue(List<JsonObject> batch) {
        for (JsonObject report : batch) {
            if (QUEUE.size() < MAX_QUEUE) {
                QUEUE.add(report);
            }
        }
    }

    /**
     * Клиент сам вычисляет подтверждение для Mojang из запроса бэкенда и собственного случайного числа.
     * Один адрес обслуживает подтверждение, выдачу токена и дальнейшую отправку отчётов.
     * Так подтверждается, что отчёты шлёт владелец лицензионного аккаунта.
     */
    private static boolean ensureToken() throws Exception {
        long now = System.currentTimeMillis();
        if (session == null) {
            loadSavedToken();
        }
        if (session != null && ApiTransport.baseUrls().contains(session.base())
                && now < session.expiresAtMs() - 5 * 60 * 1000L) {
            return true;
        }
        if (now < authBlockedUntilMs) {
            return false;
        }

        try {
            ApiTransport.Response answered = send(null, base -> request(base, "/v1/auth/challenge").GET().build());
            HttpResponse<String> challenge = answered.http();
            if (challenge.statusCode() != 200) {
                throw new IllegalStateException("challenge: HTTP " + challenge.statusCode());
            }
            JsonObject auth = JsonParser.parseString(challenge.body()).getAsJsonObject();
            if (!auth.has("authVersion") || auth.get("authVersion").getAsInt() != AuthProof.VERSION) {
                throw new IllegalStateException("Сервис цен не поддерживает безопасное подтверждение PPLTrades");
            }
            String serverId = auth.get("serverId").getAsString();
            String nonce = AuthProof.newNonce();
            String proof = AuthProof.derive(serverId, nonce);

            Minecraft mc = Minecraft.getInstance();
            User user = mc.getUser();
            mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), proof);

            JsonObject body = new JsonObject();
            body.addProperty("username", user.getName());
            body.addProperty("serverId", serverId);
            body.addProperty("clientNonce", nonce);
            HttpResponse<String> verify = post(answered.base(), "/v1/auth/verify", body, null);
            if (verify.statusCode() != 200) {
                throw new IllegalStateException("verify: HTTP " + verify.statusCode());
            }
            JsonObject result = JsonParser.parseString(verify.body()).getAsJsonObject();
            session = new Session(answered.base(), result.get("token").getAsString(),
                    Math.multiplyExact(result.get("expiresAt").getAsLong(), 1000L));
            saveToken();
            return true;
        } catch (Exception e) {
            authBlockedUntilMs = now + AUTH_RETRY_DELAY_MS;
            Ppltrades.LOGGER.warn("Авторизация на сервере цен не удалась (повтор через {} мин): {}",
                    AUTH_RETRY_DELAY_MS / 60000, e.toString());
            return false;
        }
    }

    /** Токен с диска, если он выдан этому же аккаунту для этого же бэкенда. */
    private static void loadSavedToken() {
        try {
            String source = PrivateJsonFiles.readIfExists(LocalFiles.TOKEN, PrivateJsonFiles.TOKEN_MAX_BYTES);
            if (source == null) {
                return;
            }
            JsonObject saved = JsonParser.parseString(source).getAsJsonObject();
            if (!saved.has("authVersion") || saved.get("authVersion").getAsInt() != AuthProof.VERSION) {
                return;
            }
            String base = saved.get("apiUrl").getAsString();
            if (saved.get("uuid").getAsString().equals(Minecraft.getInstance().getUser().getProfileId().toString())
                    && ApiTransport.baseUrls().contains(base)) {
                session = new Session(base, saved.get("token").getAsString(), saved.get("expiresAtMs").getAsLong());
            }
        } catch (Exception e) {
            Ppltrades.LOGGER.warn("Не удалось прочитать сохранённый токен");
        }
    }

    private static void saveToken() {
        try {
            JsonObject saved = new JsonObject();
            saved.addProperty("uuid", Minecraft.getInstance().getUser().getProfileId().toString());
            saved.addProperty("authVersion", AuthProof.VERSION);
            saved.addProperty("apiUrl", session.base());
            saved.addProperty("token", session.token());
            saved.addProperty("expiresAtMs", session.expiresAtMs());
            PrivateJsonFiles.write(LocalFiles.TOKEN, PrivateJsonFiles.TOKEN_MAX_BYTES, writer -> GSON.toJson(saved, writer));
        } catch (Exception e) {
            Ppltrades.LOGGER.warn("Не удалось сохранить токен");
        }
    }

    private static void forgetToken() {
        session = null;
        try {
            PrivateJsonFiles.delete(LocalFiles.TOKEN);
        } catch (Exception ignored) {
        }
    }

    private static HttpResponse<String> post(String pinnedBase, String path, JsonObject body, String bearer) throws Exception {
        String json = GSON.toJson(body);
        return send(pinnedBase, base -> {
            HttpRequest.Builder builder = request(base, path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (bearer != null) {
                builder.header("Authorization", "Bearer " + bearer);
            }
            return builder.build();
        }).http();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send(null, base -> request(base, path).GET().build()).http();
    }

    /** Запрос через ApiTransport (зеркала, прокси); при полной недоступности — подсказка игроку. */
    private static ApiTransport.Response send(String pinnedBase,
            java.util.function.Function<String, HttpRequest> request) throws java.io.IOException, InterruptedException {
        try {
            ApiTransport.Response response = ApiTransport.send(pinnedBase, request);
            if (unreachableNotified) {
                notifyRestored();
            }
            unreachableNotified = false;
            failures = 0;
            retryAtMs = 0;
            return response;
        } catch (java.io.IOException e) {
            // VPN часто «моргает» при переключении: пробуем снова через 30 с, 1, 2, затем каждые 5 мин
            long delay = RETRY_DELAYS_MS[Math.min(failures, RETRY_DELAYS_MS.length - 1)];
            failures++;
            retryAtMs = System.currentTimeMillis() + delay;
            // одно медленное подключение — не повод писать в чат; сообщаем после нескольких неудач подряд
            if (failures >= NOTIFY_AFTER_FAILURES) {
                notifyUnreachable();
            }
            throw e;
        }
    }

    /** Сколько неудачных попыток подряд (~2–3 мин с учётом повторов) до сообщения в чат. */
    private static final int NOTIFY_AFTER_FAILURES = 3;

    private static void notifyRestored() {
        if (!PpltradesConfig.get().notifyUnreachable) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "ppltrades.message.api_restored").withStyle(net.minecraft.ChatFormatting.GREEN));
            }
        });
    }

    private static final long[] RETRY_DELAYS_MS = {30_000, 60_000, 120_000, 300_000};
    private static volatile int failures;
    private static volatile long retryAtMs;

    /** Пора повторить запросы после сбоя связи (вызывается из игрового тика). */
    public static boolean retryDue() {
        long at = retryAtMs;
        if (at == 0 || System.currentTimeMillis() < at) {
            return false;
        }
        retryAtMs = 0;
        return true;
    }

    /** Один раз за «отключение» пишем в чат, что сервер цен недоступен и что можно сделать. */
    private static void notifyUnreachable() {
        if (unreachableNotified || !PpltradesConfig.get().notifyUnreachable) {
            return;
        }
        unreachableNotified = true;
        boolean proxy = ApiTransport.hasProxy();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(proxy
                                ? "ppltrades.message.api_unreachable.proxy" : "ppltrades.message.api_unreachable")
                        .withStyle(net.minecraft.ChatFormatting.GOLD));
            }
        });
    }

    private static HttpRequest.Builder request(String base, String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30)) // запрос целиком; через медленный VPN 15 с иногда не хватало
                .header("User-Agent", "PPLTrades/" + modVersion());
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer(Ppltrades.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
