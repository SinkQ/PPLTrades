package ru.sink_q.ppltrades;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Uses only disposable local fixtures, without Fabric or Minecraft initialization. */
public final class PrivateJsonFilesTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("ppltrades-private-json-test-");
        try {
            privateBoundedFiles(directory);
            configFiles(directory);
            proxySettings();
            linkedFiles(directory);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("PrivateJsonFilesTest: OK");
    }

    private static void privateBoundedFiles(Path directory) throws Exception {
        Path path = directory.resolve("local.json");
        check(PrivateJsonFiles.readIfExists(path, 32) == null, "Missing file was not distinguished");
        String fixture = "{\"name\":\"медь\"}";
        int length = fixture.getBytes(StandardCharsets.UTF_8).length;
        PrivateJsonFiles.write(path, length, writer -> {
            try (var files = Files.list(directory)) {
                for (Path temporary : files.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList()) {
                    checkOwnerOnly(temporary);
                }
            }
            writer.write(fixture);
        });
        check(fixture.equals(PrivateJsonFiles.readIfExists(path, length)), "UTF-8 round trip failed");
        checkOwnerOnly(path);
        expectIoFailure(() -> PrivateJsonFiles.readIfExists(path, length - 1));
        expectIoFailure(() -> PrivateJsonFiles.write(path, length - 1, writer -> writer.write(fixture)));
        check(fixture.equals(Files.readString(path)), "Bounded write changed the previous file");
        expectIoFailure(() -> PrivateJsonFiles.write(path, length, writer -> {
            writer.write("{}");
            throw new IOException("Disposable fixture failure");
        }));
        check(fixture.equals(Files.readString(path)), "Failed publication changed the previous file");
        PrivateJsonFiles.write(path, 32, writer -> writer.write("{\"ok\":true}"));
        check("{\"ok\":true}".equals(Files.readString(path)), "Existing-file publication failed");
        try (var paths = Files.list(directory)) {
            check(paths.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "Temporary file was not removed");
        }

        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
            PrivateJsonFiles.readIfExists(path, 32);
            checkOwnerOnly(path);
        }
        Path token = directory.resolve("token-fixture.json");
        Files.writeString(token, " ".repeat(PrivateJsonFiles.TOKEN_MAX_BYTES + 1));
        expectIoFailure(() -> PrivateJsonFiles.readIfExists(token, PrivateJsonFiles.TOKEN_MAX_BYTES));
    }

    private static void configFiles(Path directory) throws Exception {
        Path path = directory.resolve("config-fixture.json");
        var absent = PpltradesConfig.readForLoad(path);
        check(absent.saveOnLoad() && absent.config().collectPrices, "Absent config lost the normal defaults");
        for (String invalid : List.of("", "{", "null", "[]", "{} {}", "{\"collectPrices\":null}",
                "{\"collectPrices\":\"unknown\"}", "{\"collectPrices\":12}")) {
            Files.writeString(path, invalid);
            var loaded = PpltradesConfig.readForLoad(path);
            check(!loaded.saveOnLoad() && !loaded.config().collectPrices, "Invalid config did not fail closed");
            check(invalid.equals(Files.readString(path)), "Invalid config was changed");
        }
        Files.writeString(path, " ".repeat(PrivateJsonFiles.CONFIG_MAX_BYTES + 1));
        var oversized = PpltradesConfig.readForLoad(path);
        check(!oversized.saveOnLoad() && !oversized.config().collectPrices, "Oversized config did not fail closed");
        check(Files.size(path) == PrivateJsonFiles.CONFIG_MAX_BYTES + 1L, "Oversized config was changed");
        byte[] invalidEncoding = {'{', '"', 'p', 'r', 'o', 'x', 'y', '"', ':', '"', (byte) 0xC3, '"', '}'};
        Files.write(path, invalidEncoding);
        var malformedText = PpltradesConfig.readForLoad(path);
        check(!malformedText.saveOnLoad() && !malformedText.config().collectPrices, "Invalid UTF-8 did not fail closed");
        check(java.util.Arrays.equals(invalidEncoding, Files.readAllBytes(path)), "Invalid UTF-8 was changed");

        PpltradesConfig config = new PpltradesConfig();
        config.collectPrices = false;
        config.proxy = "http://name:label@127.0.0.1:7890/";
        config.signSearchAliases = Map.of("minecraft:copper_block", List.of("Медный блок", "A=B"));
        // An explicit save can repair an ordinary invalid/oversized file.
        PpltradesConfig.writeConfig(path, config);
        check(!Files.readString(path).contains("name:label"), "Proxy userinfo was persisted");
        check("http://127.0.0.1:7890/".equals(config.proxy), "Local HTTP proxy changed");
        var valid = PpltradesConfig.readForLoad(path);
        check(valid.saveOnLoad() && !valid.config().collectPrices, "Valid opt-out config changed");
        check(config.signSearchAliases.equals(valid.config().signSearchAliases), "Ordinary aliases changed");
        checkOwnerOnly(path);

        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("Bad Item", List.of("invalid item"));
        aliases.put("minecraft:ignored", List.of(" ", "x".repeat(129)));
        for (int item = 0; item < 20; item++) {
            List<String> names = new ArrayList<>();
            for (int name = 0; name < 40; name++) {
                names.add("название " + name);
            }
            aliases.put("minecraft:item_" + item, names);
        }
        config.signSearchAliases = aliases;
        Files.writeString(path, new Gson().toJson(config));
        var loadedAliases = PpltradesConfig.readForLoad(path).config().signSearchAliases;
        check(loadedAliases.values().stream().mapToInt(List::size).sum() == 200, "Loaded aliases were not bounded");
        PpltradesConfig.writeConfig(path, config);
        var bounded = PpltradesConfig.readForLoad(path).config().signSearchAliases;
        check(bounded.values().stream().mapToInt(List::size).sum() == 200, "Total alias bound was not enforced");
        check(bounded.values().stream().allMatch(names -> names.size() <= 32), "Per-item alias bound was not enforced");
        check(!bounded.containsKey("Bad Item") && !bounded.containsKey("minecraft:ignored"), "Invalid aliases survived");
    }

    private static void proxySettings() {
        for (String clean : List.of("system", "none", "off", "127.0.0.1:10809", "http://127.0.0.1:7890/",
                "https://proxy.example.org:443", "http://[::1]:7890")) {
            check(Objects.equals(clean, PpltradesConfig.proxyWithoutCredentials(clean)), "Clean proxy changed");
        }
        check("proxy.example.org:3128".equals(PpltradesConfig.proxyWithoutCredentials("name:label@proxy.example.org:3128")),
                "Bare proxy userinfo was retained");
        check("http://proxy.example.org:3128/".equals(PpltradesConfig.proxyWithoutCredentials(
                "http://name:lab%20el@proxy.example.org:3128/")), "Encoded proxy userinfo was retained");
        for (String malformed : List.of("http://name label@proxy.example.org:3128",
                "http://name:lab%el@proxy.example.org:3128", "http://proxy.example.org:3128/path")) {
            check("none".equals(PpltradesConfig.proxyWithoutCredentials(malformed)), "Malformed proxy was retained");
        }
    }

    private static void linkedFiles(Path directory) throws Exception {
        Path target = directory.resolve("regular-fixture.json");
        Path link = directory.resolve("linked-fixture.json");
        String fixture = "{\"collectPrices\":false}";
        Files.writeString(target, fixture);
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            return; // Windows profiles commonly do not grant symlink creation.
        }
        expectIoFailure(() -> PrivateJsonFiles.readIfExists(link, 128));
        expectIoFailure(() -> PrivateJsonFiles.write(link, 128, writer -> writer.write("{}")));
        var loaded = PpltradesConfig.readForLoad(link);
        check(!loaded.saveOnLoad() && !loaded.config().collectPrices, "Linked config did not fail closed");
        check(fixture.equals(Files.readString(target)), "Linked leaf modified another fixture");
        PrivateJsonFiles.delete(link);
        check(Files.exists(target), "Deleting a leaf followed its target");
    }

    private static void checkOwnerOnly(Path path) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            check(Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------")),
                    "POSIX JSON permissions were not owner-only");
        }
    }

    private static void expectIoFailure(IoAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected a safe persistence failure");
        } catch (IOException expected) {
            check(expected.getCause() == null && expected.getMessage().startsWith("Local "),
                    "Persistence failure exposed exception details");
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }

    private static void check(boolean valid, String message) {
        if (!valid) {
            throw new AssertionError(message);
        }
    }
}
