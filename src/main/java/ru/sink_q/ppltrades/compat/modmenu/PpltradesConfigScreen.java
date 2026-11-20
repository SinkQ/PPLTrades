package ru.sink_q.ppltrades.compat.modmenu;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import ru.sink_q.ppltrades.Ppltrades;
import ru.sink_q.ppltrades.PpltradesConfig;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class PpltradesConfigScreen {
    private static final String PREFIX = "ppltrades.config.";

    private PpltradesConfigScreen() {
    }

    static Screen create(Screen parent) {
        PpltradesConfig config = PpltradesConfig.get();
        boolean previouslyShowedPrices = config.showPrices;
        var builder = ConfigBuilder.create().setParentScreen(parent).setTitle(text("title"))
                .setSavingRunnable(() -> {
                    PpltradesConfig.save();
                    if (!previouslyShowedPrices && config.showPrices) {
                        Ppltrades.refreshVisiblePrices();
                    }
                });
        var entries = builder.entryBuilder();

        // --- подсказка ---------------------------------------------------------------------------
        var tooltip = builder.getOrCreateCategory(text("tooltip"));
        tooltip.addEntry(entries.startBooleanToggle(text("show_prices"), config.showPrices)
                .setDefaultValue(true)
                .setTooltip(text("show_prices.tooltip"))
                .setSaveConsumer(value -> config.showPrices = value)
                .build());
        tooltip.addEntry(entries.startEnumSelector(text("trigger"), PpltradesConfig.TooltipTrigger.class,
                        config.tooltipTrigger)
                .setDefaultValue(PpltradesConfig.TooltipTrigger.SHIFT)
                .setEnumNameProvider(value -> text("trigger." + value.name().toLowerCase()))
                .setTooltip(text("trigger.tooltip"))
                .setSaveConsumer(value -> config.tooltipTrigger = value)
                .build());
        tooltip.addEntry(toggle(entries, "trigger_hint", config.showTriggerHint, true, v -> config.showTriggerHint = v));
        tooltip.addEntry(toggle(entries, "show_category", config.showCategory, true, v -> config.showCategory = v));
        tooltip.addEntry(entries.startEnumSelector(text("layout"), PpltradesConfig.PriceLayout.class, config.priceLayout)
                .setDefaultValue(PpltradesConfig.PriceLayout.TABLE)
                .setEnumNameProvider(value -> text("layout." + value.name().toLowerCase()))
                .setTooltip(text("layout.tooltip"))
                .setSaveConsumer(value -> config.priceLayout = value)
                .build());
        tooltip.addEntry(entries.startEnumSelector(text("unit"), PpltradesConfig.PriceUnit.class, config.priceUnit)
                .setDefaultValue(PpltradesConfig.PriceUnit.AUTO)
                .setEnumNameProvider(value -> text("unit." + value.name().toLowerCase()))
                .setTooltip(text("unit.tooltip"))
                .setSaveConsumer(value -> config.priceUnit = value)
                .build());
        tooltip.addEntry(entries.startSubCategory(text("columns"), List.of(
                        toggle(entries, "col.median", config.showMedian, true, v -> config.showMedian = v),
                        toggle(entries, "col.avg", config.showAvg, true, v -> config.showAvg = v),
                        toggle(entries, "col.min", config.showMin, true, v -> config.showMin = v),
                        toggle(entries, "col.listings", config.showListings, true, v -> config.showListings = v)))
                .setTooltip(text("columns.tooltip"))
                .setExpanded(true)
                .build());
        tooltip.addEntry(entries.startSubCategory(text("windows"), List.of(
                        toggle(entries, "window.hour", config.showHour, true, v -> config.showHour = v),
                        toggle(entries, "window.day", config.showDay, true, v -> config.showDay = v),
                        toggle(entries, "window.week", config.showWeek, true, v -> config.showWeek = v),
                        toggle(entries, "merge_windows", config.mergeEqualWindows, true, v -> config.mergeEqualWindows = v)))
                .setExpanded(true)
                .build());
        tooltip.addEntry(entries.startEnumSelector(text("trend"), PpltradesConfig.TrendMode.class, config.trend)
                .setDefaultValue(PpltradesConfig.TrendMode.FULL)
                .setEnumNameProvider(value -> text("trend." + value.name().toLowerCase()))
                .setTooltip(text("trend.tooltip"))
                .setSaveConsumer(value -> config.trend = value)
                .build());

        // --- сбор и сеть -------------------------------------------------------------------------
        var prices = builder.getOrCreateCategory(text("network"));
        prices.addEntry(entries.startBooleanToggle(text("collect_prices"), config.collectPrices)
                .setDefaultValue(true)
                .setTooltip(text("collect_prices.tooltip"))
                .setSaveConsumer(value -> config.collectPrices = value)
                .build());
        prices.addEntry(entries.startStrField(text("api_url"), config.apiUrl)
                .setDefaultValue(PpltradesConfig.DEFAULT_API_URL)
                .setTooltip(text("api_url.tooltip"))
                .setErrorSupplier(value -> validUrl(value) ? Optional.empty() : Optional.of(text("api_url.invalid")))
                .requireRestart()
                .setSaveConsumer(value -> config.apiUrl = value)
                .build());
        prices.addEntry(entries.startStrField(text("proxy"), config.proxy)
                .setDefaultValue("system")
                .setTooltip(text("proxy.tooltip"))
                .setErrorSupplier(value -> validProxy(value) ? Optional.empty() : Optional.of(text("proxy.invalid")))
                .setSaveConsumer(value -> config.proxy = value.isBlank() ? "system" : value.trim())
                .build());
        prices.addEntry(entries.startStrList(text("mirrors"), config.mirrorUrls)
                .setDefaultValue(List.of())
                .setTooltip(text("mirrors.tooltip"))
                .setErrorSupplier(rows -> rows.stream().allMatch(PpltradesConfigScreen::validUrl)
                        ? Optional.empty() : Optional.of(text("api_url.invalid")))
                .setSaveConsumer(values -> config.mirrorUrls = values.stream()
                        .map(String::trim).filter(v -> !v.isEmpty()).toList())
                .build());
        prices.addEntry(toggle(entries, "notify_unreachable", config.notifyUnreachable, true,
                v -> config.notifyUnreachable = v));

        var search = builder.getOrCreateCategory(text("sign_search"));
        search.addEntry(entries.startEnumSelector(text("search_overlay"), PpltradesConfig.SearchOverlay.class,
                        config.searchOverlay)
                .setDefaultValue(PpltradesConfig.SearchOverlay.ITEM_NAME)
                .setEnumNameProvider(value -> text("search_overlay." + value.name().toLowerCase()))
                .setTooltip(text("search_overlay.tooltip"))
                .setSaveConsumer(value -> config.searchOverlay = value)
                .build());
        search.addEntry(entries.startStrList(text("aliases"), aliasRows(config.signSearchAliases))
                .setExpanded(true)
                .setDefaultValue(List.of())
                .setTooltip(text("aliases.tooltip"), Component.literal("minecraft:redstone_block = ред блок"))
                .setErrorSupplier(rows -> rows.stream().anyMatch(row -> !row.isBlank() && parseAlias(row) == null)
                        ? Optional.of(text("aliases.invalid")) : Optional.empty())
                .setSaveConsumer(values -> config.signSearchAliases = aliasesOf(values))
                .build());
        return builder.build();
    }

    /** Переключатель с подсказкой из ключа "<key>.tooltip", если он есть в языковом файле. */
    private static me.shedaniel.clothconfig2.api.AbstractConfigListEntry toggle(
            me.shedaniel.clothconfig2.api.ConfigEntryBuilder entries, String key, boolean value,
            boolean def, java.util.function.Consumer<Boolean> save) {
        var builder = entries.startBooleanToggle(text(key), value).setDefaultValue(def).setSaveConsumer(save);
        if (net.minecraft.locale.Language.getInstance().has(PREFIX + key + ".tooltip")) {
            builder.setTooltip(text(key + ".tooltip"));
        }
        return builder.build();
    }

    /** "system", "none"/"off", "host:port" или "http://host:port" (SOCKS не поддерживается). */
    static boolean validProxy(String value) {
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty() || v.equals("system") || v.equals("none") || v.equals("off")) {
            return true;
        }
        if (v.startsWith("socks")) {
            return false;
        }
        try {
            URI uri = URI.create(v.contains("://") ? v : "http://" + v);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getPort() > 0 && uri.getPort() <= 65535;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean validUrl(String value) {
        if (value.isBlank()) {
            return true;
        }
        try {
            URI url = URI.create(value.trim());
            return ("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                    && url.getHost() != null && url.getPort() != 0 && url.getPort() <= 65535
                    && url.getUserInfo() == null && url.getQuery() == null && url.getFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static List<String> aliasRows(Map<String, List<String>> aliases) {
        return aliases.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(entry -> entry.getValue() != null)
                .flatMap(entry -> entry.getValue().stream().filter(name -> name != null && !name.isBlank())
                        .map(name -> entry.getKey() + " = " + name))
                .toList();
    }

    static Map<String, List<String>> aliasesOf(List<String> rows) {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        for (String row : rows) {
            var alias = parseAlias(row);
            if (alias != null) {
                var names = aliases.computeIfAbsent(alias.id(), key -> new ArrayList<>());
                if (!names.contains(alias.name())) {
                    names.add(alias.name());
                }
            }
        }
        return aliases;
    }

    private static Alias parseAlias(String row) {
        int separator = row.indexOf('=');
        if (separator < 0) {
            return null;
        }
        String item = row.substring(0, separator).strip();
        String name = row.substring(separator + 1).strip();
        Identifier id = Identifier.tryParse(item);
        return id == null || id.getPath().isEmpty() || name.isEmpty() ? null : new Alias(id.toString(), name);
    }

    private static Component text(String key) {
        return Component.translatable(PREFIX + key);
    }

    private record Alias(String id, String name) {
    }
}
