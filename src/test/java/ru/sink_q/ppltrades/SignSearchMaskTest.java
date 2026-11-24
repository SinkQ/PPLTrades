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
        Pattern oak = mask("oak_log", catalog.get("oak_log"), null, null);
        for (String sign : List.of("Дуб|1 ст - 2 алм|Payziii", "Дуб/берёза|1 стак - 2 алм", "Дубовые брёвна|1 ст",
                "ДубовоеБревно|1 ст - 1 алм", "Бревна дуба|1 ст - 1 алм", "Дуб|слот - 1 алм")) {
            check(oak, sign, true);
        }
        for (String sign : List.of("Тёмный дуб|1 ст - 2 алм", "Древесина|Темный дуб|Слот - 1алм",
                "Бледный дуб|1 ст - 2 алм", "Бревно тёмного дуба|1 ст", "Общие таблички|Только дуб|ИНАЧЕ НЕ РАБРО",
                "Дубовая листва|64шт - 1 алм", "Доски дуб|1 ст - 1 алм", "Саженец|дуб|1 шт", "Дубовые доски|1 ст")) {
            check(oak, sign, false);
        }
        check(mask("dark_oak_log", catalog.get("dark_oak_log"), null, null), "Древесина|Темный дуб|Слот - 1алм", true);
        check(mask("dark_oak_log", catalog.get("dark_oak_log"), null, null), "Дуб|1 ст - 2 алм", false);
        check(mask("pale_oak_log", catalog.get("pale_oak_log"), null, null), "Тёмный дуб|1 ст - 2 алм", false);
        check(mask("birch_log", catalog.get("birch_log"), null, null), "Дуб/берёза|1 стак - 2 алм", true);
        check(oak, "Дуб/берёза/ель/акация|1 ст - 2 алм", true);
        check(oak, "Дуб ".repeat(8192) + "не продаётся", false);
        check(oak, "Дуб" + "-".repeat(16384) + "2 ст", true);
        check(mask("acacia_log", catalog.get("acacia_log"), null, null), "Акация (log)|1 слот - 1алм", true);
        check(mask("spruce_log", catalog.get("spruce_log"), null, null), "саженец ели|1 сажанец= 1 алм", false);
        check(mask("poplar_log", catalog.get("poplar_log"), null, null), "Саженцы тополя|16шт - 1алм", false);
        check(mask("mangrove_log", catalog.get("mangrove_log"), null, null), "Мангровые корни|1 слот -1 алмаз", false);
        Pattern netherite = mask("netherite_ingot", catalog.get("netherite_ingot"), null, null);
        for (String sign : List.of("Незерит|1шт.-40алм", "НЕЗЕРИТ|1 слиток - 23алм", "Незерит|30 алм - 1 шт",
                "Незерит|===============|1шт - 42алм|QUASAR_69", "☺ ★ Незерит ★ ☺ |☽ дешевый ☽|1 шт - 39 алм",
                "Незерит. Слиток|1 Слот - 35 АЛМ", "Незеритовый|слиток|1шт - 32 алм",
                "Незеритовые|слитки|1шт-27алм", "НЕЗЕРИТОВЫЙ|⛏слиток⛏|1шт. - 39 алм", "НЕЗЕРИТОВЫЙ|[СлИтОк]|1шт")) {
            check(netherite, sign, true);
        }
        for (String sign : List.of("незерит. лом|1шт - 9 алм", "незерит обломки|3шт - 18алм",
                "Незеритовое|улучшение|1 шт - 9 алм", "Незерит блок|1 шт - 300 алм", "Незерит|1 блок - 300 алм",
                "Незеритовые|топоры|1 шт - 20 аб", "Незерак|4ст - 1алм")) {
            check(netherite, sign, false);
        }
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
        List<String> manyAliases = new java.util.ArrayList<>();
        manyAliases.add("а".repeat(129));
        manyAliases.add("ред пыль");
        manyAliases.addAll(java.util.Collections.nCopies(30, "другое имя"));
        manyAliases.add("лишнее имя");
        Pattern bounded = mask("redstone", catalog.get("redstone"), null, manyAliases);
        check(bounded, "ред пыль", true);
        check(bounded, "а".repeat(129), false);
        check(bounded, "лишнее имя", false);
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
