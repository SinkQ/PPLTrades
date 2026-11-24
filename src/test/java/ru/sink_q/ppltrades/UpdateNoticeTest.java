package ru.sink_q.ppltrades;

/** Сравнение версий для уведомления об обновлении (запуск: gradlew testUpdateNotice, входит в gradlew test). */
public final class UpdateNoticeTest {
    public static void main(String[] args) {
        check(UpdateNotice.isNewer("1.0.1+26.3", "1.0.0+26.3"), "1.0.1 > 1.0.0");
        check(UpdateNotice.isNewer("1.1.0+26.3", "1.0.9+26.3"), "1.1.0 > 1.0.9");
        check(!UpdateNotice.isNewer("1.0.0+26.3", "1.0.0+26.3"), "same version");
        check(!UpdateNotice.isNewer("1.0.0+26.3", "1.0.1+26.3"), "older on server");
        check(!UpdateNotice.isNewer("not a version", "1.0.0+26.3"), "garbage");
        check(UpdateNotice.isValidLatestVersion("1.0.2+26.3"), "ASCII version");
        check(!UpdateNotice.isValidLatestVersion("1.0.2+26.3/evil"), "version path suffix");
        check(!UpdateNotice.isValidLatestVersion("1.0.2+26.3\u202e"), "non-ASCII version");
        check(!UpdateNotice.isValidLatestVersion("a".repeat(65)), "overlong version");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/latest") != null,
                "latest release URL");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/tag/1.0.2+26.3") != null,
                "tag release URL");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/tag/1.0.2%2B26.3") != null,
                "encoded plus in release tag");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/download/v1.0.2%2B26.3/"
                + "ppltrades-1.0.2+26.3.jar") != null, "direct PPLTrades release asset");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/download/v1.0.2%2B26.3/"
                + "ppltrades-1.0.2%2B26.3.jar") != null, "encoded plus in asset filename");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/download/v1.0.2%2B26.3/"
                + "ppltrades-1.0.2%2b26.3.jar") != null, "lowercase encoded plus in asset filename");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/download/v1.0.2%2Fbad/"
                + "ppltrades-1.0.2+26.3.jar") == null, "encoded separator in asset tag");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/download/v1.0.2%2B26.3/"
                + "other-1.0.2+26.3.jar") == null, "non-PPLTrades asset");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/download/v1.0.2%2B26.3/"
                + "ppltrades-1.0.2%2F26.3.jar") == null, "encoded separator in asset filename");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/download/v1.0.2%2B26.3/"
                + "ppltrades-1.0.3+26.3.jar") == null, "mismatched asset version");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/download/v1.0.2.jar/"
                + "ppltrades-1.0.2.jar.jar") == null, "ambiguous double extension");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/tag/..") == null,
                "dot-segment tag");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/tag/a%2Fb") == null,
                "encoded separator");
        check(UpdateNotice.releaseUri("https://user@github.com/SinkQ/PPLTrades/releases/latest") == null,
                "userinfo");
        check(UpdateNotice.releaseUri("https://github.com:443/SinkQ/PPLTrades/releases/latest") == null,
                "explicit port");
        check(UpdateNotice.releaseUri("https://github.com.evil/SinkQ/PPLTrades/releases/latest") == null,
                "lookalike host");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/latest?next=evil") == null,
                "query");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/latest#fragment") == null,
                "fragment");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/tag/%2e%2e") == null,
                "encoded dot segment");
        check(UpdateNotice.releaseUri("https://github.com/SinkQ/PPLTrades/releases/tag/" + "a".repeat(500)) == null,
                "overlong URL");
        System.out.println("UpdateNoticeTest: OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("UpdateNoticeTest failed: " + what);
        }
    }
}
