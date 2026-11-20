package ru.sink_q.ppltrades;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import ru.sink_q.ppltrades.price.ItemVariants;
import ru.sink_q.ppltrades.price.PriceApi;
import ru.sink_q.ppltrades.price.PriceStats;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Окно предмета, как страница на сайте: цены за час/день/неделю со сравнением, график истории,
 * текущие лоты (бочки) с координатами. Открывается клавишей (по умолчанию I) над предметом.
 */
public final class ItemInfoScreen extends Screen {
    private static final int WIDTH = 380;
    private static final int ROW = 11;
    private static final int C_TEXT = 0xFFE6E6E6;
    private static final int C_MUTED = 0xFF8A8A8A;
    private static final int C_ACCENT = 0xFF2DD4BF;
    private static final int C_PANEL = 0xC0101012;
    private static final int C_BORDER = 0xFF2A2A2E;

    private enum Range {
        DAY("day"), WEEK("week"), MONTH("month");

        final String api;

        Range(String api) {
            this.api = api;
        }
    }

    /** Нижняя граница панели (под ней ряд кнопок). */
    private int panelBottom() {
        return height - 32;
    }

    private record Point(long t, double median, double min, double avg) {
    }

    private record Listing(net.minecraft.core.BlockPos pos, double price, int count, int maxStack, String sign,
                           String seller, long lastSeen) {
        String coords() {
            return pos.getX() + " " + pos.getY() + " " + pos.getZ();
        }
    }

    /** Ник Minecraft на табличке — обычно отдельная строка (продавец). */
    private static final java.util.regex.Pattern NICK = java.util.regex.Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final Screen parent;
    private final ItemStack stack;
    private final String key;
    private Range range = Range.WEEK;
    private boolean perStack;
    private List<Point> history;     // null = загрузка
    private boolean historyFailed;
    private List<Listing> listings;  // null = загрузка
    private boolean listingsFailed;
    private int listScroll;
    private int left;
    private int chartTop;
    private int listTop;
    private int listRows;

    public ItemInfoScreen(Screen parent, ItemStack stack) {
        super(stack.getHoverName());
        this.parent = parent;
        this.stack = stack.copyWithCount(1);
        this.key = ItemVariants.priceKey(stack);
        int max = stack.getMaxStackSize();
        Map<PriceStats.Window, PriceStats.Trend> prices = key == null ? null : PriceApi.getPrices(key);
        double ref = prices == null ? 1 : referenceMedian(prices);
        PpltradesConfig.PriceUnit unit = PpltradesConfig.get().priceUnit;
        this.perStack = max > 1 && (unit == PpltradesConfig.PriceUnit.STACK
                || (unit == PpltradesConfig.PriceUnit.AUTO && ref < 1));
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        int top = 12;
        chartTop = top + 102;
        listTop = chartTop + 98;
        // строки лотов начинаются под заголовком и шапкой колонок, внизу место под строку прокрутки
        int firstRow = listTop + 12 + ROW;
        listRows = Math.max(1, (panelBottom() - 4 - ROW - firstRow) / ROW);

        int x = left + WIDTH - 3 * 34;
        for (Range r : Range.values()) {
            Range target = r;
            addRenderableWidget(Button.builder(Component.translatable("ppltrades.info.range." + r.api), b -> {
                range = target;
                loadHistory();
            }).bounds(x, chartTop - 2, 32, 14).build());
            x += 34;
        }
        // 4 кнопки на всю ширину окна; короткие подписи, полный текст — во всплывающей подсказке
        int by = height - 26;
        int gap = 6;
        int bw = (WIDTH - 3 * gap) / 4;
        addRenderableWidget(Button.builder(Component.translatable("ppltrades.info.unit." + (perStack ? "stack" : "piece")),
                b -> {
                    perStack = !perStack && stack.getMaxStackSize() > 1;
                    b.setMessage(Component.translatable("ppltrades.info.unit." + (perStack ? "stack" : "piece")));
                }).bounds(left, by, bw, 20)
                .tooltip(Tooltip.create(Component.translatable("ppltrades.info.unit.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("ppltrades.info.search"), b -> {
            onClose();
            SignSearch.search(stack);
        }).bounds(left + bw + gap, by, bw, 20)
                .tooltip(Tooltip.create(Component.translatable("ppltrades.info.search.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("ppltrades.info.site"), b -> {
            if (key != null) {
                ConfirmLinkScreen.confirmLinkNow(this, URI.create(PpltradesConfig.activeApiUrl() + "/#/item/"
                        + URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20")));
            }
        }).bounds(left + 2 * (bw + gap), by, bw, 20)
                .tooltip(Tooltip.create(Component.translatable("ppltrades.info.site.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(left + 3 * (bw + gap), by, bw, 20).build());

        if (history == null && !historyFailed) {
            loadHistory();
        }
        if (listings == null && !listingsFailed) {
            loadListings();
        }
    }

    private void loadHistory() {
        history = null;
        historyFailed = false;
        if (key == null) {
            historyFailed = true;
            return;
        }
        Range requested = range;
        PriceApi.fetchJson("/v1/history?range=" + requested.api + "&key=" + enc(key), json -> {
            if (requested != range) {
                return; // уже переключили диапазон
            }
            if (json == null) {
                historyFailed = true;
                return;
            }
            List<Point> points = new ArrayList<>();
            for (JsonElement el : json.getAsJsonArray("points")) {
                JsonObject p = el.getAsJsonObject();
                points.add(new Point(p.get("t").getAsLong(), p.get("median").getAsDouble(),
                        p.get("min").getAsDouble(), p.get("avg").getAsDouble()));
            }
            history = points;
        });
    }

    private void loadListings() {
        if (key == null) {
            listingsFailed = true;
            return;
        }
        PriceApi.fetchJson("/v1/listings?key=" + enc(key), json -> {
            if (json == null) {
                listingsFailed = true;
                return;
            }
            List<Listing> out = new ArrayList<>();
            for (JsonElement el : json.getAsJsonArray("listings")) {
                JsonObject l = el.getAsJsonObject();
                List<String> lines = new ArrayList<>();
                JsonArray signs = l.getAsJsonArray("signs");
                if (signs != null && !signs.isEmpty()) {
                    for (JsonElement line : signs.get(0).getAsJsonArray()) {
                        String s = line.getAsString().strip();
                        if (!s.isEmpty()) {
                            lines.add(s);
                        }
                    }
                }
                // продавец — последняя строка, похожая на ник; в тексте таблички её не повторяем
                String seller = "";
                for (int i = lines.size() - 1; i >= 0; i--) {
                    if (NICK.matcher(lines.get(i)).matches()) {
                        seller = lines.remove(i);
                        break;
                    }
                }
                out.add(new Listing(new net.minecraft.core.BlockPos(l.get("x").getAsInt(), l.get("y").getAsInt(),
                        l.get("z").getAsInt()), l.get("price").getAsDouble(), l.get("count").getAsInt(),
                        l.get("maxStack").getAsInt(), String.join(" / ", lines), seller, l.get("lastSeen").getAsLong()));
            }
            listings = out;
        });
    }

    // --- отрисовка --------------------------------------------------------------------------------

    /** Панель рисуется вместе с фоном — до кнопок, иначе полупрозрачная заливка их затемняет. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        panel(g, left - 6, 6, left + WIDTH + 6, panelBottom());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int top = 12;

        // заголовок
        g.item(stack, left, top);
        g.text(font, title, left + 22, top, C_TEXT, true);
        TradeCategories.Entry entry = TradeCategories.get(stack);
        Component sub = Component.literal(key == null ? "—" : key).withStyle(ChatFormatting.DARK_GRAY);
        if (entry != null) {
            sub = Component.translatable("ppltrades.category." + entry.categoryId()).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("  " + key).withStyle(ChatFormatting.DARK_GRAY));
        }
        g.text(font, sub, left + 22, top + 10, C_MUTED, false);

        drawStats(g, top + 28);
        drawChart(g, mouseX, mouseY);
        drawListings(g, mouseX, mouseY);
    }

    private void drawStats(GuiGraphicsExtractor g, int y) {
        int unit = perStack ? stack.getMaxStackSize() : 1;
        g.text(font, Component.translatable(perStack ? "ppltrades.tooltip.price.header.stack" : "ppltrades.tooltip.price.header.piece",
                unit), left, y, C_MUTED, false);
        Map<PriceStats.Window, PriceStats.Trend> prices = key == null ? null : PriceApi.getPrices(key);
        int colW = WIDTH / 3;
        int i = 0;
        for (PriceStats.Window w : PriceStats.Window.values()) {
            int x = left + i++ * colW;
            int cy = y + 12;
            g.outline(x, cy, colW - 6, 50, C_BORDER);
            g.text(font, Component.translatable("ppltrades.tooltip.price.window." + w.key), x + 5, cy + 4, C_MUTED, false);
            PriceStats.Trend t = prices == null ? null : prices.get(w);
            PriceStats cur = t == null ? null : t.current();
            if (cur == null) {
                g.text(font, Component.translatable("ppltrades.info.no_data"), x + 5, cy + 18, C_MUTED, false);
                continue;
            }
            String median = TooltipHandler.format(cur.median() * unit);
            g.text(font, Component.literal(median).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), x + 5, cy + 16, C_TEXT, true);
            PriceStats prev = t.previous();
            if (prev != null) {
                String before = TooltipHandler.format(prev.median() * unit);
                int cmp = new BigDecimal(median).compareTo(new BigDecimal(before));
                if (cmp != 0) {
                    double pct = (cur.median() / prev.median() - 1) * 100;
                    MutableComponent tr = Component.literal((cmp > 0 ? "▲ +" : "▼ ") + Math.round(pct) + "%")
                            .withStyle(cmp > 0 ? ChatFormatting.RED : ChatFormatting.GREEN);
                    g.text(font, tr, x + 10 + font.width(median), cy + 16, C_TEXT, false);
                }
            }
            g.text(font, Component.translatable("ppltrades.info.min_avg",
                            TooltipHandler.format(cur.min() * unit), TooltipHandler.format(cur.avg() * unit)),
                    x + 5, cy + 28, C_MUTED, false);
            g.text(font, Component.translatable("ppltrades.tooltip.price.listings", cur.n()), x + 5, cy + 38, C_MUTED, false);
        }
    }

    private void drawChart(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x0 = left;
        int y0 = chartTop + 16;
        int w = WIDTH;
        int h = 70;
        g.text(font, Component.translatable("ppltrades.info.history"), left, chartTop, C_TEXT, false);
        g.outline(x0, y0, w, h, C_BORDER);
        if (history == null) {
            g.centeredText(font, Component.translatable(historyFailed ? "ppltrades.info.load_failed" : "ppltrades.info.loading"),
                    x0 + w / 2, y0 + h / 2 - 4, C_MUTED);
            return;
        }
        if (history.isEmpty()) {
            g.centeredText(font, Component.translatable("ppltrades.info.no_data"), x0 + w / 2, y0 + h / 2 - 4, C_MUTED);
            return;
        }
        int unit = perStack ? stack.getMaxStackSize() : 1;
        double lo = Double.MAX_VALUE;
        double hi = 0;
        for (Point p : history) {
            lo = Math.min(lo, p.min() * unit);
            hi = Math.max(hi, Math.max(p.median(), p.avg()) * unit);
        }
        if (hi - lo < 1e-9) {
            hi = lo * 1.1 + 1e-6;
            lo = lo * 0.9;
        }
        long t0 = history.get(0).t();
        long t1 = Math.max(t0 + 1, history.get(history.size() - 1).t());
        int px = x0 + 4;
        int pw = w - 8;
        int py = y0 + 4;
        int ph = h - 8;
        g.text(font, TooltipHandler.format(hi), x0 + 3, y0 + 2, C_MUTED, false);
        g.text(font, TooltipHandler.format(lo), x0 + 3, y0 + h - 10, C_MUTED, false);

        int prevMx = -1, prevMy = 0, prevLx = -1, prevLy = 0;
        Point hover = null;
        for (Point p : history) {
            int x = px + (int) Math.round((double) (p.t() - t0) / (t1 - t0) * pw);
            int my = py + ph - (int) Math.round((p.median() * unit - lo) / (hi - lo) * ph);
            int ly = py + ph - (int) Math.round((p.min() * unit - lo) / (hi - lo) * ph);
            if (prevLx >= 0) {
                line(g, prevLx, prevLy, x, ly, 0xFF555555);
            }
            if (prevMx >= 0) {
                line(g, prevMx, prevMy, x, my, C_ACCENT);
            }
            g.fill(x - 1, my - 1, x + 2, my + 2, C_ACCENT);
            prevMx = x;
            prevMy = my;
            prevLx = x;
            prevLy = ly;
            if (Math.abs(mouseX - x) <= 3 && mouseY >= y0 && mouseY <= y0 + h) {
                hover = p;
            }
        }
        if (history.size() == 1) {
            g.text(font, Component.translatable("ppltrades.info.one_point"), x0 + w / 2 - 60, y0 + h - 10, C_MUTED, false);
        }
        if (hover != null) {
            long ago = System.currentTimeMillis() / 1000 - hover.t();
            Component tip = Component.translatable("ppltrades.info.point",
                    ago(ago), TooltipHandler.format(hover.median() * unit), TooltipHandler.format(hover.min() * unit));
            int tw = font.width(tip) + 6;
            int tx = Math.min(mouseX + 6, x0 + w - tw);
            g.fill(tx, mouseY - 14, tx + tw, mouseY - 2, 0xE0000000);
            g.text(font, tip, tx + 3, mouseY - 12, C_TEXT, false);
        }
    }

    private void drawListings(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int y = listTop;
        Component title = Component.translatable("ppltrades.info.listings");
        g.text(font, title, left, y, C_TEXT, false);
        if (listings == null) {
            g.text(font, Component.translatable(listingsFailed ? "ppltrades.info.load_failed" : "ppltrades.info.loading"),
                    left, y + 14, C_MUTED, false);
            return;
        }
        // подпись после заголовка — по его реальной ширине (у разных языков она разная)
        g.text(font, Component.translatable("ppltrades.info.listings_hint", listings.size()),
                left + font.width(title) + 8, y, C_MUTED, false);
        if (listings.isEmpty()) {
            g.text(font, Component.translatable("ppltrades.info.no_data"), left, y + 14, C_MUTED, false);
            return;
        }
        int unit = perStack ? stack.getMaxStackSize() : 1;
        y += 12;
        // колонки: ширина = max(заголовок, самое широкое значение) + отступ
        Component hPrice = Component.translatable("ppltrades.info.col.price");
        Component hCount = Component.translatable("ppltrades.info.col.count");
        Component hCoords = Component.translatable("ppltrades.info.col.coords");
        Component hSeller = Component.translatable("ppltrades.info.col.seller");
        int wPrice = font.width(hPrice), wCount = font.width(hCount), wCoords = font.width(hCoords);
        int wSeller = font.width(hSeller);
        for (Listing l : listings) {
            wPrice = Math.max(wPrice, font.width(TooltipHandler.format(l.price() * unit)));
            wCount = Math.max(wCount, font.width(String.valueOf(l.count())));
            wCoords = Math.max(wCoords, font.width(l.coords()));
            wSeller = Math.max(wSeller, font.width(l.seller()));
        }
        wSeller = Math.min(wSeller, 90);
        int xCount = left + wPrice + 10;
        int xCoords = xCount + wCount + 10;
        int xSeller = xCoords + wCoords + 10;
        int xSign = xSeller + wSeller + 10;
        g.text(font, hPrice, left, y, C_MUTED, false);
        g.text(font, hCount, xCount, y, C_MUTED, false);
        g.text(font, hCoords, xCoords, y, C_MUTED, false);
        g.text(font, hSeller, xSeller, y, C_MUTED, false);
        g.text(font, Component.translatable("ppltrades.info.col.sign"), xSign, y, C_MUTED, false);
        y += ROW;
        listScroll = Math.max(0, Math.min(listScroll, listings.size() - listRows));
        long now = System.currentTimeMillis() / 1000;
        for (int i = listScroll; i < Math.min(listings.size(), listScroll + listRows); i++) {
            Listing l = listings.get(i);
            boolean hovered = mouseX >= left && mouseX < left + WIDTH && mouseY >= y - 1 && mouseY < y + ROW - 1;
            if (BarrelHighlight.isShown(l.pos())) { // подсвеченная сейчас бочка
                g.fill(left - 2, y - 1, left + WIDTH + 2, y + ROW - 1, 0x402DD4BF);
            }
            if (hovered) {
                g.fill(left - 2, y - 1, left + WIDTH + 2, y + ROW - 1, 0x30FFFFFF);
            }
            g.text(font, TooltipHandler.format(l.price() * unit), left, y, 0xFF55FFFF, false);
            g.text(font, String.valueOf(l.count()), xCount, y, C_TEXT, false);
            g.text(font, l.coords(), xCoords, y, C_TEXT, false);
            g.text(font, font.plainSubstrByWidth(l.seller(), wSeller), xSeller, y, 0xFFFFD27F, false);
            String age = ago(now - l.lastSeen()).getString();
            int ageX = left + WIDTH - font.width(age);
            g.text(font, font.plainSubstrByWidth(l.sign(), Math.max(0, ageX - xSign - 6)), xSign, y, C_MUTED, false);
            g.text(font, age, ageX, y, 0xFF666666, false);
            y += ROW;
        }
        if (listings.size() > listRows) {
            g.text(font, Component.translatable("ppltrades.info.scroll", listScroll + 1,
                    Math.min(listings.size(), listScroll + listRows), listings.size()), left, y + 1, 0xFF666666, false);
        }
    }

    // --- ввод -------------------------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (listings != null && mouseY >= listTop) {
            listScroll -= (int) Math.signum(scrollY) * 3;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // сначала кнопки — ровно один раз (раньше super вызывался повторно и кнопки срабатывали дважды)
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (listings != null) {
            int first = listTop + 12 + ROW;
            int index = listScroll + (int) Math.floor((event.y() - first + 1) / ROW);
            if (event.x() >= left && event.x() < left + WIDTH && event.y() >= first - 1
                    && index >= listScroll && index < Math.min(listings.size(), listScroll + listRows)) {
                Listing l = listings.get(index);
                Component message;
                if (BarrelHighlight.isShown(l.pos())) { // второй клик по тому же лоту — снять подсветку
                    BarrelHighlight.clear();
                    message = Component.translatable("ppltrades.info.highlight_cleared");
                } else {
                    BarrelHighlight.show(l.pos());
                    minecraft.keyboardHandler.setClipboard(l.coords());
                    message = Component.translatable("ppltrades.info.highlighted", l.coords());
                }
                if (minecraft.player != null) {
                    minecraft.player.sendOverlayMessage(message);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // --- утилиты ----------------------------------------------------------------------------------

    private static void panel(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, C_PANEL);
        g.outline(x1, y1, x2 - x1, y2 - y1, C_BORDER);
    }

    /** Линия из квадратиков 1x1 (в GUI нет примитива линии под углом). */
    private static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int s = 0; s <= steps; s++) {
            int x = x1 + (steps == 0 ? 0 : (x2 - x1) * s / steps);
            int y = y1 + (steps == 0 ? 0 : (y2 - y1) * s / steps);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static Component ago(long seconds) {
        if (seconds < 3600) {
            return Component.translatable("ppltrades.info.ago.min", Math.max(1, seconds / 60));
        }
        if (seconds < 86400) {
            return Component.translatable("ppltrades.info.ago.hour", seconds / 3600);
        }
        return Component.translatable("ppltrades.info.ago.day", seconds / 86400);
    }

    private static double referenceMedian(Map<PriceStats.Window, PriceStats.Trend> prices) {
        for (PriceStats.Window w : new PriceStats.Window[]{PriceStats.Window.WEEK, PriceStats.Window.DAY, PriceStats.Window.HOUR}) {
            PriceStats.Trend t = prices.get(w);
            if (t != null) {
                return (t.current() != null ? t.current() : t.previous()).median();
            }
        }
        return 1;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
