package ru.sink_q.ppltrades;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class SignSearchMaskTest {
    public static void main(String[] args) throws IOException {
        Map<String, String> catalog = loadCatalog();
        catalog.forEach((path, entry) -> mask(path, entry, null, null));

        Pattern block = mask("redstone_block", catalog.get("redstone_block"), null, null);
        Pattern dust = mask("redstone", catalog.get("redstone"), null, null);
        for (String sign : List.of("Редстоун блок|20 шт - 1 алмаз|", "Редстоун||8 бл - 1 алм",
                "Рэдстоун—блок", "редстоуновый|блок", "Блок редстоуна", "РЕДСТОУН: 8бл")) {
            check(block, sign, true);
            check(dust, sign, false);
        }
        for (String sign : List.of("Редстоун|2 ст - 1 алм|MrGrand434", "редстоун пыль|1слот-3алм",
                "Редстоуновая|пыль", "Редстоун||", "Пыль редстоуна", "Рэдстон: 1 стак")) {
            check(block, sign, false);
            check(dust, sign, true);
        }
        for (String sign : List.of("Редстоуновый факел", "Редстоуновая руда", "Редстоун повторитель",
                "Редстоун фонарь", "люблю_редстоун|2 шт")) {
            check(block, sign, false);
            check(dust, sign, false);
        }

        check(mask("ender_chest", catalog.get("ender_chest"), null, null), "Эндер—сундук", true);
        check(mask("ender_chest", catalog.get("ender_chest"), null, null), "сундук|Эндера", true);
        check(mask("clay_ball", catalog.get("clay_ball"), null, null), "Комок|гли-ны", true);
        check(mask("diamond_block", catalog.get("diamond_block"), null, null), "Блок алмазов", true);
        check(mask("stonecutter", catalog.get("stonecutter"), null, null), "Камнерезы", true);
        check(mask("dried_ghast", catalog.get("dried_ghast"), null, null), "СУШЕНЫЙ ГАСТ", true);
        check(block, "⚠Редстоун⚠|⚠Блоки⚠|16 шт - 2 алм", true);
        check(dust, "⚠Редстоун⚠|⚠Блоки⚠|16 шт - 2 алм", false);
        check(dust, "<Редстоун>|64 шт - 1 алмаз", true);
        check(mask("iron_ingot", catalog.get("iron_ingot"), null, null), "Железо`|12 шт - 1 аб", true);
        check(mask("iron_ingot", catalog.get("iron_ingot"), null, null), "Блок железа|64 шт - 4 алм", false);
        check(mask("quartz", catalog.get("quartz"), null, null), "Кварц|1 стак - 1 алм", true);
        check(mask("quartz", catalog.get("quartz"), null, null), "Кварц руда|32 шт - 1 алм", false);
        check(mask("cobbled_deepslate", catalog.get("cobbled_deepslate"), null, null),
                "Кол. глуб.|сланец|1 слот - 1 алм", true);
        check(mask("deepslate", catalog.get("deepslate"), null, null),
                "Кол. глуб.|сланец|1 слот - 1 алм", false);
        check(mask("enchanted_book", catalog.get("enchanted_book"), null, null), "Починка|1 шт - 5 алм", true);
        check(mask("enchanted_book", catalog.get("enchanted_book"), null, null), "Порыв ветра 1|28 АБ", true);
        check(mask("enchanted_book", catalog.get("enchanted_book"), null, null), "Порыв ветра|1 шт - 2 алм", false);

        Pattern custom = mask("redstone_block", catalog.get("redstone_block"), null, List.of("рэд (блок)+"));
        check(custom, "РЭД—(БЛОК)+", true);
        check(custom, "рэд блок", false);
        check(custom, "редстоун блок", true);
        check(mask(null, null, "Новый предмет", null), "Новый||предмет|1 шт", true);
    }

    private static Pattern mask(String path, String catalog, String name, List<String> aliases) {
        return Pattern.compile(SignSearchMask.build(path, catalog, name, aliases));
    }

    private static void check(Pattern mask, String lines, boolean expected) {
        // Pochatok 3.0.1 добавляет после каждой строки пробел и переводит текст в нижний регистр.
        String sign = lines.replace('|', ' ').toLowerCase(Locale.ROOT) + " ";
        if (mask.matcher(sign).find() != expected) {
            throw new AssertionError("«" + lines + "»: expected match=" + expected + " for " + mask);
        }
    }

    static Map<String, String> loadCatalog() throws IOException {
        Map<String, String> catalog = new HashMap<>();
        try (var reader = new InputStreamReader(SignSearchMaskTest.class
                .getResourceAsStream("/assets/ppltrades/categories.json"), StandardCharsets.UTF_8)) {
            var categories = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("categories");
            for (var category : categories) {
                category.getAsJsonObject().getAsJsonObject("items").entrySet()
                        .forEach(item -> catalog.put(item.getKey(), item.getValue().getAsString()));
            }
        }
        return catalog;
    }
}
