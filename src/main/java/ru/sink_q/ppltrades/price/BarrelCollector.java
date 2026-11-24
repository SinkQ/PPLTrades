package ru.sink_q.ppltrades.price;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.sink_q.ppltrades.TradeCategories;

/**
 * Собирает цены из бочек-магазинов: когда игрок открывает бочку, к которой прикреплена табличка,
 * запоминаем предмет, который в ней лежит, и текст табличек. Цену из текста разбирает бэкенд.
 * Пустая бочка с табличкой отправляется как «распродано», чтобы её старая цена перестала учитываться.
 */
public final class BarrelCollector {
    /** Сколько тиков ждать содержимое бочки от сервера. */
    private static final int MAX_WAIT_TICKS = 40;

    /** Валюта сервера: не может быть товаром. */
    private static final Set<Item> CURRENCY = Set.of(Items.DIAMOND, Items.DIAMOND_BLOCK);

    /** Сколько разных товаров бочки отправлять серверу для выбора по табличке. */
    private static final int MAX_CANDIDATES = 10;

    private static Pending pending;

    private record Pending(ChestMenu menu, BlockPos barrelPos, Level level, int[] ticks) {
    }

    private BarrelCollector() {
    }

    /** Вызывается при открытии любого экрана. Бочку определяем по прицелу. */
    public static void onScreenOpened(Minecraft mc, Screen screen) {
        pending = null;
        if (!(screen instanceof ContainerScreen containerScreen)
                || containerScreen.getMenu().getType() != MenuType.GENERIC_9x3
                || mc.level == null) {
            return;
        }
        BlockPos barrel = findTargetBarrel(mc);
        if (barrel != null) {
            pending = new Pending(containerScreen.getMenu(), barrel, mc.level, new int[]{0});
        }
    }

    /** Вызывается каждый клиентский тик. */
    public static void tick(Minecraft mc) {
        Pending p = pending;
        if (p == null) {
            return;
        }
        // Меню закрыто (игрок закрыл бочку), сменился мир или сервер так и не прислал содержимое
        if (mc.player == null || mc.player.containerMenu != p.menu() || mc.level != p.level()
                || ++p.ticks()[0] > MAX_WAIT_TICKS) {
            pending = null;
            return;
        }
        // stateId становится ненулевым, когда сервер прислал содержимое контейнера
        if (p.menu().getStateId() == 0) {
            return;
        }
        pending = null;
        JsonObject report = buildReport(p.level(), p.barrelPos(), p.menu().getContainer());
        if (report != null) {
            PriceApi.enqueue(report);
        }
    }

    /** Бочка под прицелом. Если прицел на табличке, висящей на бочке, берём бочку за ней. */
    public static BlockPos findTargetBarrel(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof WallSignBlock) {
            pos = pos.relative(state.getValue(WallSignBlock.FACING).getOpposite());
            state = mc.level.getBlockState(pos);
        }
        return state.getBlock() instanceof BarrelBlock ? pos : null;
    }

    private static JsonObject buildReport(Level level, BlockPos barrel, Container container) {
        // Преобладающий товар в бочке: считаем по ключу "предмет@вариант", чтобы книги с разными чарами
        // и разные зелья не смешивались. Алмазы и алмазные блоки — оплата покупателей, а не товар.
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        Map<String, List<Integer>> slotCounts = new LinkedHashMap<>();
        boolean hasOther = false;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || CURRENCY.contains(stack.getItem())) {
                continue;
            }
            String key = ItemVariants.priceKey(stack);
            if (key == null) {
                hasOther = true;
                continue; // предметы модов
            }
            counts.merge(key, stack.getCount(), Integer::sum);
            samples.putIfAbsent(key, stack);
            slotCounts.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(stack.getCount());
        }
        JsonArray signs = collectSigns(level, barrel);
        if (signs.isEmpty()) {
            return null;
        }

        JsonObject report = new JsonObject();
        report.addProperty("dimension", level.dimension().identifier().toString());
        String world = ru.sink_q.ppltrades.PepeLand.worldId();
        if (world != null) {
            report.addProperty("world", world);
        }
        report.addProperty("x", barrel.getX());
        report.addProperty("y", barrel.getY());
        report.addProperty("z", barrel.getZ());
        report.add("signs", signs);
        if (counts.isEmpty()) {
            if (hasOther) {
                return null; // только предметы модов: сервер о них не знает, но бочка не пустая
            }
            // Пусто или одна оплата алмазами: товар раскупили, сервер уберёт старую цену этой бочки
            report.addProperty("empty", true);
            return report;
        }

        String key = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        ItemStack sample = samples.get(key);
        report.addProperty("item", TradeCategories.itemPath(sample));
        String variant = ItemVariants.variant(sample);
        if (variant != null) {
            report.addProperty("variant", variant);
        }
        report.addProperty("count", counts.get(key));
        report.addProperty("maxStack", sample.getMaxStackSize());
        report.addProperty("distinctItems", counts.size());

        // Все товары бочки: сервер выберет тот, о котором написано на табличке. Наполнитель бывает
        // многочисленнее товара (бочка зарядов ветра, а продаётся книга "Порыв ветра").
        JsonArray candidates = new JsonArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_CANDIDATES)
                .forEach(e -> {
                    ItemStack s = samples.get(e.getKey());
                    JsonObject c = new JsonObject();
                    c.addProperty("item", TradeCategories.itemPath(s));
                    String v = ItemVariants.variant(s);
                    if (v != null) {
                        c.addProperty("variant", v);
                    }
                    c.addProperty("count", e.getValue());
                    c.addProperty("maxStack", s.getMaxStackSize());
                    Integer slot = uniformSlotSize(slotCounts.get(e.getKey()));
                    if (slot != null) {
                        c.addProperty("slotSize", slot);
                    }
                    candidates.add(c);
                });
        report.add("candidates", candidates);
        return report;
    }

    /**
     * Сколько предметов продавец кладёт в одну ячейку, если ячейки заполнены одинаково
     * (например, везде по 8 блоков): тогда «слот» на табличке значит именно это количество.
     * Берём самое частое количество, если им заполнено не меньше 2/3 ячеек и оно наибольшее
     * (неполные остатки после покупок не мешают). Иначе null — «слот» будет считаться полным стаком.
     */
    static Integer uniformSlotSize(List<Integer> counts) {
        if (counts == null || counts.size() < 2) {
            return null;
        }
        Map<Integer, Integer> freq = new java.util.HashMap<>();
        counts.forEach(c -> freq.merge(c, 1, Integer::sum));
        Map.Entry<Integer, Integer> mode = freq.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        int max = counts.stream().max(Integer::compare).orElseThrow();
        return mode.getKey() == max && mode.getValue() * 3 >= counts.size() * 2 ? mode.getKey() : null;
    }

    /** Тексты настенных табличек, прикреплённых к бочке. */
    private static JsonArray collectSigns(Level level, BlockPos barrel) {
        JsonArray signs = new JsonArray();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos signPos = barrel.relative(dir);
            BlockState state = level.getBlockState(signPos);
            if (!(state.getBlock() instanceof WallSignBlock) || state.getValue(WallSignBlock.FACING) != dir) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(signPos);
            if (!(be instanceof SignBlockEntity sign)) {
                continue;
            }
            JsonArray lines = readLines(sign.getText(SignTextSlot.FRONT));
            if (lines == null) {
                lines = readLines(sign.getText(SignTextSlot.BACK));
            }
            if (lines != null) {
                signs.add(lines);
            }
        }
        return signs;
    }

    /** Строки таблички или null, если она пустая. */
    private static JsonArray readLines(SignText text) {
        List<Component> messages = text.getMessages(false);
        JsonArray lines = new JsonArray();
        boolean empty = true;
        for (Component message : messages) {
            String line = message.getString();
            if (line.length() > 90) {
                line = line.substring(0, 90);
            }
            if (!line.isBlank()) {
                empty = false;
            }
            lines.add(line);
        }
        return empty ? null : lines;
    }
}
