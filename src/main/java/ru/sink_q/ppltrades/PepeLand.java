package ru.sink_q.ppltrades;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

public final class PepeLand {
    private PepeLand() {
    }

    /**
     * Идентификатор текущего мира: хэш сида, который сервер присылает при входе/смене мира (hex).
     * Все миры PepeLand — "minecraft:overworld", различаются они только этим числом. null вне мира.
     */
    public static String worldId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        long seed = ((ru.sink_q.ppltrades.mixin.BiomeManagerAccessor) mc.level.getBiomeManager())
                .ppltrades$getBiomeZoomSeed();
        return Long.toHexString(seed);
    }

    /** true, если игрок сейчас подключён к серверу, в адресе которого есть "pepeland". */
    public static boolean isConnected() {
        String address = currentAddress();
        return address != null && address.contains("pepeland");
    }

    /** Адрес текущего сервера в нижнем регистре или null (одиночная игра / меню). */
    public static String currentAddress() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return null;
        }
        ServerData server = mc.getCurrentServer();
        return server == null || server.ip == null ? null : server.ip.toLowerCase(Locale.ROOT).trim();
    }
}
