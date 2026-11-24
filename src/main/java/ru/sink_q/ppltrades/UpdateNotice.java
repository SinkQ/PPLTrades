package ru.sink_q.ppltrades;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;

/**
 * Сообщение в чате о новой версии мода. Последнюю версию и ссылку отдаёт сервер цен в /v1/config
 * (LATEST_MOD_VERSION / MOD_DOWNLOAD_URL в .env) — так владелец сам решает, когда объявить обновление.
 * Показывается один раз за запуск игры для каждой новой версии.
 */
public final class UpdateNotice {
    private static String notifiedVersion;

    private UpdateNotice() {
    }

    /** Вызывается из сетевого потока после загрузки /v1/config. */
    public static void onConfig(String latestVersion, String downloadUrl) {
        if (latestVersion == null || downloadUrl == null || !PpltradesConfig.get().notifyUpdates
                || latestVersion.equals(notifiedVersion) || !isNewer(latestVersion, currentVersion())) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(downloadUrl);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return;
        }
        notifiedVersion = latestVersion;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) {
                notifiedVersion = null; // покажем при следующей загрузке настроек
                return;
            }
            Component link = Component.translatable("ppltrades.update.download").withStyle(style -> style
                    .withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenUrl(uri))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(uri.toString()))));
            mc.player.sendSystemMessage(Component.translatable("ppltrades.update.available",
                            Component.literal(latestVersion).withStyle(ChatFormatting.WHITE),
                            Component.literal(currentVersion()).withStyle(ChatFormatting.GRAY))
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(" "))
                    .append(link));
        });
    }

    static boolean isNewer(String latest, String current) {
        try {
            // строго semver: Version.parse принял бы любую строку и сравнил её как текст
            return SemanticVersion.parse(latest).compareTo(SemanticVersion.parse(current)) > 0;
        } catch (Exception e) {
            return false; // непонятная версия — лучше промолчать, чем звать обновляться зря
        }
    }

    static String currentVersion() {
        return FabricLoader.getInstance().getModContainer(Ppltrades.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0");
    }
}
