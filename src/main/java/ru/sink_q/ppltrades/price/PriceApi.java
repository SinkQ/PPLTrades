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

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Клиент бэкенда цен: авторизация через сессию Mojang, отправка наблюдений из бочек, загрузка статистики.
 * Вся сеть выполняется в отдельном потоке; методы для игрового потока не блокируют.
 */
public final class PriceApi {
    private static final Gson GSON = new Gson();
    private static final int MAX_QUEUE = 2000;
    private static final int MAX_BATCH = 50;
    private static final long AUTH_RETRY_DELAY_MS = 3 * 60 * 1000L;

    /** Токен бэкенда переживает перезапуск игры, чтобы не дёргать сессионный сервер Mojang (у него лимиты). */
    private static final Path TOKEN_FILE = FabricLoader.getInstance().getConfigDir().resolve("ppltrades-token.json");
    /** Неотправленные наблюдения сохраняются при выходе и отправляются при следующем запуске. */
    private static final Path QUEUE_FILE = FabricLoader.getInstance().getConfigDir().resolve("ppltrades-queue.json");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
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
    private static String token;
    private static long tokenExpiresAtMs;
    private static long authBlockedUntilMs;

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
                Files.deleteIfExists(QUEUE_FILE);
                return;
            }
            JsonArray array = new JsonArray();
            QUEUE.forEach(array::add);
            Files.writeString(QUEUE_FILE, GSON.toJson(array), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ppltrades.LOGGER.warn("Не удалось сохранить очередь цен: {}", e.toString());
        }
    }

    /** Загрузить сохранённые с прошлого раза наблюдения в очередь. */
    public static synchronized void loadQueue() {
        try {
            if (!Files.exists(QUEUE_FILE)) {
                return;
            }
            for (JsonElement el : JsonParser.parseString(Files.readString(QUEUE_FILE, StandardCharsets.UTF_8)).getAsJsonArray()) {
                if (el.isJsonObject() && QUEUE.size() < MAX_QUEUE) {
                    QUEUE.add(el.getAsJsonObject());
                }
            }
            Files.deleteIfExists(QUEUE_FILE);
            Ppltrades.LOGGER.info("Загружено {} неотправленных наблюдений цен", QUEUE.size());
        } catch (Exception e) {
            Ppltrades.LOGGER.warn("Не удалось загрузить очередь цен: {}", e.toString());
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
                while (!QUEUE.isEmpty()) {
                    List<JsonObject> batch = new ArrayList<>();
                    JsonObject item;
                    while (batch.size() < MAX_BATCH && (item = QUEUE.poll()) != null) {
                        batch.add(item);
                    }
                    if (!sendBatch(serverAddress, batch)) {
                        break;
                    }
                }
            } catch (Exception e) {
                Ppltrades.LOGGER.warn("Не удалось отправить цены: {}", e.toString());
            } finally {
                FLUSHING.set(false);
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
                HttpResponse<String> response = HTTP.send(request("/v1/prices").GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    private static Map<String, Map<PriceStats.Window, PriceStats.Trend>> parsePrices(JsonObject root) {
        Map<String, Map<PriceStats.Window, PriceStats.Trend>> result = new HashMap<>();
        JsonObject items = root.getAsJsonObject("items");
        if (items == null) {
            return result;
        }
        for (Map.Entry<String, JsonElement> itemEntry : items.entrySet()) {
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

    private static PriceStats parseStats(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject s = el.getAsJsonObject();
        return new PriceStats(
                s.get("min").getAsDouble(),
                s.get("avg").getAsDouble(),
                s.get("median").getAsDouble(),
                s.get("n").getAsInt());
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

        HttpResponse<String> response = post("/v1/reports", body, true);
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
                Ppltrades.LOGGER.warn("Сервер цен отклонил отчёт: {} {}", response.statusCode(), response.body());
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
     * Получить токен бэкенда: бэкенд выдаёт случайный serverId, клиент регистрирует "вход" с ним на сессионном
     * сервере Mojang (как при подключении к серверу), бэкенд проверяет это через hasJoined.
     * Так подтверждается, что отчёты шлёт владелец лицензионного аккаунта.
     */
    private static boolean ensureToken() throws Exception {
        long now = System.currentTimeMillis();
        if (token == null) {
            loadSavedToken();
        }
        if (token != null && now < tokenExpiresAtMs - 5 * 60 * 1000L) {
            return true;
        }
        if (now < authBlockedUntilMs) {
            return false;
        }

        try {
            HttpResponse<String> challenge = HTTP.send(request("/v1/auth/challenge").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (challenge.statusCode() != 200) {
                throw new IllegalStateException("challenge: HTTP " + challenge.statusCode());
            }
            String serverId = JsonParser.parseString(challenge.body()).getAsJsonObject().get("serverId").getAsString();

            Minecraft mc = Minecraft.getInstance();
            User user = mc.getUser();
            mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);

            JsonObject body = new JsonObject();
            body.addProperty("username", user.getName());
            body.addProperty("serverId", serverId);
            HttpResponse<String> verify = post("/v1/auth/verify", body, false);
            if (verify.statusCode() != 200) {
                throw new IllegalStateException("verify: HTTP " + verify.statusCode() + " " + verify.body());
            }
            JsonObject result = JsonParser.parseString(verify.body()).getAsJsonObject();
            token = result.get("token").getAsString();
            tokenExpiresAtMs = result.get("expiresAt").getAsLong() * 1000L;
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
            if (!Files.exists(TOKEN_FILE)) {
                return;
            }
            JsonObject saved = JsonParser.parseString(Files.readString(TOKEN_FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            if (saved.get("uuid").getAsString().equals(Minecraft.getInstance().getUser().getProfileId().toString())
                    && saved.get("apiUrl").getAsString().equals(PpltradesConfig.get().apiUrl)) {
                token = saved.get("token").getAsString();
                tokenExpiresAtMs = saved.get("expiresAtMs").getAsLong();
            }
        } catch (Exception e) {
            Ppltrades.LOGGER.warn("Не удалось прочитать сохранённый токен: {}", e.toString());
        }
    }

    private static void saveToken() {
        try {
            JsonObject saved = new JsonObject();
            saved.addProperty("uuid", Minecraft.getInstance().getUser().getProfileId().toString());
            saved.addProperty("apiUrl", PpltradesConfig.get().apiUrl);
            saved.addProperty("token", token);
            saved.addProperty("expiresAtMs", tokenExpiresAtMs);
            Files.writeString(TOKEN_FILE, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ppltrades.LOGGER.warn("Не удалось сохранить токен: {}", e.toString());
        }
    }

    private static void forgetToken() {
        token = null;
        try {
            Files.deleteIfExists(TOKEN_FILE);
        } catch (Exception ignored) {
        }
    }

    private static HttpResponse<String> post(String path, JsonObject body, boolean auth) throws Exception {
        HttpRequest.Builder builder = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        if (auth) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(PpltradesConfig.get().apiUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "PPLTrades/" + modVersion());
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer(Ppltrades.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
