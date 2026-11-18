package ru.sink_q.ppltrades;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Категории торговой зоны PepeLand.
 * Для каждого предмета (id без namespace "minecraft:") хранится id категории (название берётся из lang-файлов)
 * и маска для поиска по табличкам модом Pochatok (/signsearchmask), сгенерированная так же, как на сайте.
 * Данные: https://www.pepeland.net/wiki/worlds/trading
 */
public final class TradeCategories {
    private static final String RESOURCE = "/assets/ppltrades/categories.json";

    public record Entry(String categoryId, String mask) {
    }

    private static final Map<String, Entry> ITEMS = new HashMap<>();

    private TradeCategories() {
    }

    public static void load() {
        ITEMS.clear();
        try (InputStream in = TradeCategories.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                Ppltrades.LOGGER.error("Не найден {}", RESOURCE);
                return;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (JsonElement catEl : root.getAsJsonArray("categories")) {
                    JsonObject cat = catEl.getAsJsonObject();
                    String categoryId = cat.get("id").getAsString();
                    for (Map.Entry<String, JsonElement> item : cat.getAsJsonObject("items").entrySet()) {
                        ITEMS.put(item.getKey(), new Entry(categoryId, item.getValue().getAsString()));
                    }
                }
            }
            Ppltrades.LOGGER.info("Загружено {} предметов из категорий торговой зоны", ITEMS.size());
        } catch (Exception e) {
            Ppltrades.LOGGER.error("Не удалось загрузить категории торговой зоны", e);
        }
    }

    /** @return данные предмета или null, если предмета нет в категориях */
    public static Entry get(String itemPath) {
        return ITEMS.get(itemPath);
    }

    public static Entry get(ItemStack stack) {
        String path = itemPath(stack);
        return path == null ? null : ITEMS.get(path);
    }

    /** id ванильного предмета без "minecraft:" или null для предметов других модов. */
    public static String itemPath(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : null;
    }
}
