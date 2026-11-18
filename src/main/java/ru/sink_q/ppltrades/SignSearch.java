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
import ru.sink_q.ppltrades.mixin.AbstractContainerScreenAccessor;
import ru.sink_q.ppltrades.mixin.CreativeModeInventoryScreenAccessor;

/** Поиск предмета под курсором по табличкам модом Pochatok (клавиша N). */
public final class SignSearch {
    private static final String POCHATOK_ID = "pochatok";
    private static final String SEARCH_COMMAND = "signsearchmask";
    private static final boolean JEI_LOADED = FabricLoader.getInstance().isModLoaded("jei");

    private static KeyMapping searchKey;

    private SignSearch() {
    }

    public static void register() {
        searchKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ppltrades.sign_search",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_N,
                Ppltrades.KEY_CATEGORY
        ));
    }

    public static void onScreenKeyPress(Screen screen, KeyEvent event) {
        if (!searchKey.matches(event) || isTyping(screen)) {
            return;
        }

        ItemStack stack = getHoveredStack(screen);
        if (stack == null || stack.isEmpty()) {
            return;
        }

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
        mc.gui.hud.setOverlayMessage(Component.translatable("ppltrades.message.searching_signs", itemName), false);
    }

    /** Предмет под курсором: слот контейнера, затем JEI (список, закладки, окно рецептов). */
    private static ItemStack getHoveredStack(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?>) {
            Slot slot = ((AbstractContainerScreenAccessor) screen).ppltrades$getHoveredSlot();
            if (slot != null && slot.hasItem()) {
                return slot.getItem();
            }
        }
        return JEI_LOADED ? PpltradesJeiPlugin.getStackUnderMouse() : null;
    }

    /** Не реагируем на клавишу, пока игрок печатает в текстовом поле (поиск в творческом/JEI, наковальня и т.п.). */
    private static boolean isTyping(Screen screen) {
        if (screen.getFocused() instanceof EditBox box && box.isFocused()) {
            return true;
        }
        if (JEI_LOADED && PpltradesJeiPlugin.hasKeyboardFocus()) {
            return true;
        }
        if (screen instanceof CreativeModeInventoryScreen creative) {
            EditBox searchBox = ((CreativeModeInventoryScreenAccessor) creative).ppltrades$getSearchBox();
            return searchBox != null && searchBox.isVisible() && searchBox.isFocused();
        }
        return false;
    }
}
