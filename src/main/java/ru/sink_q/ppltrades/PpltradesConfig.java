package ru.sink_q.ppltrades;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Конфиг config/ppltrades.json. Создаётся со значениями по умолчанию при первом запуске. */
public final class PpltradesConfig {
    /** Адрес бэкенда со статистикой цен. */
    public static final String DEFAULT_API_URL = "https://ppltrades.pages.dev";
    private static final String LEGACY_API_URL = "https://ppltrades.mooo.com";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_SIGN_ALIASES = 200;
    private static final int MAX_ALIASES_PER_ITEM = 32;
    private static final int MAX_ALIAS_LENGTH = 128;
    private static final int MAX_ALIAS_ITEM_ID_LENGTH = 256;
    private static final Pattern ALIAS_ITEM_ID = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]+");

    private static PpltradesConfig instance = new PpltradesConfig();
    // Токен и очередь цен используют один адрес сервиса в течение всего запуска клиента.
    private static String activeApiUrl = DEFAULT_API_URL;

    /** URL бэкенда цен (без завершающего /). */
    public String apiUrl = DEFAULT_API_URL;
    /**
     * Запасные адреса сервиса цен на случай, если основной недоступен (например, блокировка Cloudflare).
     * Пробуются по порядку после apiUrl.
     */
    public List<String> mirrorUrls = List.of();
    /**
     * Прокси для запросов к сервису цен: "system" — системный прокси (Windows / HTTPS_PROXY),
     * "none" — напрямую, или "http://127.0.0.1:10809". Если через прокси не вышло, мод пробует напрямую.
     */
    public String proxy = "system";
    /** Отправлять цены из открытых бочек на сервер статистики. */
    public volatile boolean collectPrices = true;
    /** Показывать цены в подсказке при зажатом Shift. */
    public boolean showPrices = true;
    /** Дополнительные названия на табличках: {"minecraft:redstone_block": ["ред блок"]}. */
    public Map<String, List<String>> signSearchAliases = Map.of();

    // --- подсказка с ценами ---------------------------------------------------------------------
    /** По какой клавише показывать категорию и цены в подсказке. */
    public TooltipTrigger tooltipTrigger = TooltipTrigger.SHIFT;
    /** Подсказка "Зажмите Shift — цены", если у предмета есть цены, а клавиша не нажата. */
    public boolean showTriggerHint = true;
    public boolean showCategory = true;
    public PriceLayout priceLayout = PriceLayout.TABLE;
    public PriceUnit priceUnit = PriceUnit.AUTO;
    public boolean showMedian = true;
    public boolean showAvg = true;
    public boolean showMin = true;
    public boolean showListings = true;
    public boolean showHour = true;
    public boolean showDay = true;
    public boolean showWeek = true;
    /** Склеивать периоды с одинаковыми данными в одну строку ("Час–неделя"). */
    public boolean mergeEqualWindows = true;
    /** Сравнение с прошлым периодом той же длины. */
    public TrendMode trend = TrendMode.FULL;

    // --- поиск по табличкам ----------------------------------------------------------------------
    /** Что писать над хотбаром после запуска поиска по табличкам. */
    public SearchOverlay searchOverlay = SearchOverlay.ITEM_NAME;

    // --- сеть ------------------------------------------------------------------------------------
    /** Писать в чат, если сервер цен недоступен. */
    public boolean notifyUnreachable = true;
    /** Сообщать в чате о новой версии мода (ссылку даёт сервер цен). */
    public boolean notifyUpdates = true;

    public enum TooltipTrigger { SHIFT, ALT, CONTROL, ALWAYS }

    public enum PriceLayout { TABLE, COMPACT }

    public enum PriceUnit { AUTO, PIECE, STACK }

    public enum TrendMode { OFF, ARROW, FULL }

    public enum SearchOverlay { OFF, ITEM_NAME, FILTER, BOTH }

    public static PpltradesConfig get() {
        return instance;
    }

    public static String activeApiUrl() {
        return activeApiUrl;
    }

    public static void load() {
        Path path = configPath();
        ConfigRead read = readForLoad(path);
        instance = read.config();
        if (!read.saveOnLoad()) {
            // Invalid, oversized, unreadable and linked files must not opt the user into uploads.
            // Keep the original file for repair; only an explicit UI save may replace a regular file.
            Ppltrades.LOGGER.error("Не удалось прочитать конфиг PPLTrades; сбор цен отключён, файл оставлен без изменений");
        }
        // Переносим только прежний адрес по умолчанию и заглушки из ранних версий.
        String url = instance.apiUrl == null ? DEFAULT_API_URL : normalizeApiUrl(instance.apiUrl);
        if (LEGACY_API_URL.equalsIgnoreCase(url)
                || (LEGACY_API_URL + ":8443").equalsIgnoreCase(url)
                || url.contains("ppltrades.example.com")
                || url.contains("pplttrades.mooo.com")
                || url.contains("ppltrades.mooo.com")) { // старый адрес до переезда на Cloudflare Pages
            instance.apiUrl = DEFAULT_API_URL;
        } else {
            // Keep invalid input unchanged so it remains available for repair in the config screen.
            if (url.isBlank() || isSecureApiUrl(url)) {
                instance.apiUrl = url;
            }
        }
        if (instance.signSearchAliases == null) {
            instance.signSearchAliases = Map.of();
        }
        if (instance.mirrorUrls == null) {
            instance.mirrorUrls = List.of();
        }
        instance.proxy = proxyWithoutCredentials(instance.proxy);
        // неизвестные/старые значения перечислений Gson читает как null
        if (instance.tooltipTrigger == null) {
            instance.tooltipTrigger = TooltipTrigger.SHIFT;
        }
        if (instance.priceLayout == null) {
            instance.priceLayout = PriceLayout.TABLE;
        }
        if (instance.priceUnit == null) {
            instance.priceUnit = PriceUnit.AUTO;
        }
        if (instance.trend == null) {
            instance.trend = TrendMode.FULL;
        }
        if (instance.searchOverlay == null) {
            instance.searchOverlay = SearchOverlay.ITEM_NAME;
        }
        activeApiUrl = isSecureApiUrl(instance.apiUrl) ? normalizeApiUrl(instance.apiUrl) : "";
        if (read.saveOnLoad()) {
            save();
        }
    }

    record ConfigRead(PpltradesConfig config, boolean saveOnLoad) {}

    /** No Minecraft/Fabric state is needed to validate a local config. */
    static ConfigRead readForLoad(Path path) {
        try {
            String source = PrivateJsonFiles.readIfExists(path, PrivateJsonFiles.CONFIG_MAX_BYTES);
            if (source == null) {
                return new ConfigRead(new PpltradesConfig(), true);
            }
            JsonObject root;
            try (JsonReader reader = GSON.newJsonReader(new StringReader(source))) {
                reader.setNestingLimit(64);
                root = GSON.fromJson(reader, JsonObject.class);
                if (root == null || reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new IOException();
                }
            }
            JsonElement collecting = root.get("collectPrices");
            if (collecting != null && (collecting.isJsonNull() || !collecting.isJsonPrimitive()
                    || (!collecting.getAsJsonPrimitive().isBoolean()
                    && !(collecting.getAsJsonPrimitive().isString()
                    && ("true".equalsIgnoreCase(collecting.getAsString())
                    || "false".equalsIgnoreCase(collecting.getAsString())))))) {
                throw new IOException();
            }
            PpltradesConfig loaded = GSON.fromJson(root, PpltradesConfig.class);
            loaded.signSearchAliases = limitedAliases(loaded.signSearchAliases);
            return new ConfigRead(loaded, true);
        } catch (Exception failure) {
            PpltradesConfig disabled = new PpltradesConfig();
            disabled.collectPrices = false;
            return new ConfigRead(disabled, false);
        }
    }

    /** Returns whether a nonblank API base URL is a valid HTTPS URL. */
    public static boolean isSecureApiUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI url = URI.create(value.trim());
            String authority = url.getRawAuthority();
            int port = url.getPort();
            return url.isAbsolute() && !url.isOpaque()
                    && "https".equalsIgnoreCase(url.getScheme())
                    && url.getHost() != null
                    && !url.getHost().isBlank()
                    && (port == -1 || (port > 0 && port <= 65535))
                    && authority != null && !authority.endsWith(":")
                    && url.getUserInfo() == null && url.getQuery() == null && url.getFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String normalizeApiUrl(String value) {
        return value.trim().replaceAll("/+$", "");
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(Ppltrades.MOD_ID + ".json");
    }

    public static void save() {
        try {
            writeConfig(configPath(), instance);
        } catch (Exception e) {
            Ppltrades.LOGGER.error("Не удалось сохранить конфиг PPLTrades");
        }
    }

    static void writeConfig(Path path, PpltradesConfig config) throws IOException {
        if (config.apiUrl != null && (config.apiUrl.isBlank() || isSecureApiUrl(config.apiUrl))) {
            config.apiUrl = normalizeApiUrl(config.apiUrl);
        }
        config.proxy = proxyWithoutCredentials(config.proxy);
        config.signSearchAliases = limitedAliases(config.signSearchAliases);
        PrivateJsonFiles.write(path, PrivateJsonFiles.CONFIG_MAX_BYTES, writer -> GSON.toJson(config, writer));
    }

    static Map<String, List<String>> limitedAliases(Map<String, List<String>> aliases) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        int count = 0;
        if (aliases == null) {
            return result;
        }
        outer:
        for (var entry : aliases.entrySet()) {
            String item = entry.getKey();
            if (item == null || item.length() > MAX_ALIAS_ITEM_ID_LENGTH
                    || !ALIAS_ITEM_ID.matcher(item).matches() || entry.getValue() == null) {
                continue;
            }
            String id = item.indexOf(':') < 0 ? "minecraft:" + item : item;
            if (id.length() > MAX_ALIAS_ITEM_ID_LENGTH) {
                continue;
            }
            int checked = 0;
            for (String name : entry.getValue()) {
                // Match the bounded input inspected by SignSearchMask, including invalid rows.
                if (++checked > MAX_ALIASES_PER_ITEM) {
                    break;
                }
                if (name == null || name.length() > MAX_ALIAS_LENGTH || name.isBlank()) {
                    continue;
                }
                var names = result.computeIfAbsent(id, ignored -> new ArrayList<>());
                String stripped = name.strip();
                if (names.size() < MAX_ALIASES_PER_ITEM && !names.contains(stripped)) {
                    names.add(stripped);
                    if (++count == MAX_SIGN_ALIASES) {
                        break outer;
                    }
                }
            }
        }
        return result;
    }

    /** Proxy authentication was never supported. Do not retain it, even in malformed input. */
    static String proxyWithoutCredentials(String value) {
        if (value == null || value.isBlank()) {
            return "system";
        }
        String proxy = value.trim();
        if (proxy.equalsIgnoreCase("system") || proxy.equalsIgnoreCase("none") || proxy.equalsIgnoreCase("off")) {
            return proxy;
        }
        boolean hasScheme = proxy.contains("://");
        String source = hasScheme ? proxy : "http://" + proxy;
        try {
            URI uri = URI.create(source);
            String scheme = uri.getScheme();
            String path = uri.getRawPath();
            if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || (path != null && !path.isEmpty() && !path.equals("/"))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return "none";
            }
            if (uri.getRawUserInfo() == null) {
                return proxy;
            }
            String authority = uri.getRawAuthority();
            String clean = source.substring(0, source.indexOf("://") + 3)
                    + authority.substring(authority.lastIndexOf('@') + 1) + (path == null ? "" : path);
            return hasScheme ? clean : clean.substring("http://".length());
        } catch (IllegalArgumentException failure) {
            // Do not fall back to the input: bad escapes, whitespace or extra '@' may be credentials.
            return "none";
        }
    }
}
