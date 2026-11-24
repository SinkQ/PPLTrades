package ru.sink_q.ppltrades.price;

import com.google.gson.JsonObject;
import ru.sink_q.ppltrades.PrivateJsonFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Queue persistence is tested with small benign observations in a disposable directory. */
public final class QueueJsonTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("ppltrades-queue-json-test-");
        try {
            Path path = directory.resolve("queue-fixture.json");
            check(QueueJson.read(path, 2000) == null, "Absent queue was not distinguished");
            JsonObject report = new JsonObject();
            report.addProperty("world", "42");
            report.addProperty("key", "copper_block");
            report.addProperty("price", 12.5);
            QueueJson.write(path, List.of(report, report, report), 2);
            check(QueueJson.read(path, 2000).equals(List.of(report, report)), "Queue round trip or write cap failed");
            check(QueueJson.read(path, 1).equals(List.of(report)), "Queue read cap failed");
            check(QueueJson.read(path, 0).isEmpty(), "Full live queue did not suppress loading");
            Files.writeString(path, "[null,12,{\"world\":\"42\"},{\"world\":\"43\"}]");
            check(QueueJson.read(path, 1).getFirst().get("world").getAsString().equals("42"),
                    "Queue did not skip non-object entries");
            for (String invalid : List.of("[{\"world\":\"42\"},", "{}", "[] []")) {
                Files.writeString(path, invalid);
                expectFailure(path);
                check(invalid.equals(Files.readString(path)), "Invalid queue was changed");
            }
            Files.writeString(path, " ".repeat(PrivateJsonFiles.QUEUE_MAX_BYTES + 1));
            expectFailure(path);
            check(Files.size(path) == PrivateJsonFiles.QUEUE_MAX_BYTES + 1L, "Oversized queue was changed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("QueueJsonTest: OK");
    }

    private static void expectFailure(Path path) throws Exception {
        try {
            QueueJson.read(path, 2000);
            throw new AssertionError("Invalid queue was accepted");
        } catch (IOException expected) {
            check("Local queue JSON could not be read".equals(expected.getMessage()) && expected.getCause() == null,
                    "Queue parse error exposed details");
        }
    }

    private static void check(boolean valid, String message) {
        if (!valid) {
            throw new AssertionError(message);
        }
    }
}
