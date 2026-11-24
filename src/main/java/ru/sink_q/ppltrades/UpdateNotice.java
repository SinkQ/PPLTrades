package ru.sink_q.ppltrades;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Сообщение в чате о новой версии мода. Последнюю версию и ссылку отдаёт сервер цен в /v1/config
 * (LATEST_MOD_VERSION / MOD_DOWNLOAD_URL в .env) — так владелец сам решает, когда объявить обновление.
 * Показывается один раз за запуск игры для каждой новой версии.
 */
public final class UpdateNotice {
    private static final Pattern LATEST_VERSION_CHARS = Pattern.compile("[A-Za-z0-9.+-]{1,64}");
    private static final Pattern RELEASE_TAG_CHARS = Pattern.compile("[A-Za-z0-9._+-]{1,128}");
    private static final String RELEASE_PATH = "/SinkQ/PPLTrades/releases/";
    private static String notifiedVersion;

    private UpdateNotice() {
    }

    /** Вызывается из сетевого потока после загрузки /v1/config. */
    public static void onConfig(String latestVersion, String downloadUrl) {
        if (!isValidLatestVersion(latestVersion)) {
            return;
        }
        URI uri = releaseUri(downloadUrl);
        if (uri == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!PpltradesConfig.get().notifyUpdates || latestVersion.equals(notifiedVersion)
                    || !isNewer(latestVersion, currentVersion()) || mc.player == null) {
                return;
            }
            notifiedVersion = latestVersion;
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

    static boolean isValidLatestVersion(String version) {
        return version != null && LATEST_VERSION_CHARS.matcher(version).matches();
    }

    /** Only canonical release links on the official project repository are accepted. */
    static URI releaseUri(String value) {
        if (value == null || value.length() > 512) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || uri.isOpaque()
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getRawAuthority())
                    || uri.getQuery() != null || uri.getFragment() != null) {
                return null;
            }
            String path = uri.getRawPath();
            if ((RELEASE_PATH + "latest").equals(path)) {
                return uri;
            }
            String tagPrefix = RELEASE_PATH + "tag/";
            if (path == null) {
                return null;
            }
            if (path.startsWith(tagPrefix)) {
                String tag = path.substring(tagPrefix.length());
                String safeTag = tag.replace("%2B", "+");
                if (RELEASE_TAG_CHARS.matcher(safeTag).matches()
                        && !safeTag.equals(".") && !safeTag.equals("..")) {
                    return uri;
                }
                return null;
            }

            String downloadPrefix = RELEASE_PATH + "download/";
            if (!path.startsWith(downloadPrefix)) {
                return null;
            }
            String[] segments = path.substring(downloadPrefix.length()).split("/", -1);
            if (segments.length != 2 || !segments[0].startsWith("v")) {
                return null;
            }
            String tagVersion = versionFromTag(segments[0].substring(1));
            String filenamePrefix = "ppltrades-";
            String filenameSuffix = ".jar";
            String filename = segments[1];
            if (!filename.startsWith(filenamePrefix) || !filename.endsWith(filenameSuffix)) {
                return null;
            }
            String filenameVersion = filename.substring(filenamePrefix.length(),
                    filename.length() - filenameSuffix.length());
            filenameVersion = filenameVersion.replace("%2B", "+").replace("%2b", "+");
            if (!isValidLatestVersion(filenameVersion) || !isSemverVersion(filenameVersion)
                    || !filenameVersion.equals(tagVersion)) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String versionFromTag(String rawVersion) {
        // GitHub encodes the version's '+' in the release tag as %2B; no other escapes are permitted.
        String version = rawVersion.replace("%2B", "+");
        if (!isValidLatestVersion(version)) {
            return null;
        }
        return version;
    }

    private static boolean isSemverVersion(String version) {
        try {
            SemanticVersion.parse(version);
            return true;
        } catch (Exception e) {
            return false;
        }
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
