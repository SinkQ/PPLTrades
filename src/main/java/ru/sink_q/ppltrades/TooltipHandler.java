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

    public static void append(ItemStack stack, List<Component> lines) {
        if (stack.isEmpty() || !Minecraft.getInstance().hasShiftDown()) {
            return;
        }

        TradeCategories.Entry entry = TradeCategories.get(stack);
        Component category = entry != null
                ? Component.translatable("ppltrades.category." + entry.categoryId()).withStyle(ChatFormatting.GOLD)
                : Component.translatable("ppltrades.tooltip.category.none").withStyle(ChatFormatting.DARK_GRAY);
        lines.add(Component.translatable("ppltrades.tooltip.category", category).withStyle(ChatFormatting.GRAY));

        if (PpltradesConfig.get().showPrices && PepeLand.isConnected()) {
            appendPrices(stack, lines);
        }
    }

    private static void appendPrices(ItemStack stack, List<Component> lines) {
        String priceKey = ItemVariants.priceKey(stack); // enchanted_book@sharpness=1 и т.п.
        Map<PriceStats.Window, PriceStats.Trend> prices = priceKey == null ? null : PriceApi.getPrices(priceKey);
        List<Row> rows = prices == null ? List.of() : mergeRows(prices);
        if (rows.isEmpty()) {
            lines.add(Component.translatable("ppltrades.tooltip.price.none").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        // Дешёвые стакающиеся предметы удобнее показывать за стак
        int maxStack = stack.getMaxStackSize();
        int unit = maxStack > 1 && referenceMedian(rows) < 1 ? maxStack : 1;

        lines.add((unit == 1
                ? Component.translatable("ppltrades.tooltip.price.header.piece")
                : Component.translatable("ppltrades.tooltip.price.header.stack", unit))
                .withStyle(ChatFormatting.GRAY));

        List<List<Component>> table = new ArrayList<>();
        table.add(List.of(
                Component.empty(),
                column("median"), column("min"), column("avg"), column("n")));
        for (Row row : rows) {
            PriceStats cur = row.trend().current();
            PriceStats prev = row.trend().previous();
            table.add(List.of(
                    row.label().withStyle(ChatFormatting.GRAY),
                    medianCell(cur, prev, unit),
                    plain(cur == null ? null : cur.min() * unit),
                    plain(cur == null ? null : cur.avg() * unit),
                    Component.literal(cur == null ? "0" : String.valueOf(cur.n())).withStyle(ChatFormatting.DARK_GRAY)));
        }
        lines.addAll(layout(table));
    }

    /** Строка таблицы: подпись (одно окно или диапазон склеенных одинаковых окон) и цены. */
    private record Row(MutableComponent label, PriceStats.Trend trend) {
    }

    /** Окна с одинаковыми данными подряд склеиваются в одну строку ("Час–неделя"), пустые пропускаются. */
    private static List<Row> mergeRows(Map<PriceStats.Window, PriceStats.Trend> prices) {
        List<Row> rows = new ArrayList<>();
        PriceStats.Window first = null;
        PriceStats.Window last = null;
        PriceStats.Trend trend = null;
        for (PriceStats.Window window : PriceStats.Window.values()) {
            PriceStats.Trend t = prices.get(window);
            if (t != null && Objects.equals(t, trend)) {
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
     * Медиана и цена за прошлый период: "4 (3▲)".
     * ▲ красная — подорожало, ▼ зелёная — подешевело; при равенстве стрелки нет.
     */
    private static Component medianCell(PriceStats cur, PriceStats prev, int unit) {
        String now = cur == null ? null : format(cur.median() * unit);
        MutableComponent cell = Component.literal(now == null ? "—" : now).withStyle(ChatFormatting.AQUA);
        if (prev == null) {
            return cell;
        }
        String before = format(prev.median() * unit);
        cell.append(Component.literal(" (" + before).withStyle(ChatFormatting.DARK_GRAY));
        if (now != null) {
            int cmp = new BigDecimal(now).compareTo(new BigDecimal(before));
            if (cmp > 0) {
                cell.append(Component.literal("▲").withStyle(ChatFormatting.RED));
            } else if (cmp < 0) {
                cell.append(Component.literal("▼").withStyle(ChatFormatting.GREEN));
            }
        }
        return cell.append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
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
