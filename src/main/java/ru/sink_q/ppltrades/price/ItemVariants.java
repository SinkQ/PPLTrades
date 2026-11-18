package ru.sink_q.ppltrades.price;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import ru.sink_q.ppltrades.TradeCategories;

import java.util.ArrayList;
import java.util.List;

/**
 * Вариант предмета для раздельной статистики цен: у предметов одного id цена может сильно отличаться.
 * <ul>
 *     <li>чародейская книга — набор чар с уровнями: {@code sharpness=1} или {@code mending=1,unbreaking=3};</li>
 *     <li>зелья и стрелы с эффектом — тип зелья: {@code strong_swiftness}.</li>
 * </ul>
 * Ключ цены: {@code enchanted_book@sharpness=1}; без варианта — просто id.
 */
public final class ItemVariants {
    private ItemVariants() {
    }

    /** Вариант предмета или null, если для этого предмета варианты не различаются. */
    public static String variant(ItemStack stack) {
        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null && !stored.isEmpty()) {
            List<String> parts = new ArrayList<>();
            stored.entrySet().forEach(e -> parts.add(name(e.getKey()) + "=" + e.getIntValue()));
            parts.sort(null);
            return String.join(",", parts);
        }
        PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
        if (potion != null && potion.potion().isPresent()) {
            return name(potion.potion().get());
        }
        return null;
    }

    /** Ключ цены для ванильного предмета ({@code enchanted_book@sharpness=1}) или null для предметов модов. */
    public static String priceKey(ItemStack stack) {
        String path = TradeCategories.itemPath(stack);
        if (path == null) {
            return null;
        }
        String variant = variant(stack);
        return variant == null ? path : path + "@" + variant;
    }

    private static String name(Holder<?> holder) {
        return holder.unwrapKey()
                .map(ResourceKey::identifier)
                .map(id -> "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString())
                .orElse("unknown");
    }
}
