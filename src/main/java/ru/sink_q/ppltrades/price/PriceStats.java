package ru.sink_q.ppltrades.price;

/** Статистика цены за окно времени. Цены в алмазах за 1 предмет. */
public record PriceStats(double min, double avg, double median, int n) {

    /** Окна статистики в порядке отображения; key совпадает с ключом в ответе бэкенда. */
    public enum Window {
        HOUR("hour"), DAY("day"), WEEK("week");

        public final String key;

        Window(String key) {
            this.key = key;
        }
    }

    /**
     * Цена за окно и за предыдущий период той же длины (например, вчерашний день для окна "день").
     * Любое из значений может быть null, но не оба сразу.
     */
    public record Trend(PriceStats current, PriceStats previous) {
    }
}
