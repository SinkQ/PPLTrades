package ru.sink_q.ppltrades;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

public final class PepeLand {
    private PepeLand() {
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
