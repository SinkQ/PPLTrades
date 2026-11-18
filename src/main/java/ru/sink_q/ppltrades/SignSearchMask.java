package ru.sink_q.ppltrades;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Собирает одну маску для Pochatok: каталог, торговые названия и личные алиасы. */
final class SignSearchMask {
    private static final String SEPARATOR = "[\\s\\p{Pd}]+";
    private static final Pattern SEPARATORS = Pattern.compile("(?U)" + SEPARATOR);
    private static final String START_WORD = "(?<![\\p{L}\\p{N}_])";
    private static final String END_WORD = "(?![\\p{L}\\p{N}_])";
    private static final Pattern YO = Pattern.compile("[еёЕЁ]");

    private static final String REDSTONE = "(?:р[еэ]дстоу?н|redstone)";
    private static final String BLOCK = "(?:бл(?:ок(?:а|и|ов)?)?|blocks?)";
    private static final String DECORATED_SEPARATOR = "[\\s\\p{Pd}\\p{So}\\p{Mn}]+";
    // Pochatok склеивает строки таблички пробелами: «Редстоун», «8 бл – 1 алм».
    private static final String PRICE_START = "[\\s\\p{So}\\p{Mn}\\p{Pd}\\p{Pe}`:>»]+\\d+";
    private static final String PIECES = PRICE_START + "\\s*шт" + END_WORD;
    private static final String BLOCK_QUANTITY = PRICE_START + "\\s*" + BLOCK + END_WORD;

    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("diamond_block", List.of("блок алмазов", "алмаз блок", "алмазные блоки")),
            Map.entry("emerald_block", List.of("блок изумрудов", "изумруд блок")),
            Map.entry("iron_block", List.of("блок железа", "железо блок", "железные блоки")),
            Map.entry("gold_block", List.of("блок золота", "золото блок", "золотые блоки")),
            Map.entry("copper_block", List.of("блок меди", "медь блок")),
            Map.entry("coal_block", List.of("блок угля", "уголь блок")),
            Map.entry("lapis_block", List.of("блок лазурита", "лазурит блок")),
            Map.entry("quartz_block", List.of("блок кварца", "кварц блок")),
            Map.entry("netherite_block", List.of("блок незерита", "незерит блок")),
            Map.entry("bricks", List.of("кирпичный блок", "кирпичные блоки", "блок кирпичей")),
            Map.entry("clay_ball", List.of("комки глины", "глиняные шарики")),
            Map.entry("stonecutter", List.of("камнерезы")),
            Map.entry("ender_chest", List.of("эндерсундук", "ендер сундук", "сундук эндера", "эндер чест")),
            Map.entry("dried_ghast", List.of("сухой гаст", "сушёный гаст", "высохшие гасты")),
            Map.entry("pointed_dripstone", List.of("капельники", "сталактит", "сталактиты")),
            Map.entry("repeater", List.of("повторитель", "повторители")),
            Map.entry("comparator", List.of("компаратор", "компараторы")),
            Map.entry("redstone_lamp", List.of("редстоун лампа", "редстоун фонарь", "редстоуновая лампа")),
            Map.entry("piston", List.of("поршни")),
            Map.entry("sticky_piston", List.of("липкие поршни")),
            Map.entry("hopper", List.of("воронки", "хоппер", "хопперы")),
            Map.entry("slime_block", List.of("слизневый блок", "слизь блок", "слизневые блоки")),
            Map.entry("slime_ball", List.of("шарик слизи", "слизневый шарик", "слизебол")),
            Map.entry("honey_block", List.of("медовый блок", "мёд блок", "медовые блоки")),
            Map.entry("hay_block", List.of("сено", "блок сена", "тюк сена")),
            Map.entry("firework_rocket", List.of("фейерверки", "ракета", "ракеты")),
            Map.entry("shulker_shell", List.of("панцирь шалкера", "панцири шалкера"))
    );
    private static final Map<String, List<String>> TRADE_ALIASES = loadTradeAliases();

    private SignSearchMask() {
    }

    static String build(String vanillaPath, String catalogMask, String itemName, List<String> customAliases) {
        Set<String> names = new LinkedHashSet<>();
        String catalog = null;
        if (catalogMask != null && !catalogMask.isBlank()) {
            // Сохраняем варианты ё/е и точки переноса внутри слов из каталога.
            catalog = catalogMask.replaceFirst("^\\(\\?iu\\)", "")
                    .replace("(?:-?\\s+)?", "[\\s\\p{Pd}]*")
                    .replace("\\s+", SEPARATOR)
                    .replace("-", SEPARATOR);
        } else {
            addName(names, itemName);
        }

        if (vanillaPath != null) {
            ALIASES.getOrDefault(vanillaPath, List.of()).forEach(name -> addName(names, name));
            TRADE_ALIASES.getOrDefault(vanillaPath, List.of()).forEach(name -> addName(names, name));
            switch (vanillaPath) {
                case "redstone_block" -> {
                    names.add(REDSTONE + DECORATED_SEPARATOR + BLOCK);
                    names.add("блок(?:и)?" + SEPARATOR + "(?:из" + SEPARATOR + ")?р[еэ]дстоу?на?");
                    names.add("редстоуновые" + SEPARATOR + "блоки");
                    names.add("block" + SEPARATOR + "of" + SEPARATOR + "redstone");
                    names.add(REDSTONE + BLOCK_QUANTITY);
                }
                case "redstone" -> {
                    names.add(REDSTONE + DECORATED_SEPARATOR + "(?:пыль|dust)");
                    names.add("пыль" + SEPARATOR + "р[еэ]дстоу?на");
                    names.add(REDSTONE + "(?!" + BLOCK_QUANTITY + ")(?=" + PRICE_START + "|\\s*$)");
                }
                // Без уточняющего слова сокращённое название ищем только рядом с количеством.
                case "iron_ingot" -> names.add("железо(?=" + PIECES + ")");
                case "gold_ingot" -> names.add("золото(?=" + PIECES + ")");
                case "copper_ingot" -> names.add("медь(?=" + PIECES + ")");
                case "quartz" -> names.add("кварц(?=" + PRICE_START + ")(?!" + BLOCK_QUANTITY + ")");
                case "slime_ball" -> names.add("слизь(?=" + PRICE_START + ")(?!" + BLOCK_QUANTITY + ")");
                // На табличке «Порыв ветра» может быть и зарядом, и книгой. «Книга» или
                // уровень вместе с ценой в алмазных блоках уточняют, что продают зачарование.
                case "enchanted_book" -> names.add("порыв" + SEPARATOR + "ветра" + SEPARATOR
                        + "1(?:\\s+i)?\\s+\\d+(?:\\s*шт\\.?\\s*\\p{Pd}\\s*\\d+)?\\s*аб");
            }
        }

        if (customAliases != null) {
            customAliases.forEach(name -> addName(names, name));
        }

        // Для каталога сохраняем поиск по подстроке: «камнерез» ловит и «камнерезы».
        // Новые краткие названия ищем как целые слова: «сено» не должно находиться в нике.
        String aliases = names.isEmpty() ? null
                : START_WORD + "(?:" + String.join("|", names) + ")" + END_WORD;
        if (catalog == null && aliases == null) {
            return null;
        }
        return "(?iuU)" + (catalog == null ? aliases : aliases == null ? catalog
                : "(?:" + catalog + "|" + aliases + ")");
    }

    private static Map<String, List<String>> loadTradeAliases() {
        try (var in = SignSearchMask.class.getResourceAsStream("/assets/ppltrades/sign_aliases.json")) {
            if (in == null) {
                return Map.of();
            }
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, List<String>>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void addName(Set<String> alternatives, String name) {
        if (name == null) {
            return;
        }
        String regex = SEPARATORS.splitAsStream(name)
                .filter(part -> !part.isEmpty())
                .map(part -> YO.matcher(Pattern.quote(part)).replaceAll(Matcher.quoteReplacement("\\E[её]\\Q")))
                .collect(Collectors.joining(SEPARATOR));
        if (!regex.isEmpty()) {
            alternatives.add(regex);
        }
    }
}
