package ru.sink_q.ppltrades;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import ru.sink_q.ppltrades.compat.jei.PpltradesJeiPlugin;
import ru.sink_q.ppltrades.compat.rei.PpltradesReiPlugin;
import ru.sink_q.ppltrades.mixin.AbstractContainerScreenAccessor;
import ru.sink_q.ppltrades.mixin.CreativeModeInventoryScreenAccessor;

/** Поиск предмета под курсором по табличкам модом Pochatok (клавиша N). */
public final class SignSearch {
    private static final String POCHATOK_ID = "pochatok";
    private static final String SEARCH_COMMAND = "signsearchmask";
    private static final boolean JEI_LOADED = FabricLoader.getInstance().isModLoaded("jei");
    private static final boolean REI_LOADED = FabricLoader.getInstance().isModLoaded("roughlyenoughitems");

    private static KeyMapping searchKey;
    private static KeyMapping infoKey;

    private SignSearch() {
    }

    public static void register() {
        searchKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ppltrades.sign_search",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_N,
                Ppltrades.KEY_CATEGORY
        ));
        infoKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ppltrades.item_info",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_I,
                Ppltrades.KEY_CATEGORY
        ));
    }

    public static void onScreenKeyPress(Screen screen, KeyEvent event) {
        boolean search = searchKey.matches(event);
        boolean info = infoKey.matches(event) && !(screen instanceof ItemInfoScreen);
        if (!(search || info) || isTyping(screen)) {
            return;
        }

        ItemStack stack = getHoveredStack(screen);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (search) {
            search(stack);
        } else {
            Minecraft.getInstance().gui.setScreen(new ItemInfoScreen(screen, stack));
        }
    }

    /** Запустить поиск предмета по табличкам (Pochatok). Можно вызывать и из окна предмета. */
    public static void search(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        if (!FabricLoader.getInstance().isModLoaded(POCHATOK_ID)) {
            mc.player.sendOverlayMessage(Component.translatable("ppltrades.message.no_pochatok")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        var dispatcher = ClientCommands.getActiveDispatcher();
        if (dispatcher == null || dispatcher.getRoot().getChild(SEARCH_COMMAND) == null) {
            mc.player.sendOverlayMessage(Component.translatable("ppltrades.message.no_signsearchmask")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String vanillaPath = "minecraft".equals(id.getNamespace()) ? id.getPath() : null;
        TradeCategories.Entry entry = vanillaPath == null ? null : TradeCategories.get(vanillaPath);
        Component itemName = stack.getItemName();
        String mask = SignSearchMask.build(vanillaPath, entry == null ? null : entry.mask(), itemName.getString(),
                PpltradesConfig.get().signSearchAliases.get(id.toString()));
        if (mask == null) {
            return;
        }

        // Клиентская команда Pochatok; Fabric перехватывает её и не отправляет на сервер.
        mc.player.connection.sendCommand(SEARCH_COMMAND + " " + mask);
        showSearchToast(mc, PpltradesConfig.get().searchOverlay, itemName, mask);
    }

    /** Одно уведомление на поиск: новый поиск обновляет его, а не складывает в очередь. */
    private static final net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId SEARCH_TOAST =
            new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(6000L);

    /**
     * Уведомление в правом верхнем углу (на тёмной плашке, читается поверх любого фона):
     * заголовок «Поиск по табличкам», ниже — название предмета и/или реальный фильтр (regex для Pochatok).
     */
    static void showSearchToast(Minecraft mc, PpltradesConfig.SearchOverlay mode, Component itemName, String mask) {
        Component title;
        Component body;
        switch (mode) {
            case OFF -> {
                return;
            }
            case ITEM_NAME -> {
                title = Component.translatable("ppltrades.message.searching_signs.title");
                body = itemName.copy().withStyle(ChatFormatting.WHITE);
            }
            case FILTER -> {
                title = Component.translatable("ppltrades.message.searching_signs.title");
                body = Component.literal(mask).withStyle(ChatFormatting.YELLOW);
            }
            default -> { // BOTH
                title = Component.translatable("ppltrades.message.searching_signs", itemName);
                body = Component.literal(mask).withStyle(ChatFormatting.YELLOW);
            }
        }
        net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.gui.toastManager(), SEARCH_TOAST,
                title, body);
    }

    /** Предмет под курсором: слот контейнера, затем JEI и REI (список, закладки, окно рецептов). */
    static ItemStack getHoveredStack(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?>) {
            Slot slot = ((AbstractContainerScreenAccessor) screen).ppltrades$getHoveredSlot();
            if (slot != null && slot.hasItem()) {
                return slot.getItem();
            }
        }
        ItemStack stack = JEI_LOADED ? PpltradesJeiPlugin.getStackUnderMouse() : null;
        if ((stack == null || stack.isEmpty()) && REI_LOADED) {
            stack = PpltradesReiPlugin.getStackUnderMouse(screen);
        }
        return stack;
    }

    /** Не реагируем на клавишу, пока игрок печатает в текстовом поле (поиск в творческом/JEI, наковальня и т.п.). */
    static boolean isTyping(Screen screen) {
        if (screen.getFocused() instanceof EditBox box && box.isFocused()) {
            return true;
        }
        if ((JEI_LOADED && PpltradesJeiPlugin.hasKeyboardFocus())
                || (REI_LOADED && PpltradesReiPlugin.hasKeyboardFocus())) {
            return true;
        }
        if (screen instanceof CreativeModeInventoryScreen creative) {
            EditBox searchBox = ((CreativeModeInventoryScreenAccessor) creative).ppltrades$getSearchBox();
            return searchBox != null && searchBox.isVisible() && searchBox.isFocused();
        }
        return false;
    }
}
