package ru.sink_q.ppltrades.price;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.Map;

/** Focused checks for defensive price-stat parsing (run as a Java main). */
public final class PriceStatsValidationTest {
    private static final String VALID = "{\"min\":0.01,\"avg\":2.5,\"median\":2,\"n\":3}";

    public static void main(String[] args) {
        checkStats(VALID, true, "valid stats");
        checkStats("{\"min\":1,\"avg\":900000,\"median\":900000,\"n\":2147483647}", true,
                "server maximum price and representable listing count");
        checkStats("{\"min\":5e-324,\"avg\":5e-324,\"median\":5e-324,\"n\":1}", true,
                "smallest positive representable price");
        checkStats("{\"min\":1,\"avg\":2,\"median\":2,\"n\":\"3\"}", false, "string count");
        checkStats("{\"min\":1,\"avg\":2,\"median\":2,\"n\":1.5}", false, "fractional count");
        checkStats("{\"min\":1,\"avg\":2,\"median\":2,\"n\":0}", false, "zero count");
        checkStats("{\"min\":1,\"avg\":2,\"median\":2,\"n\":2147483648}", false, "unrepresentable count");
        checkStats("{\"min\":\"1\",\"avg\":2,\"median\":2,\"n\":1}", false, "string price");
        checkStats("{\"min\":0,\"avg\":2,\"median\":2,\"n\":1}", false, "nonpositive price");
        checkStats("{\"min\":1,\"avg\":900000.01,\"median\":2,\"n\":1}", false, "out-of-range price");
        checkStats("{\"min\":1e309,\"avg\":2,\"median\":2,\"n\":1}", false, "nonfinite price");
        checkStats("{\"min\":3,\"avg\":2,\"median\":3,\"n\":1}", false, "min greater than average");
        checkStats("{\"min\":3,\"avg\":3,\"median\":2,\"n\":1}", false, "min greater than median");

        Map<String, Map<PriceStats.Window, PriceStats.Trend>> prices = PriceApi.parsePrices(JsonParser.parseString("""
                {"items":{
                  "good":{"hour":%s,"day":{"min":1,"avg":2,"median":2,"n":"bad"},
                          "week_prev":%s},
                  "bad-item":["not","an","object"],
                  "good-sibling":{"hour":%s}
                }}
                """.formatted(VALID, VALID, VALID)).getAsJsonObject());
        check(prices.size() == 2, "malformed item does not discard valid items");
        check(prices.containsKey("good") && prices.containsKey("good-sibling"), "valid siblings retained");
        check(prices.get("good").containsKey(PriceStats.Window.HOUR), "valid window retained");
        check(!prices.get("good").containsKey(PriceStats.Window.DAY), "invalid window omitted");
        check(prices.get("good").get(PriceStats.Window.WEEK).current() == null
                && prices.get("good").get(PriceStats.Window.WEEK).previous() != null,
                "valid previous window retained independently");
        System.out.println("PriceStatsValidationTest: OK");
    }

    private static void checkStats(String json, boolean expected, String description) {
        JsonElement element = JsonParser.parseString(json);
        boolean actual = PriceApi.parseStats(element) != null;
        if (actual != expected) {
            throw new AssertionError(description + ": expected valid=" + expected + ", got " + actual);
        }
    }

    private static void check(boolean ok, String description) {
        if (!ok) {
            throw new AssertionError(description);
        }
    }
}
