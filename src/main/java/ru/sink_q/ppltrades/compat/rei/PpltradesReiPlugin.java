package ru.sink_q.ppltrades.compat.rei;

import me.shedaniel.math.Point;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.gui.widgets.TextField;
import me.shedaniel.rei.api.client.overlay.OverlayListWidget;
import me.shedaniel.rei.api.client.overlay.ScreenOverlay;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/**
 * Интеграция с REI. Плагин загружает только сам REI (entrypoint "rei_client"); остальной код мода
 * обращается к этому классу, только если REI установлен, поэтому без REI мод работает как обычно.
 */
public class PpltradesReiPlugin implements REIClientPlugin {
    /** Предмет под курсором в списке REI, избранном или окне рецептов; null, если нет. */
    public static ItemStack getStackUnderMouse(Screen screen) {
        REIRuntime rt = REIRuntime.getInstance();
        EntryStack<?> entry = null;
        if (rt.isOverlayVisible()) {
            ScreenOverlay overlay = rt.getOverlay().orElse(null);
            if (overlay != null) {
                entry = overlay.getEntryList().getFocusedStack();
                if (entry == null || entry.isEmpty()) {
                    entry = overlay.getFavoritesList().map(OverlayListWidget::getFocusedStack).orElse(null);
                }
            }
        }
        if (entry == null || entry.isEmpty()) {
            // окно рецептов и другие экраны, для которых REI знает, что под курсором
            Minecraft mc = Minecraft.getInstance();
            Point mouse = new Point(mc.mouseHandler.getScaledXPos(mc.getWindow()),
                    mc.mouseHandler.getScaledYPos(mc.getWindow()));
            entry = ScreenRegistry.getInstance().getFocusedStack(screen, mouse);
        }
        if (entry == null || entry.isEmpty() || entry.getType() != VanillaEntryTypes.ITEM) {
            return null;
        }
        return entry.castValue();
    }

    /** true, если в REI сейчас активно поле поиска. */
    public static boolean hasKeyboardFocus() {
        TextField field = REIRuntime.getInstance().getSearchTextField();
        return field != null && field.isFocused();
    }
}
