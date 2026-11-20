package ru.sink_q.ppltrades;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sink_q.ppltrades.price.BarrelCollector;
import ru.sink_q.ppltrades.price.PriceApi;

public class Ppltrades implements ClientModInitializer {
    public static final String MOD_ID = "ppltrades";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

    private static final int PRICES_REFRESH_TICKS = 5 * 60 * 20;
    private static final int REPORTS_FLUSH_TICKS = 15 * 20;

    private static int ticks;
    private static String lastWorld;

    @Override
    public void onInitializeClient() {
        PpltradesConfig.load();
        TradeCategories.load();
        SignSearch.register();
        BarrelHighlight.register();

        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> TooltipHandler.append(stack, lines));

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // Все экраны: инвентари/сундуки, а также окно рецептов JEI.
            ScreenKeyboardEvents.afterKeyPress(screen).register(SignSearch::onScreenKeyPress);
            // открыли подсвеченную бочку — подсветка больше не нужна (в любом мире)
            if (screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen && client.level != null) {
                BarrelHighlight.onBarrelOpened(BarrelCollector.findTargetBarrel(client));
            }
            if (isCollectingActive()) {
                BarrelCollector.onScreenOpened(client, screen);
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ticks = 0;
            refreshVisiblePrices();
            if (isPriceTrackingActive()) {
                PriceApi.refreshConfig();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PriceApi.clearPrices();
            PriceApi.saveQueue();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PriceApi.saveQueue());
        PriceApi.loadQueue();

        ClientTickEvents.END_CLIENT_TICK.register(Ppltrades::onTick);
    }

    private static void onTick(Minecraft mc) {
        if (!isPriceTrackingActive()) {
            return;
        }
        ticks++;
        if (ticks % PRICES_REFRESH_TICKS == 0 || PriceApi.retryDue()) {
            refreshVisiblePrices();
            PriceApi.refreshConfig();
        }
        String world = PepeLand.worldId();
        if (world != null && !world.equals(lastWorld)) { // переход между мирами PepeLand
            lastWorld = world;
            LOGGER.info("Мир PepeLand: {} ({})", world,
                    PriceApi.isTradingWorld(world) ? "сбор цен включён" : "не торговый, бочки не читаются");
        }
        if (isCollectingActive()) {
            BarrelCollector.tick(mc);
            if ((ticks % REPORTS_FLUSH_TICKS == 0 && PriceApi.hasQueued()) || PriceApi.hasFullBatch()) {
                PriceApi.flush(PepeLand.currentAddress());
            }
        }
    }

    public static void refreshVisiblePrices() {
        if (PpltradesConfig.get().showPrices && isPriceTrackingActive()) {
            PriceApi.refreshPrices();
        }
    }

    /** Цены собираются и показываются только на серверах PepeLand и при заданном адресе бэкенда. */
    private static boolean isPriceTrackingActive() {
        return !PpltradesConfig.activeApiUrl().isEmpty() && PepeLand.isConnected();
    }

    /** Бочки читаются только в торговом мире (список миров сервер отдаёт в /v1/config). */
    private static boolean isCollectingActive() {
        return isPriceTrackingActive() && PpltradesConfig.get().collectPrices
                && PriceApi.isTradingWorld(PepeLand.worldId());
    }
}
