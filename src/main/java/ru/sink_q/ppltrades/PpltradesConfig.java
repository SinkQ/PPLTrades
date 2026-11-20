package ru.sink_q.ppltrades;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Конфиг config/ppltrades.json. Создаётся со значениями по умолчанию при первом запуске. */
public final class PpltradesConfig {
    /** Адрес бэкенда со статистикой цен. */
    public static final String DEFAULT_API_URL = "https://ppltrades.pages.dev";
    private static final String LEGACY_API_URL = "https://ppltrades.mooo.com";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

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
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    PpltradesConfig loaded = GSON.fromJson(reader, PpltradesConfig.class);
                    if (loaded != null) {
                        instance = loaded;
                    }
                }
            }
        } catch (Exception e) {
            Ppltrades.LOGGER.error("Не удалось прочитать {}, используются значения по умолчанию", path, e);
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
        if (instance.proxy == null || instance.proxy.isBlank()) {
            instance.proxy = "system";
        }
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
        save();
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
        if (instance.apiUrl != null && (instance.apiUrl.isBlank() || isSecureApiUrl(instance.apiUrl))) {
            instance.apiUrl = normalizeApiUrl(instance.apiUrl);
        }
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            Ppltrades.LOGGER.error("Не удалось сохранить {}", path, e);
        }
    }
}
