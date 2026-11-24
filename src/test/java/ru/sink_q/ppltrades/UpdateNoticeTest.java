package ru.sink_q.ppltrades;

/** Сравнение версий для уведомления об обновлении (запуск: gradlew testUpdateNotice, входит в gradlew test). */
public final class UpdateNoticeTest {
    public static void main(String[] args) {
        check(UpdateNotice.isNewer("1.0.1+26.3", "1.0.0+26.3"), "1.0.1 > 1.0.0");
        check(UpdateNotice.isNewer("1.1.0+26.3", "1.0.9+26.3"), "1.1.0 > 1.0.9");
        check(!UpdateNotice.isNewer("1.0.0+26.3", "1.0.0+26.3"), "same version");
        check(!UpdateNotice.isNewer("1.0.0+26.3", "1.0.1+26.3"), "older on server");
        check(!UpdateNotice.isNewer("not a version", "1.0.0+26.3"), "garbage");
        System.out.println("UpdateNoticeTest: OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("UpdateNoticeTest failed: " + what);
        }
    }
}
