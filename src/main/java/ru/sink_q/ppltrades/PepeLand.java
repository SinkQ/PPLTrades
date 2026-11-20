package ru.sink_q.ppltrades;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

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

    /** Принимаем только хосты в официальной зоне PepeLand, а не произвольные похожие адреса. */
    public static boolean isConnected() {
        String address = currentAddress();
        if (address == null || !ServerAddress.isValidAddress(address)) {
            return false;
        }
        String host = ServerAddress.parseString(address).getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.equals("pepeland.net") || host.endsWith(".pepeland.net");
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
