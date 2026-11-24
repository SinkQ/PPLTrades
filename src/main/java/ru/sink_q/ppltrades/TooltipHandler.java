package ru.sink_q.ppltrades;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import ru.sink_q.ppltrades.price.ItemVariants;
import ru.sink_q.ppltrades.price.PriceApi;
import ru.sink_q.ppltrades.price.PriceStats;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Строки подсказки при зажатом Shift: категория торговой зоны и цены (только на PepeLand).
 * Цены выводятся таблицей с выровненными колонками:
 * <pre>
 * Категория: Разное
 * Цена за стак (64), алм
 *         мед         мин  ср  лотов
 *  День   4 (3▲)      2    6   3
 *  Неделя 4           2    6   5
 * </pre>
 */
public final class TooltipHandler {
    private static final int COLUMN_GAP = 6;
    private static final String INDENT = " ";

    private TooltipHandler() {
    }

    /** Колонки цены; порядок = порядок вывода. Первая включённая из цен — «главная» (к ней сравнение). */
    private enum Stat {
        MEDIAN("median"), AVG("avg"), MIN("min");

        final String key;

        Stat(String key) {
            this.key = key;
        }

        double of(PriceStats s) {
            return switch (this) {
                case MEDIAN -> s.median();
                case AVG -> s.avg();
                case MIN -> s.min();
            };
        }

        boolean enabled(PpltradesConfig c) {
            return switch (this) {
                case MEDIAN -> c.showMedian;
                case AVG -> c.showAvg;
                case MIN -> c.showMin;
            };
        }
    }

    public static void append(ItemStack stack, List<Component> lines) {
        if (stack.isEmpty()) {
            return;
        }
        PpltradesConfig config = PpltradesConfig.get();
        if (!triggerHeld(config.tooltipTrigger)) {
            if (config.showTriggerHint && config.showPrices && PepeLand.isConnected() && hasPrices(stack)) {
                lines.add(Component.translatable("ppltrades.tooltip.hint",
                                Component.translatable("ppltrades.config.trigger." + config.tooltipTrigger.name().toLowerCase()))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            return;
        }

        if (config.showCategory) {
            TradeCategories.Entry entry = TradeCategories.get(stack);
            Component category = entry != null
                    ? Component.translatable("ppltrades.category." + entry.categoryId()).withStyle(ChatFormatting.GOLD)
                    : Component.translatable("ppltrades.tooltip.category.none").withStyle(ChatFormatting.DARK_GRAY);
            lines.add(Component.translatable("ppltrades.tooltip.category", category).withStyle(ChatFormatting.GRAY));
        }

        if (config.showPrices && PepeLand.isConnected()) {
            appendPrices(stack, lines, config);
        }
    }

    private static boolean triggerHeld(PpltradesConfig.TooltipTrigger trigger) {
        Minecraft mc = Minecraft.getInstance();
        return switch (trigger) {
            case SHIFT -> mc.hasShiftDown();
            case ALT -> mc.hasAltDown();
            case CONTROL -> mc.hasControlDown();
            case ALWAYS -> true;
        };
    }

    private static boolean hasPrices(ItemStack stack) {
        String key = ItemVariants.priceKey(stack);
        return key != null && PriceApi.getPrices(key) != null;
    }

    private static void appendPrices(ItemStack stack, List<Component> lines, PpltradesConfig config) {
        String priceKey = ItemVariants.priceKey(stack); // enchanted_book@sharpness=1 и т.п.
        Map<PriceStats.Window, PriceStats.Trend> prices = priceKey == null ? null : PriceApi.getPrices(priceKey);
        List<Row> rows = prices == null ? List.of() : buildRows(prices, config);
        if (rows.isEmpty()) {
            lines.add(Component.translatable("ppltrades.tooltip.price.none").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        int maxStack = stack.getMaxStackSize();
        int unit = switch (config.priceUnit) {
            case PIECE -> 1;
            case STACK -> maxStack;
            // дешёвые стакающиеся предметы удобнее показывать за стак
            case AUTO -> maxStack > 1 && referenceMedian(rows) < 1 ? maxStack : 1;
        };

        List<Stat> stats = new ArrayList<>();
        for (Stat s : Stat.values()) {
            if (s.enabled(config)) {
                stats.add(s);
            }
        }
        if (stats.isEmpty()) {
            stats.add(Stat.MEDIAN); // без колонок цены подсказка бессмысленна
        }

        lines.add((unit == 1
                ? Component.translatable("ppltrades.tooltip.price.header.piece")
                : Component.translatable("ppltrades.tooltip.price.header.stack", unit))
                .withStyle(ChatFormatting.GRAY));

        if (config.priceLayout == PpltradesConfig.PriceLayout.COMPACT) {
            for (Row row : rows) {
                lines.add(compactLine(row, stats, unit, config));
            }
            return;
        }

        List<List<Component>> table = new ArrayList<>();
        List<Component> header = new ArrayList<>();
        header.add(Component.empty());
        stats.forEach(s -> header.add(column(s.key)));
        if (config.showListings) {
            header.add(column("n"));
        }
        table.add(header);
        for (Row row : rows) {
            PriceStats cur = row.trend().current();
            PriceStats prev = row.trend().previous();
            List<Component> cells = new ArrayList<>();
            cells.add(row.label().withStyle(ChatFormatting.GRAY));
            for (int i = 0; i < stats.size(); i++) {
                cells.add(i == 0 ? mainCell(stats.get(0), cur, prev, unit, config.trend)
                        : plain(cur == null ? null : stats.get(i).of(cur) * unit));
            }
            if (config.showListings) {
                cells.add(Component.literal(cur == null ? "0" : String.valueOf(cur.n())).withStyle(ChatFormatting.DARK_GRAY));
            }
            table.add(cells);
        }
        lines.addAll(layout(table));
    }

    /** "Неделя: мед 4 (3▲) · мин 2 · ср 6 · 3 лот." — без выравнивания, по строке на период. */
    private static Component compactLine(Row row, List<Stat> stats, int unit, PpltradesConfig config) {
        PriceStats cur = row.trend().current();
        PriceStats prev = row.trend().previous();
        MutableComponent line = Component.literal(INDENT).append(row.label().withStyle(ChatFormatting.GRAY))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        boolean labels = stats.size() > 1;
        for (int i = 0; i < stats.size(); i++) {
            if (i > 0) {
                line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            }
            if (labels) {
                line.append(column(stats.get(i).key)).append(Component.literal(" "));
            }
            line.append(i == 0 ? mainCell(stats.get(0), cur, prev, unit, config.trend)
                    : plain(cur == null ? null : stats.get(i).of(cur) * unit));
        }
        if (config.showListings && cur != null) {
            line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("ppltrades.tooltip.price.listings", cur.n())
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
        return line;
    }

    /** Строка таблицы: подпись (одно окно или диапазон склеенных одинаковых окон) и цены. */
    private record Row(MutableComponent label, PriceStats.Trend trend) {
    }

    private static boolean windowEnabled(PriceStats.Window window, PpltradesConfig c) {
        return switch (window) {
            case HOUR -> c.showHour;
            case DAY -> c.showDay;
            case WEEK -> c.showWeek;
        };
    }

    /**
     * Строки по включённым периодам. Если склейка включена, соседние окна с одинаковыми данными
     * объединяются в одну строку ("Час–неделя"); пустые пропускаются.
     */
    private static List<Row> buildRows(Map<PriceStats.Window, PriceStats.Trend> prices, PpltradesConfig config) {
        List<Row> rows = new ArrayList<>();
        PriceStats.Window first = null;
        PriceStats.Window last = null;
        PriceStats.Trend trend = null;
        for (PriceStats.Window window : PriceStats.Window.values()) {
            if (!windowEnabled(window, config)) {
                continue;
            }
            PriceStats.Trend t = prices.get(window);
            if (t != null && config.mergeEqualWindows && Objects.equals(t, trend)) {
                last = window;
                continue;
            }
            if (trend != null) {
                rows.add(new Row(label(first, last), trend));
            }
            first = last = window;
            trend = t;
        }
        if (trend != null) {
            rows.add(new Row(label(first, last), trend));
        }
        return rows;
    }

    private static MutableComponent label(PriceStats.Window first, PriceStats.Window last) {
        MutableComponent a = Component.translatable("ppltrades.tooltip.price.window." + first.key);
        return first == last ? a : Component.translatable("ppltrades.tooltip.price.window.range", a,
                Component.translatable("ppltrades.tooltip.price.window_lower." + last.key));
    }

    private static double referenceMedian(List<Row> rows) {
        PriceStats.Trend t = rows.get(rows.size() - 1).trend(); // самое длинное окно
        return (t.current() != null ? t.current() : t.previous()).median();
    }

    private static Component column(String key) {
        return Component.translatable("ppltrades.tooltip.price.col." + key).withStyle(ChatFormatting.DARK_GRAY);
    }

    private static Component plain(Double v) {
        return Component.literal(v == null ? "—" : format(v)).withStyle(ChatFormatting.WHITE);
    }

    /**
     * Главная цена (первая включённая колонка) и сравнение с прошлым периодом:
     * FULL — "4 (3▲)", ARROW — "4▲", OFF — "4".
     * ▲ красная — подорожало, ▼ зелёная — подешевело; при равенстве стрелки нет.
     */
    private static Component mainCell(Stat stat, PriceStats cur, PriceStats prev, int unit,
                                      PpltradesConfig.TrendMode mode) {
        Double currentValue = cur == null ? null : stat.of(cur) * unit;
        String now = currentValue == null ? null : format(currentValue);
        MutableComponent cell = Component.literal(now == null ? "—" : now).withStyle(ChatFormatting.AQUA);
        if (prev == null || mode == PpltradesConfig.TrendMode.OFF) {
            return cell;
        }
        double previousValue = stat.of(prev) * unit;
        String before = format(previousValue);
        Component arrow = Component.empty();
        if (currentValue != null && Double.isFinite(currentValue) && Double.isFinite(previousValue)) {
            int cmp = Double.compare(currentValue, previousValue);
            if (cmp > 0) {
                arrow = Component.literal("▲").withStyle(ChatFormatting.RED);
            } else if (cmp < 0) {
                arrow = Component.literal("▼").withStyle(ChatFormatting.GREEN);
            }
        }
        if (mode == PpltradesConfig.TrendMode.ARROW) {
            return cell.append(arrow);
        }
        return cell.append(Component.literal(" (" + before).withStyle(ChatFormatting.DARK_GRAY))
                .append(arrow)
                .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Выравнивает колонки пробелами по ширине текста в пикселях (шрифт Minecraft не моноширинный). */
    private static List<Component> layout(List<List<Component>> table) {
        Font font = Minecraft.getInstance().font;
        int columns = table.get(0).size();
        int[] widths = new int[columns];
        for (List<Component> row : table) {
            for (int c = 0; c < columns; c++) {
                widths[c] = Math.max(widths[c], font.width(row.get(c)));
            }
        }
        int space = Math.max(1, font.width(" "));
        List<Component> lines = new ArrayList<>();
        for (List<Component> row : table) {
            MutableComponent line = Component.literal(INDENT);
            int x = 0;       // текущая ширина строки
            int target = 0;  // где должна начинаться следующая колонка
            for (int c = 0; c < columns; c++) {
                Component cell = row.get(c);
                line.append(cell);
                x += font.width(cell);
                target += widths[c] + COLUMN_GAP;
                if (c < columns - 1) {
                    // позиция считается от начала строки, поэтому ошибка округления не накапливается
                    int spaces = Math.max(1, Math.round((target - x) / (float) space));
                    line.append(Component.literal(" ".repeat(spaces)));
                    x += spaces * space;
                }
            }
            lines.add(line);
        }
        return lines;
    }

    static String format(double v) {
        if (!Double.isFinite(v)) {
            return "?";
        }
        BigDecimal d = BigDecimal.valueOf(v);
        d = v >= 1 ? d.setScale(2, RoundingMode.HALF_UP) : d.round(new MathContext(2, RoundingMode.HALF_UP));
        return d.stripTrailingZeros().toPlainString();
    }
}
