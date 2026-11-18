package ru.sink_q.ppltrades;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Конфиг config/ppltrades.json. Создаётся со значениями по умолчанию при первом запуске. */
public final class PpltradesConfig {
    /** Адрес бэкенда со статистикой цен. */
    public static final String DEFAULT_API_URL = "https://ppltrades.mooo.com:8443";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(Ppltrades.MOD_ID + ".json");

    private static PpltradesConfig instance = new PpltradesConfig();

    /** URL бэкенда цен (без завершающего /). */
    public String apiUrl = DEFAULT_API_URL;
    /** Отправлять цены из открытых бочек на сервер статистики. */
    public boolean collectPrices = true;
    /** Показывать цены в подсказке при зажатом Shift. */
    public boolean showPrices = true;
    /** Дополнительные названия на табличках: {"minecraft:redstone_block": ["ред блок"]}. */
    public Map<String, List<String>> signSearchAliases = Map.of();

    public static PpltradesConfig get() {
        return instance;
    }

    public static void load() {
        try {
            if (Files.exists(PATH)) {
                try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
                    PpltradesConfig loaded = GSON.fromJson(reader, PpltradesConfig.class);
                    if (loaded != null) {
                        instance = loaded;
                    }
                }
            }
        } catch (Exception e) {
            Ppltrades.LOGGER.error("Не удалось прочитать {}, используются значения по умолчанию", PATH, e);
        }
        // null или заглушка из ранних версий -> адрес по умолчанию
        if (instance.apiUrl == null
                || instance.apiUrl.contains("ppltrades.example.com")
                || instance.apiUrl.contains("pplttrades.mooo.com")) {
            instance.apiUrl = DEFAULT_API_URL;
        }
        instance.apiUrl = instance.apiUrl.trim().replaceAll("/+$", "");
        if (instance.signSearchAliases == null) {
            instance.signSearchAliases = Map.of();
        }
        save();
    }

    private static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            Ppltrades.LOGGER.error("Не удалось сохранить {}", PATH, e);
        }
    }
}
