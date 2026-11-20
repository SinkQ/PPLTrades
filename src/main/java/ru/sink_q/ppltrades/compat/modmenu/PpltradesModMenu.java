package ru.sink_q.ppltrades.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.net.URI;

public final class PpltradesModMenu implements ModMenuApi {
    private static final URI CLOTH_CONFIG = URI.create("https://modrinth.com/mod/cloth-config");

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return PpltradesConfigScreen::create;
        }
        // Без Cloth Config экрана настроек нет: объясняем, что поставить, вместо пустой кнопки
        return parent -> new ConfirmScreen(open -> {
            if (open) {
                ConfirmLinkScreen.confirmLinkNow(parent, CLOTH_CONFIG, true);
            } else {
                Minecraft.getInstance().gui.setScreen(parent);
            }
        }, Component.translatable("ppltrades.config.need_cloth.title"),
                Component.translatable("ppltrades.config.need_cloth.message"),
                Component.translatable("ppltrades.config.need_cloth.open"),
                Component.translatable("gui.back"));
    }
}
