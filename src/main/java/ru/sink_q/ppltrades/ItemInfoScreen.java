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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
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
    // The API's largest history window (month) contains at most 31 daily samples.
    private static final int MAX_HISTORY_POINTS = 32;
    private static final int MAX_LISTINGS = 500;
    private static final int MAX_CHART_LINE_STEPS = 4096;
    // The server parser accepts amounts up to 100,000 and at most nine diamond blocks.
    private static final double MAX_PRICE = 900_000.0;
    private static final int INVALID_COORDINATE = Integer.MIN_VALUE;

    private enum Range {
        DAY("day", 86_400), WEEK("week", 604_800), MONTH("month", 2_592_000);

        final String api;
        final long spanSeconds;

        Range(String api, long spanSeconds) {
            this.api = api;
            this.spanSeconds = spanSeconds;
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
            String apiUrl = PpltradesConfig.activeApiUrl();
            if (key != null && apiUrl != null && !apiUrl.isBlank()) {
                ConfirmLinkScreen.confirmLinkNow(this, URI.create(apiUrl + "/#/item/"
                        + URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20")), false);
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
            JsonElement pointsElement = json.get("points");
            if (pointsElement == null || !pointsElement.isJsonArray()) {
                historyFailed = true;
                return;
            }
            JsonArray pointsJson = pointsElement.getAsJsonArray();
            if (pointsJson.size() > MAX_HISTORY_POINTS) {
                historyFailed = true;
                return;
            }
            long now = System.currentTimeMillis() / 1000;
            long oldestAllowed = now - requested.spanSeconds - 86_400;
            long newestAllowed = now + 86_400;
            List<Point> points = new ArrayList<>(pointsJson.size());
            for (JsonElement el : pointsJson) {
                try {
                    if (el == null || !el.isJsonObject()) {
                        continue;
                    }
                    JsonObject p = el.getAsJsonObject();
                    long t = integer(p, "t", oldestAllowed, newestAllowed);
                    double median = price(p, "median");
                    double min = price(p, "min");
                    double avg = price(p, "avg");
                    if (min > median || min > avg) {
                        continue;
                    }
                    points.add(new Point(t, median, min, avg));
                } catch (RuntimeException ignored) {
                    // Ignore an invalid sample while retaining valid samples from the response.
                }
            }
            points.sort(Comparator.comparingLong(Point::t));
            List<Point> ordered = new ArrayList<>(points.size());
            long previousTime = Long.MIN_VALUE;
            for (Point point : points) {
                if (point.t() > previousTime) {
                    ordered.add(point);
                    previousTime = point.t();
                }
            }
            history = ordered;
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
            JsonElement listingsElement = json.get("listings");
            if (listingsElement == null || !listingsElement.isJsonArray()) {
                listingsFailed = true;
                return;
            }
            JsonArray listingsJson = listingsElement.getAsJsonArray();
            List<Listing> out = new ArrayList<>(Math.min(listingsJson.size(), MAX_LISTINGS));
            long now = System.currentTimeMillis() / 1000;
            for (int i = 0; i < Math.min(listingsJson.size(), MAX_LISTINGS); i++) {
                try {
                    JsonElement el = listingsJson.get(i);
                    if (el == null || !el.isJsonObject()) {
                        continue;
                    }
                    JsonObject l = el.getAsJsonObject();
                    int x = (int) integer(l, "x", -30_000_000, 30_000_000);
                    int y = (int) integer(l, "y", -2048, 2048);
                    int z = (int) integer(l, "z", -30_000_000, 30_000_000);
                    double price = price(l, "price");
                    int count = (int) integer(l, "count", 1, 100_000);
                    int maxStack = (int) integer(l, "maxStack", 1, 99);
                    long lastSeen = integer(l, "lastSeen", Math.max(1, now - 8 * 86_400), now + 86_400);
                    List<String> lines = new ArrayList<>();
                    JsonElement signsElement = l.get("signs");
                    if (signsElement != null && !signsElement.isJsonNull()) {
                        if (!signsElement.isJsonArray()) {
                            continue;
                        }
                        JsonArray signs = signsElement.getAsJsonArray();
                        if (!signs.isEmpty()) {
                            JsonElement firstSign = signs.get(0);
                            if (!firstSign.isJsonArray()) {
                                continue;
                            }
                            JsonArray signLines = firstSign.getAsJsonArray();
                            for (int j = 0; j < Math.min(signLines.size(), 4); j++) {
                                JsonElement line = signLines.get(j);
                                if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString()) {
                                    continue;
                                }
                                String raw = line.getAsString();
                                String s = raw.substring(0, Math.min(90, raw.length())).strip();
                                if (!s.isEmpty()) {
                                    lines.add(s);
                                }
                            }
                        }
                    }
                    // продавец — последняя строка, похожая на ник; в тексте таблички её не повторяем
                    String seller = "";
                    for (int j = lines.size() - 1; j >= 0; j--) {
                        if (NICK.matcher(lines.get(j)).matches()) {
                            seller = lines.remove(j);
                            break;
                        }
                    }
                    out.add(new Listing(new net.minecraft.core.BlockPos(x, y, z), price, count, maxStack,
                            String.join(" / ", lines), seller, lastSeen));
                } catch (RuntimeException ignored) {
                    // Skip malformed or out-of-range entries without failing the whole listing response.
                }
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
            if (prev != null && Double.isFinite(cur.median()) && cur.median() > 0
                    && Double.isFinite(prev.median()) && prev.median() > 0) {
                String before = TooltipHandler.format(prev.median() * unit);
                int cmp = Double.compare(cur.median(), prev.median());
                if (cmp != 0) {
                    double pct = (cur.median() / prev.median() - 1) * 100;
                    if (Double.isFinite(pct)) {
                        MutableComponent tr = Component.literal((cmp > 0 ? "▲ +" : "▼ ") + Math.round(pct) + "%")
                                .withStyle(cmp > 0 ? ChatFormatting.RED : ChatFormatting.GREEN);
                        g.text(font, tr, x + 10 + font.width(median), cy + 16, C_TEXT, false);
                    }
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
            int x = chartCoordinate(px + (double) (p.t() - t0) / (t1 - t0) * pw, px, px + pw);
            int my = chartCoordinate(py + ph - (p.median() * unit - lo) / (hi - lo) * ph, py, py + ph);
            int ly = chartCoordinate(py + ph - (p.min() * unit - lo) / (hi - lo) * ph, py, py + ph);
            if (x == INVALID_COORDINATE || my == INVALID_COORDINATE || ly == INVALID_COORDINATE) {
                continue;
            }
            if (prevLx >= 0) {
                line(g, prevLx, prevLy, x, ly, 0xFF555555, px, px + pw, py, py + ph);
            }
            if (prevMx >= 0) {
                line(g, prevMx, prevMy, x, my, C_ACCENT, px, px + pw, py, py + ph);
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
    private static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color,
                             int minX, int maxX, int minY, int maxY) {
        if (x1 < minX || x1 > maxX || x2 < minX || x2 > maxX
                || y1 < minY || y1 > maxY || y2 < minY || y2 > maxY) {
            return;
        }
        long dx = (long) x2 - x1;
        long dy = (long) y2 - y1;
        long rawSteps = Math.max(Math.abs(dx), Math.abs(dy));
        if (rawSteps > MAX_CHART_LINE_STEPS) {
            return;
        }
        int steps = (int) rawSteps;
        for (int s = 0; s <= steps; s++) {
            int x = x1 + (steps == 0 ? 0 : (int) (dx * s / steps));
            int y = y1 + (steps == 0 ? 0 : (int) (dy * s / steps));
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static int chartCoordinate(double value, int min, int max) {
        if (!Double.isFinite(value) || min > max) {
            return INVALID_COORDINATE;
        }
        double clipped = Math.max(min, Math.min(max, value));
        return (int) Math.round(clipped);
    }

    private static double price(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing or nonnumeric price");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value <= 0 || value > MAX_PRICE) {
            throw new IllegalArgumentException("Price outside accepted range");
        }
        return value;
    }

    private static long integer(JsonObject object, String name, long min, long max) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing or nonnumeric integer");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max || value != Math.rint(value)) {
            throw new IllegalArgumentException("Integer outside accepted range");
        }
        return (long) value;
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
