package ru.sink_q.ppltrades.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import ru.sink_q.ppltrades.Ppltrades;

/**
 * Интеграция с JEI. Класс загружается только самим JEI (entrypoint "jei_mod_plugin"),
 * поэтому без JEI мод работает как обычно.
 */
@JeiPlugin
public class PpltradesJeiPlugin implements IModPlugin {
    private static volatile IJeiRuntime runtime;

    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath(Ppltrades.MOD_ID, "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    /** Предмет под курсором в списке JEI, закладках или окне рецептов; null, если нет. */
    public static ItemStack getStackUnderMouse() {
        IJeiRuntime rt = runtime;
        if (rt == null) {
            return null;
        }
        ItemStack stack = rt.getIngredientListOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        if (stack == null) {
            stack = rt.getBookmarkOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        }
        if (stack == null) {
            stack = rt.getRecipesGui().getIngredientUnderMouse(VanillaTypes.ITEM_STACK).orElse(null);
        }
        return stack;
    }

    /** true, если в JEI сейчас активно поле поиска. */
    public static boolean hasKeyboardFocus() {
        IJeiRuntime rt = runtime;
        return rt != null && rt.getIngredientListOverlay().hasKeyboardFocus();
    }
}
