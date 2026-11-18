package ru.sink_q.ppltrades;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Local/test-only audit of SignSearchMask against a JSONL coverage export. */
public final class SignSearchCoverage {
    private SignSearchCoverage() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> arguments = parseArguments(args);
        String signsFile = arguments.get("signsFile");
        if (signsFile == null || signsFile.isBlank()) {
            throw new IllegalArgumentException("Specify --signsFile <path>");
        }

        Map<String, String> catalog = SignSearchMaskTest.loadCatalog();
        Map<String, List<String>> aliases = arguments.containsKey("signAliasesFile")
                ? readStringListMap(Path.of(arguments.get("signAliasesFile"))) : Map.of();
        Map<String, String> names = arguments.containsKey("signNamesFile")
                ? readStringMap(Path.of(arguments.get("signNamesFile"))) : Map.of();
        List<SignGroup> groups = readSigns(Path.of(signsFile));

        Set<String> paths = new TreeSet<>(aliases.keySet());
        paths.addAll(names.keySet());
        groups.forEach(group -> paths.add(group.itemPath));

        Map<String, Pattern> baselineMasks = buildMasks(paths, catalog, names, Map.of());
        Map<String, Pattern> candidateMasks = buildMasks(paths, catalog, names, aliases);
        Map<String, List<AliasPattern>> candidateAliasMasks = buildAliasMasks(aliases);
        Audit audit = audit(groups, baselineMasks, candidateMasks, candidateAliasMasks);

        Path reportDir = Path.of("build", "reports");
        Files.createDirectories(reportDir);
        String outputTag = outputTag(Path.of(signsFile));
        Path missesFile = reportDir.resolve("sign-search-coverage-" + outputTag + "-misses.tsv");
        Path reportFile = reportDir.resolve("sign-search-coverage-" + outputTag + "-report.txt");
        writeMisses(missesFile, audit.misses);
        writeReport(reportFile, Path.of(signsFile), missesFile, audit, aliases.isEmpty());

        System.out.println("Sign search coverage audit");
        System.out.println("JSONL: " + Path.of(signsFile).toAbsolutePath());
        System.out.printf(Locale.ROOT, "Deduped sign groups: %d (weighted occurrences: %d)%n",
                audit.totalGroups, audit.totalWeight);
        System.out.printf(Locale.ROOT, "Own-item matches before aliases: %d/%d (weighted %d/%d)%n",
                audit.baselineOwnGroups, audit.totalGroups, audit.baselineOwnWeight, audit.totalWeight);
        System.out.printf(Locale.ROOT, "Own-item matches after aliases:  %d/%d (weighted %d/%d)%n",
                audit.candidateOwnGroups, audit.totalGroups, audit.candidateOwnWeight, audit.totalWeight);
        System.out.printf(Locale.ROOT, "Source item paths with any own-item match: %d/%d before, %d/%d after%n",
                audit.baselineAnyPathCount, audit.totalPathCount,
                audit.candidateAnyPathCount, audit.totalPathCount);
        System.out.printf(Locale.ROOT,
                "Cross-item matches before aliases: %d (weighted %d; %d distinct sign groups)%n",
                audit.baselineCrossPairs, audit.baselineCrossWeight, audit.baselineCrossGroups);
        System.out.printf(Locale.ROOT,
                "Cross-item matches after aliases:  %d (weighted %d; %d distinct sign groups)%n",
                audit.candidateCrossPairs, audit.candidateCrossWeight, audit.candidateCrossGroups);
        System.out.printf(Locale.ROOT, "Misses after aliases: %d (weighted %d); TSV: %s%n",
                audit.misses.size(), audit.missWeight, missesFile.toAbsolutePath());
        System.out.println("Report: " + reportFile.toAbsolutePath());
        printUnsafeJoins(audit.unsafeJoins);
    }

    private static String outputTag(Path signsFile) {
        String fileName = signsFile.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        if (extension > 0) {
            fileName = fileName.substring(0, extension);
        }
        String safeName = fileName.replaceAll("[^A-Za-z0-9._-]+", "_");
        String pathHash = Integer.toUnsignedString(
                signsFile.toAbsolutePath().normalize().toString().hashCode(), 16);
        return safeName + "-" + pathHash;
    }

    private static Map<String, String> parseArguments(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --option <value>, got " + argument);
            }
            String key = argument.substring(2);
            if (!Set.of("signsFile", "signAliasesFile", "signNamesFile").contains(key)) {
                throw new IllegalArgumentException("Unknown option: " + argument);
            }
            if (result.put(key, args[++i]) != null) {
                throw new IllegalArgumentException("Option specified more than once: " + argument);
            }
        }
        return result;
    }

    private static List<SignGroup> readSigns(Path file) throws IOException {
        Map<GroupKey, MutableSignGroup> deduped = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                JsonArray row;
                try {
                    JsonElement parsed = JsonParser.parseString(line);
                    if (!parsed.isJsonArray()) {
                        throw new IllegalArgumentException("row is not an array");
                    }
                    row = parsed.getAsJsonArray();
                } catch (RuntimeException exception) {
                    throw new IOException("Invalid JSONL row " + lineNumber + ": " + exception.getMessage(),
                            exception);
                }
                if (row.size() != 3 || !row.get(0).isJsonPrimitive()
                        || !row.get(1).isJsonArray() || !row.get(2).isJsonPrimitive()) {
                    throw new IOException("Unexpected JSONL row shape on line " + lineNumber);
                }
                String itemPath = row.get(0).getAsString();
                JsonArray signs = row.get(1).getAsJsonArray();
                long occurrences;
                try {
                    occurrences = row.get(2).getAsLong();
                } catch (RuntimeException exception) {
                    throw new IOException("Invalid occurrence count on line " + lineNumber, exception);
                }
                if (occurrences < 0) {
                    throw new IOException("Negative occurrence count on line " + lineNumber);
                }
                for (JsonElement signElement : signs) {
                    if (!signElement.isJsonArray()) {
                        throw new IOException("Expected sign lines array on line " + lineNumber);
                    }
                    List<String> lines = new ArrayList<>();
                    StringBuilder rendered = new StringBuilder();
                    for (JsonElement lineElement : signElement.getAsJsonArray()) {
                        if (!lineElement.isJsonPrimitive()) {
                            throw new IOException("Expected string sign line on line " + lineNumber);
                        }
                        String signLine = lineElement.getAsString();
                        lines.add(signLine);
                        // Pochatok lowercases each line, appends a space, then searches the joined text.
                        rendered.append(signLine.toLowerCase(Locale.ROOT)).append(' ');
                    }
                    GroupKey key = new GroupKey(itemPath, rendered.toString());
                    MutableSignGroup group = deduped.get(key);
                    if (group == null) {
                        deduped.put(key, new MutableSignGroup(itemPath, lines, rendered.toString(), occurrences));
                    } else {
                        group.occurrences = Math.addExact(group.occurrences, occurrences);
                    }
                }
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Occurrence total exceeds the supported range", exception);
        }
        return deduped.values().stream().map(MutableSignGroup::toSignGroup).toList();
    }

    private static Map<String, List<String>> readStringListMap(Path file) throws IOException {
        JsonObject object = readJsonObject(file);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonArray()) {
                throw new IOException("Expected an array of aliases for item path " + entry.getKey());
            }
            List<String> values = new ArrayList<>();
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                if (!element.isJsonPrimitive()) {
                    throw new IOException("Expected string alias for item path " + entry.getKey());
                }
                values.add(element.getAsString());
            }
            result.put(entry.getKey(), List.copyOf(values));
        }
        return result;
    }

    private static Map<String, String> readStringMap(Path file) throws IOException {
        JsonObject object = readJsonObject(file);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                throw new IOException("Expected a localized name for item path " + entry.getKey());
            }
            result.put(entry.getKey(), entry.getValue().getAsString());
        }
        return result;
    }

    private static JsonObject readJsonObject(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw new IOException("Expected a JSON object in " + file);
            }
            return element.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse JSON file " + file + ": " + exception.getMessage(), exception);
        }
    }

    private static Map<String, Pattern> buildMasks(Set<String> paths, Map<String, String> catalog,
                                                   Map<String, String> names,
                                                   Map<String, List<String>> aliases) {
        Map<String, Pattern> result = new TreeMap<>();
        for (String path : paths) {
            String mask = SignSearchMask.build(path, catalog.get(path), names.get(path), aliases.get(path));
            if (mask != null) {
                result.put(path, Pattern.compile(mask));
            }
        }
        return result;
    }

    private static Map<String, List<AliasPattern>> buildAliasMasks(Map<String, List<String>> aliases) {
        Map<String, List<AliasPattern>> result = new TreeMap<>();
        aliases.forEach((path, values) -> {
            List<AliasPattern> patterns = new ArrayList<>();
            for (String alias : values) {
                String mask = SignSearchMask.build(null, null, null, List.of(alias));
                if (mask != null) {
                    patterns.add(new AliasPattern(alias, Pattern.compile(mask)));
                }
            }
            result.put(path, patterns);
        });
        return result;
    }

    private static Audit audit(List<SignGroup> groups, Map<String, Pattern> baselineMasks,
                               Map<String, Pattern> candidateMasks,
                               Map<String, List<AliasPattern>> candidateAliasMasks) {
        Audit audit = new Audit();
        audit.totalGroups = groups.size();
        audit.totalPathCount = groups.stream().map(group -> group.itemPath).distinct().count();
        Map<String, PathStats> pathStats = new HashMap<>();
        for (SignGroup group : groups) {
            audit.totalWeight = Math.addExact(audit.totalWeight, group.occurrences);
            PathStats stats = pathStats.computeIfAbsent(group.itemPath, ignored -> new PathStats());
            stats.groups++;
            Set<String> before = matches(group.rendered, baselineMasks);
            Set<String> after = matches(group.rendered, candidateMasks);
            boolean ownBefore = before.contains(group.itemPath);
            boolean ownAfter = after.contains(group.itemPath);
            if (ownBefore) {
                audit.baselineOwnGroups++;
                audit.baselineOwnWeight += group.occurrences;
                stats.baselineMatched++;
            }
            if (ownAfter) {
                audit.candidateOwnGroups++;
                audit.candidateOwnWeight += group.occurrences;
                stats.candidateMatched++;
            } else {
                audit.misses.add(group);
                audit.missWeight += group.occurrences;
            }

            Set<String> beforeForeign = foreignPaths(before, group.itemPath);
            Set<String> afterForeign = foreignPaths(after, group.itemPath);
            if (!beforeForeign.isEmpty()) {
                audit.baselineCrossGroups++;
                audit.baselineCrossWeight += group.occurrences * beforeForeign.size();
                audit.baselineCrossPairs += beforeForeign.size();
            }
            if (!afterForeign.isEmpty()) {
                audit.candidateCrossGroups++;
                audit.candidateCrossWeight += group.occurrences * afterForeign.size();
                audit.candidateCrossPairs += afterForeign.size();
            }
            for (String target : afterForeign) {
                if (beforeForeign.contains(target)) {
                    continue;
                }
                List<String> matchingAliases = matchingAliases(candidateAliasMasks.get(target), group.rendered);
                audit.unsafeJoins.computeIfAbsent(new JoinKey(target, group.itemPath), ignored -> new JoinStats())
                        .add(group.occurrences, matchingAliases);
            }
        }
        for (PathStats stats : pathStats.values()) {
            if (stats.baselineMatched > 0) {
                audit.baselineAnyPathCount++;
            }
            if (stats.candidateMatched > 0) {
                audit.candidateAnyPathCount++;
            }
        }
        return audit;
    }

    private static Set<String> matches(String sign, Map<String, Pattern> masks) {
        Set<String> result = new LinkedHashSet<>();
        masks.forEach((path, pattern) -> {
            if (pattern.matcher(sign).find()) {
                result.add(path);
            }
        });
        return result;
    }

    private static Set<String> foreignPaths(Set<String> matches, String sourcePath) {
        Set<String> result = new LinkedHashSet<>(matches);
        result.remove(sourcePath);
        return result;
    }

    private static List<String> matchingAliases(List<AliasPattern> masks, String sign) {
        if (masks == null || masks.isEmpty()) {
            return List.of();
        }
        List<String> matching = new ArrayList<>();
        for (AliasPattern alias : masks) {
            if (alias.pattern.matcher(sign).find()) {
                matching.add(alias.value);
            }
        }
        return matching;
    }

    private static void writeMisses(Path file, List<SignGroup> misses) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("itemPath\toccurrences\tsignLines");
            writer.newLine();
            for (SignGroup miss : misses) {
                writer.write(tsv(miss.itemPath));
                writer.write('\t');
                writer.write(Long.toString(miss.occurrences));
                writer.write('\t');
                writer.write(String.join("\\n", miss.lines.stream().map(SignSearchCoverage::tsv).toList()));
                writer.newLine();
            }
        }
    }

    private static String tsv(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void writeReport(Path file, Path signsFile, Path missesFile, Audit audit,
                                    boolean noCandidateAliases) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("Sign search coverage audit");
            writer.newLine();
            writer.write("JSONL: " + signsFile.toAbsolutePath());
            writer.newLine();
            writer.write("Deduped sign groups: " + audit.totalGroups + " (weighted occurrences: "
                    + audit.totalWeight + ")");
            writer.newLine();
            writer.write("Own-item matches before aliases: " + audit.baselineOwnGroups + "/" + audit.totalGroups
                    + " (weighted " + audit.baselineOwnWeight + "/" + audit.totalWeight + ")");
            writer.newLine();
            writer.write("Own-item matches after aliases: " + audit.candidateOwnGroups + "/" + audit.totalGroups
                    + " (weighted " + audit.candidateOwnWeight + "/" + audit.totalWeight + ")");
            writer.newLine();
            writer.write("Source item paths with any own-item match: " + audit.baselineAnyPathCount + "/"
                    + audit.totalPathCount + " before, " + audit.candidateAnyPathCount + "/"
                    + audit.totalPathCount + " after");
            writer.newLine();
            writer.write("Cross-item matches before aliases (foreign item-path-to-sign pairs): "
                    + audit.baselineCrossPairs + " (weighted " + audit.baselineCrossWeight
                    + "; distinct sign groups " + audit.baselineCrossGroups + ")");
            writer.newLine();
            writer.write("Cross-item matches after aliases (foreign item-path-to-sign pairs): "
                    + audit.candidateCrossPairs + " (weighted " + audit.candidateCrossWeight
                    + "; distinct sign groups " + audit.candidateCrossGroups + ")");
            writer.newLine();
            writer.write("Misses after aliases: " + audit.misses.size() + " (weighted " + audit.missWeight
                    + "); TSV: " + missesFile.toAbsolutePath());
            writer.newLine();
            if (noCandidateAliases) {
                writer.write("No candidate alias file supplied.");
                writer.newLine();
            }
            writeUnsafeJoins(writer, audit.unsafeJoins);
        }
    }

    private static void printUnsafeJoins(Map<JoinKey, JoinStats> unsafeJoins) {
        if (unsafeJoins.isEmpty()) {
            System.out.println("Unsafe candidate joins: 0");
            return;
        }
        System.out.println("Unsafe candidate joins (new foreign matches):");
        unsafeJoins.forEach((key, stats) ->
                System.out.printf(Locale.ROOT, "  %s <- %s: %d sign groups (weighted %d), candidate aliases %s%n",
                        key.targetPath, key.sourcePath, stats.groups, stats.weight,
                        stats.aliases.isEmpty() ? "[unattributed]" : stats.aliases));
    }

    private static void writeUnsafeJoins(BufferedWriter writer, Map<JoinKey, JoinStats> unsafeJoins)
            throws IOException {
        writer.write("Unsafe candidate joins (new foreign matches): " + unsafeJoins.size());
        writer.newLine();
        if (unsafeJoins.isEmpty()) {
            writer.write("None");
            writer.newLine();
            return;
        }
        for (Map.Entry<JoinKey, JoinStats> entry : unsafeJoins.entrySet()) {
            JoinKey key = entry.getKey();
            JoinStats stats = entry.getValue();
            writer.write(key.targetPath + " <- " + key.sourcePath + ": " + stats.groups
                    + " sign groups (weighted " + stats.weight + "), candidate aliases "
                    + (stats.aliases.isEmpty() ? "[unattributed]" : stats.aliases));
            writer.newLine();
        }
    }

    private record GroupKey(String itemPath, String rendered) {
    }

    private record SignGroup(String itemPath, List<String> lines, String rendered, long occurrences) {
    }

    private record AliasPattern(String value, Pattern pattern) {
    }

    private record JoinKey(String targetPath, String sourcePath) implements Comparable<JoinKey> {
        @Override
        public int compareTo(JoinKey other) {
            int targetComparison = targetPath.compareTo(other.targetPath);
            return targetComparison != 0 ? targetComparison : sourcePath.compareTo(other.sourcePath);
        }
    }

    private static final class MutableSignGroup {
        private final String itemPath;
        private final List<String> lines;
        private final String rendered;
        private long occurrences;

        private MutableSignGroup(String itemPath, List<String> lines, String rendered, long occurrences) {
            this.itemPath = itemPath;
            this.lines = List.copyOf(lines);
            this.rendered = rendered;
            this.occurrences = occurrences;
        }

        private SignGroup toSignGroup() {
            return new SignGroup(itemPath, lines, rendered, occurrences);
        }
    }

    private static final class PathStats {
        private long groups;
        private long baselineMatched;
        private long candidateMatched;
    }

    private static final class JoinStats {
        private long groups;
        private long weight;
        private final Set<String> aliases = new TreeSet<>();

        private void add(long occurrences, List<String> matchingAliases) {
            groups++;
            weight += occurrences;
            aliases.addAll(matchingAliases);
        }
    }

    private static final class Audit {
        private long totalGroups;
        private long totalWeight;
        private long baselineOwnGroups;
        private long baselineOwnWeight;
        private long candidateOwnGroups;
        private long candidateOwnWeight;
        private long totalPathCount;
        private long baselineAnyPathCount;
        private long candidateAnyPathCount;
        private long baselineCrossPairs;
        private long baselineCrossWeight;
        private long baselineCrossGroups;
        private long candidateCrossPairs;
        private long candidateCrossWeight;
        private long candidateCrossGroups;
        private long missWeight;
        private final List<SignGroup> misses = new ArrayList<>();
        private final Map<JoinKey, JoinStats> unsafeJoins = new TreeMap<>();
    }
}
