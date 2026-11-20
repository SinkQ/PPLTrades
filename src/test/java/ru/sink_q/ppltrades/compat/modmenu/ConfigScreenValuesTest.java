package ru.sink_q.ppltrades.compat.modmenu;

import ru.sink_q.ppltrades.PpltradesConfig;

import java.util.List;
import java.util.Map;

public final class ConfigScreenValuesTest {
    public static void main(String[] args) {
        for (String url : List.of("", "   ", " https://example.org:8443/api/ ", "https://localhost:8080",
                "https://example.org:65535")) {
            if (!PpltradesConfigScreen.validUrl(url)) {
                throw new AssertionError("Rejected valid URL: " + url);
            }
        }
        for (String url : List.of("/", "example.org", "https://", "https://example.org?mode=1",
                "https://example.org#prices", "ftp://example.org", "https://example.org:65536",
                "https://example.org:0", "https://example.org:", "https://name@example.org",
                "http://localhost:8080", "https://example.org/a b")) {
            if (PpltradesConfigScreen.validUrl(url)) {
                throw new AssertionError("Accepted invalid URL: " + url);
            }
        }
        if (PpltradesConfig.isSecureApiUrl("") || PpltradesConfig.isSecureApiUrl("  ")
                || !PpltradesConfig.isSecureApiUrl("https://example.org/api")
                || PpltradesConfig.isSecureApiUrl("http://example.org")) {
            throw new AssertionError("HTTPS base URL validation did not distinguish secure URLs from blank/HTTP");
        }

        for (String proxy : List.of("system", "none", " ", "127.0.0.1:10808", "http://127.0.0.1:7890",
                "https://proxy.example.org:443")) {
            if (!PpltradesConfigScreen.validProxy(proxy)) {
                throw new AssertionError("Rejected valid proxy: " + proxy);
            }
        }
        for (String proxy : List.of("socks5://127.0.0.1:1080", "127.0.0.1", "ftp://host:21", "http://host:0")) {
            if (PpltradesConfigScreen.validProxy(proxy)) {
                throw new AssertionError("Accepted invalid proxy: " + proxy);
            }
        }

        var aliases = Map.of(
                "minecraft:redstone_block", List.of("ред блок", "A=B"),
                "example:custom_item", List.of("другой предмет"));
        var rows = PpltradesConfigScreen.aliasRows(aliases);
        if (!aliases.equals(PpltradesConfigScreen.aliasesOf(rows))) {
            throw new AssertionError("Existing aliases did not survive a round trip");
        }
        var edited = PpltradesConfigScreen.aliasesOf(List.of(
                "redstone_block = ред блок", "minecraft:redstone_block = ред блок", " ", "broken",
                " = no item", "minecraft: = no path", "Bad Item = no", "minecraft:empty = "));
        if (!Map.of("minecraft:redstone_block", List.of("ред блок")).equals(edited)) {
            throw new AssertionError("Unexpected aliases after editing: " + edited);
        }
    }
}
