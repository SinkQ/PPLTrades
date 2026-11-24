package ru.sink_q.ppltrades.price;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import ru.sink_q.ppltrades.PrivateJsonFiles;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Bounded queue persistence without constructing a JSON tree for the complete array. */
final class QueueJson {
    private static final Gson GSON = new Gson();

    private QueueJson() {
    }

    /** Stage the bounded result before adding anything to the live queue. */
    static List<JsonObject> read(Path path, int maxEntries) throws IOException {
        try {
            String source = PrivateJsonFiles.readIfExists(path, PrivateJsonFiles.QUEUE_MAX_BYTES);
            if (source == null) {
                return null;
            }
            List<JsonObject> result = new ArrayList<>();
            try (JsonReader reader = new JsonReader(new StringReader(source))) {
                // Retain Gson's legacy local-file syntax (comments etc.), but limit nesting.
                reader.setStrictness(Strictness.LENIENT);
                reader.setNestingLimit(64);
                reader.beginArray();
                while (reader.hasNext()) {
                    if (result.size() < maxEntries && reader.peek() == JsonToken.BEGIN_OBJECT) {
                        result.add(GSON.fromJson(reader, JsonObject.class));
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endArray();
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new IOException();
                }
            }
            return result;
        } catch (IOException | RuntimeException failure) {
            throw new IOException("Local queue JSON could not be read");
        }
    }

    static void write(Path path, Iterable<JsonObject> queue, int maxEntries) throws IOException {
        PrivateJsonFiles.write(path, PrivateJsonFiles.QUEUE_MAX_BYTES, writer -> {
            JsonWriter json = GSON.newJsonWriter(writer);
            json.beginArray();
            int count = 0;
            for (JsonObject entry : queue) {
                if (count++ >= maxEntries) {
                    break;
                }
                GSON.toJson(entry, json);
            }
            json.endArray();
            json.flush();
        });
    }
}
