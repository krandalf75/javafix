package io.github.krandalf75.javafix;



import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.node.ArrayNode;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.krandalf75.javafix.analysis.PmdRuleProvider;

import io.github.krandalf75.javafix.analysis.SpringBootRuleProvider;



import java.io.BufferedInputStream;

import java.io.BufferedOutputStream;

import java.io.ByteArrayOutputStream;

import java.io.IOException;

import java.io.InputStream;

import java.io.OutputStream;

import java.nio.channels.FileChannel;

import java.nio.ByteBuffer;

import java.nio.charset.CharacterCodingException;

import java.nio.charset.CodingErrorAction;

import java.nio.charset.StandardCharsets;

import java.nio.file.Files;

import java.nio.file.Path;

import java.nio.file.Paths;

import java.nio.file.attribute.PosixFilePermission;

import java.nio.file.StandardCopyOption;

import java.nio.file.StandardOpenOption;

import java.security.MessageDigest;

import java.util.Comparator;

import java.time.Duration;

import java.util.ArrayList;

import java.util.HashMap;

import java.util.HashSet;

import java.util.LinkedHashMap;

import java.util.LinkedHashSet;

import java.util.List;

import java.util.Map;

import java.util.Set;

import java.util.concurrent.LinkedBlockingQueue;

import java.util.concurrent.CancellationException;

import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicLong;

import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;



public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String JDTLS_RESOURCE = "/embedded/jdtls.tar.gz";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    private static final Duration DIAGNOSTIC_QUIET_PERIOD = Duration.ofSeconds(5);



    private Main() {}



    public static void main(String[] args) {

        int exitCode = run(args, System.out, System.err);

        if (exitCode != 0) System.exit(exitCode);

    }



    static int run(String[] args, java.io.OutputStream stdout, java.io.PrintStream stderr) {

        return run(args, stdout, stderr, Main::analyze);

    }



    static int run(String[] args, java.io.OutputStream stdout, java.io.PrintStream stderr,

                   Analyzer analyzer) {

        try {

            if (args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0])) {

                usage();

                return 0;

            }

            if (!"analyze".equals(args[0]) && !"repair".equals(args[0])) {

                throw new IllegalArgumentException("Comando desconocido: " + args[0]);

            }

            Options options = Options.parse(args);

            options.cancellation.check();

            ObjectNode result = "analyze".equals(args[0]) ? analyzer.analyze(options) : repair(options, analyzer);

            JSON.writerWithDefaultPrettyPrinter().writeValue(stdout, result);

            return 0;

        } catch (Exception e) {

            stderr.println("javafix: " + e.getMessage());

            return 2;

        }

    }



    static ObjectNode analyze(Options options) throws Exception {

        options.cancellation.check();

        ProjectContext context = resolveProject(options.project);

        Path root = context.root();

        List<Path> targets = resolveTargets(options, root, context.discoveryRoot(), context.inferredFile());

        if (targets.isEmpty()) {

            throw new IllegalArgumentException("No se encontraron ficheros Java para analizar.");

        }

        options.cancellation.check();

        Path serverHome = extractJdtls();

        options.cancellation.check();

        Map<Path, byte[]> projectMetadata = snapshotProjectMetadata(root);

        Path workspace = Files.createTempDirectory("javafix-jdtls-workspace-");

        Path configuration = Files.createTempDirectory("javafix-jdtls-config-");

        List<ObjectNode> fileResults = new ArrayList<>();

        boolean diagnosticsComplete;



        try (LspSession session = LspSession.start(

            serverHome, configuration, workspace, root, options.cancellation)) {

            Map<String, List<JsonNode>> diagnosticsByUri = new HashMap<>();

            for (Path target : targets) {

                options.cancellation.check();

                String uri = target.toUri().toString();

                String text = Files.readString(target);

                session.openDocument(uri, text);

                diagnosticsByUri.put(uri, new ArrayList<>());

            }

            diagnosticsComplete = session.awaitDiagnostics(

                diagnosticsByUri, DIAGNOSTIC_QUIET_PERIOD, Duration.ofSeconds(90));

            options.cancellation.check();

            List<Path> semanticallyValidTargets = targets.stream()

                .filter(target -> diagnosticsByUri.getOrDefault(target.toUri().toString(), List.of()).stream()

                    .noneMatch(diagnostic -> diagnostic.path("severity").asInt() == 1))

                .toList();

            options.cancellation.check();

            Map<Path, List<ObjectNode>> pmdDiagnostics = new PmdRuleProvider().analyze(semanticallyValidTargets);

            options.cancellation.check();

            Map<Path, List<ObjectNode>> springDiagnostics =

                new SpringBootRuleProvider().analyze(semanticallyValidTargets);

            options.cancellation.check();



            for (Path target : targets) {

                options.cancellation.check();

                String uri = target.toUri().toString();

                List<JsonNode> diagnostics = diagnosticsByUri.getOrDefault(uri, List.of());

                ObjectNode file = JSON.createObjectNode();

                file.put("path", root.relativize(target).toString().replace('\\', '/'));

                ArrayNode findings = file.putArray("diagnostics");

                List<ObjectNode> jdtFindings = new ArrayList<>();

                for (JsonNode diagnostic : diagnostics) {

                    ObjectNode enriched = enrichDiagnostic(session, uri, diagnostic);

                    jdtFindings.add(enriched);

                    findings.add(enriched);

                }

                for (ObjectNode diagnostic : pmdDiagnostics.getOrDefault(target, List.of())) {

                    attachVerifiedPmdActions(diagnostic, jdtFindings);

                    findings.add(diagnostic);

                }

                for (ObjectNode diagnostic : springDiagnostics.getOrDefault(target, List.of())) {

                    findings.add(diagnostic);

                }

                fileResults.add(file);

            }

        } finally {

            restoreProjectMetadata(projectMetadata);

            if (Boolean.getBoolean("javafix.keepWorkspace")) {

                System.err.println("JDTLS workspace retained at " + workspace);

            } else {

                deleteTree(workspace);

            }

            deleteTree(configuration);

        }



        ObjectNode summary = JSON.createObjectNode();

        int total = fileResults.stream().mapToInt(f -> f.path("diagnostics").size()).sum();

        int errors = countSeverity(fileResults, "Error");

        int warnings = countSeverity(fileResults, "Warning");

        summary.put("filesAnalyzed", targets.size());

        summary.put("diagnostics", total);

        summary.put("errors", errors);

        summary.put("warnings", warnings);

        summary.put("analysisComplete", diagnosticsComplete);



        ObjectNode result = JSON.createObjectNode();

        result.put("schemaVersion", "1");

        result.put("status", diagnosticsComplete ? "completed" : "incomplete");

        result.putArray("providers").add("JDTLS").add("PMD").add("SpringBoot");

        result.set("summary", summary);

        ArrayNode files = result.putArray("files");

        fileResults.forEach(files::add);

        return result;

    }



    private static void attachVerifiedPmdActions(ObjectNode pmdFinding, List<ObjectNode> jdtFindings) {

        String ruleId = pmdFinding.path("ruleId").asText();

        if (!"PMD/CloseResource".equals(ruleId)) return;

        ObjectNode resolution = (ObjectNode) pmdFinding.path("resolution");

        ArrayNode actions = resolution.putArray("jdtlsActions");

        for (ObjectNode jdtFinding : jdtFindings) {

            String message = jdtFinding.path("message").asText().toLowerCase();

            boolean equivalent = message.contains("resource leak") || message.contains("not closed");

            if (!equivalent || !rangesOverlap(pmdFinding.path("range"), jdtFinding.path("range"))) continue;

            for (JsonNode action : jdtFinding.path("resolution").path("jdtlsActions")) {

                if (!action.path("fixesDiagnostic").asBoolean()

                    || !action.hasNonNull("repairId") || !action.hasNonNull("workspaceEdit")) continue;

                ObjectNode associated = action.deepCopy();

                associated.put("associatedDiagnostic", jdtFinding.path("message").asText());

                actions.add(associated);

            }

        }

        boolean available = !actions.isEmpty();

        resolution.put("hasJdtlsAction", available);

        resolution.put("automaticFixAvailable", available);

        if (available) {

            resolution.put("note",

                "Acción JDTLS asociada por regla y rango; la acción solo se ofrece si existe un WorkspaceEdit directo.");

        }

    }



    private static boolean rangesOverlap(JsonNode first, JsonNode second) {

        JsonNode secondOneBased = second.path("start").has("character")

            ? oneBasedRange(second) : second;

        JsonNode firstStart = first.path("start");

        JsonNode firstEnd = first.path("end");

        JsonNode secondStart = secondOneBased.path("start");

        JsonNode secondEnd = secondOneBased.path("end");

        return comparePositions(firstStart, secondEnd) <= 0 && comparePositions(secondStart, firstEnd) <= 0;

    }



    private static int comparePositions(JsonNode first, JsonNode second) {

        int line = Integer.compare(first.path("line").asInt(), second.path("line").asInt());

        return line != 0 ? line : Integer.compare(first.path("column").asInt(),

            second.path("column").asInt());

    }



    static ObjectNode repair(Options options) throws Exception {

        return repair(options, Main::analyze);

    }



    static ObjectNode repair(Options options, Analyzer analyzer) throws Exception {

        options.cancellation.check();

        if (options.plan == null) throw new IllegalArgumentException("repair requiere --plan <archivo.json>.");

        JsonNode plan = JSON.readTree(options.plan.toFile());

        validateRepairPlan(plan);

        ObjectNode report = analyzer.analyze(options);

        options.cancellation.check();

        Map<String, JsonNode> selected = new HashMap<>();

        for (JsonNode file : report.path("files")) {

            for (JsonNode diagnostic : file.path("diagnostics")) {

                for (JsonNode action : diagnostic.path("resolution").path("jdtlsActions")) {

                    if (action.hasNonNull("repairId")) selected.put(action.path("repairId").asText(), action);

                }

            }

        }

        List<JsonNode> actions = new ArrayList<>();

        Set<String> ids = new HashSet<>();

        for (JsonNode item : plan.path("repairs")) {

            options.cancellation.check();

            String id = item.isTextual() ? item.asText() : item.path("id").asText();

            if (id.isBlank() || !ids.add(id)) throw new IllegalArgumentException("ID de reparación vacío o duplicado.");

            JsonNode action = selected.get(id);

            if (action == null || !action.hasNonNull("workspaceEdit")) {

                throw new IllegalArgumentException("Reparación obsoleta o no aplicable: " + id);

            }

            actions.add(action);

        }

        Map<Path, byte[]> original = new HashMap<>();

        Map<Path, String> updated = new HashMap<>();

        ObjectNode combinedEdit = JSON.createObjectNode();

        ObjectNode combinedChanges = combinedEdit.putObject("changes");

        ArrayNode combinedDocumentChanges = combinedEdit.putArray("documentChanges");

        for (JsonNode action : actions) {

            JsonNode edit = action.path("workspaceEdit");

            edit.path("changes").fields().forEachRemaining(entry -> {

                ArrayNode edits = combinedChanges.withArray(entry.getKey());

                entry.getValue().forEach(e -> {

                    boolean duplicate = false;

                    for (JsonNode existing : edits) {

                        if (existing.equals(e)) {

                            duplicate = true;

                            break;

                        }

                    }

                    if (!duplicate) edits.add(e);

                });

            });

            edit.path("documentChanges").forEach(combinedDocumentChanges::add);

        }

        collectWorkspaceEdits(options.project, combinedEdit, original, updated);

        options.cancellation.check();

        ObjectNode result = JSON.createObjectNode();

        result.put("schemaVersion", "1");

        result.put("status", options.apply ? "applied" : "preview");

        ArrayNode repairs = result.putArray("repairs");

        ids.forEach(id -> repairs.addObject().put("id", id));

        ArrayNode changes = result.putArray("changes");

        for (var entry : updated.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {

            ObjectNode change = changes.addObject();

            change.put("path", options.project.toRealPath().relativize(entry.getKey()).toString().replace('\\', '/'));

            change.put("beforeHash", sha256(original.get(entry.getKey())));

            change.put("afterHash", sha256(entry.getValue().getBytes(StandardCharsets.UTF_8)));

            change.put("preview", options.apply ? "written" : "not-written");

            if (!options.apply) change.put("content", entry.getValue());

        }

        if (options.apply) {

            writeTransaction(original, updated, options.cancellation);

            // Once the write transaction commits, finish the post-analysis so the caller

            // receives the applied result rather than an ambiguous cancellation error.

            options.cancellation = CancellationToken.none();

            ObjectNode postAnalysis = analyzer.analyze(options);

            result.set("postAnalysis", postAnalysis);

            result.set("diagnosticChanges", compareDiagnostics(report, postAnalysis));

        }

        return result;

    }



    @FunctionalInterface

    interface Analyzer {

        ObjectNode analyze(Options options) throws Exception;

    }



    static void validateRepairPlan(JsonNode plan) {

        if (!plan.isObject() || !plan.fieldNames().hasNext()

            || !"1".equals(plan.path("schemaVersion").asText())

            || !plan.path("repairs").isArray() || plan.path("repairs").isEmpty()) {

            throw new IllegalArgumentException("Plan inválido: se esperaba schemaVersion 1 y repairs no vacío.");

        }

        Set<String> allowedRoot = Set.of("schemaVersion", "repairs");

        plan.fieldNames().forEachRemaining(name -> {

            if (!allowedRoot.contains(name)) {

                throw new IllegalArgumentException("Propiedad desconocida en plan v1: " + name);

            }

        });

        for (JsonNode item : plan.path("repairs")) {

            if (item.isTextual()) {

                if (item.asText().isBlank()) {

                    throw new IllegalArgumentException("ID de reparación vacío o duplicado.");

                }

                continue;

            }

            if (!item.isObject() || !item.hasNonNull("id") || !item.path("id").isTextual()

                || item.path("id").asText().isBlank()) {

                throw new IllegalArgumentException("Cada reparación debe ser un ID o un objeto con id textual.");

            }

            Set<String> allowedItem = Set.of("id", "parameters");

            item.fieldNames().forEachRemaining(name -> {

                if (!allowedItem.contains(name)) {

                    throw new IllegalArgumentException("Propiedad desconocida en reparación v1: " + name);

                }

            });

            if (item.has("parameters")

                && (!item.path("parameters").isObject() || !item.path("parameters").isEmpty())) {

                throw new IllegalArgumentException("Esta versión admite solo un objeto parameters vacío.");

            }

        }

    }



    static ObjectNode compareDiagnostics(JsonNode before, JsonNode after) {

        Map<String, List<ObjectNode>> previous = diagnosticsByIdentity(before);

        Map<String, List<ObjectNode>> current = diagnosticsByIdentity(after);

        ObjectNode changes = JSON.createObjectNode();

        ArrayNode resolved = changes.putArray("resolved");

        ArrayNode persistent = changes.putArray("persistent");

        ArrayNode newlyReported = changes.putArray("new");

        changes.put("complete", "completed".equals(before.path("status").asText())

            && "completed".equals(after.path("status").asText()));

        Set<String> identities = new LinkedHashSet<>(previous.keySet());

        identities.addAll(current.keySet());

        for (String identity : identities) {

            List<ObjectNode> oldFindings = previous.getOrDefault(identity, List.of());

            List<ObjectNode> newFindings = current.getOrDefault(identity, List.of());

            int common = Math.min(oldFindings.size(), newFindings.size());

            for (int i = 0; i < common; i++) persistent.add(newFindings.get(i));

            for (int i = common; i < oldFindings.size(); i++) resolved.add(oldFindings.get(i));

            for (int i = common; i < newFindings.size(); i++) newlyReported.add(newFindings.get(i));

        }

        return changes;

    }



    private static Map<String, List<ObjectNode>> diagnosticsByIdentity(JsonNode report) {

        Map<String, List<ObjectNode>> findings = new LinkedHashMap<>();

        for (JsonNode file : report.path("files")) {

            String path = file.path("path").asText();

            for (JsonNode diagnostic : file.path("diagnostics")) {

                String identity = path + "\n" + text(diagnostic, "source", "")

                    + "\n" + text(diagnostic, "ruleId", "")

                    + "\n" + diagnostic.path("code") + "\n" + text(diagnostic, "message", "");

                ObjectNode summary = JSON.createObjectNode();

                summary.put("path", path);

                summary.put("source", text(diagnostic, "source", ""));

                if (diagnostic.has("code")) summary.set("code", diagnostic.path("code").deepCopy());

                if (diagnostic.has("ruleId")) summary.set("ruleId", diagnostic.path("ruleId").deepCopy());

                summary.put("severity", text(diagnostic, "severity", "Unknown"));

                summary.put("message", text(diagnostic, "message", ""));

                if (diagnostic.has("range")) summary.set("range", diagnostic.path("range").deepCopy());

                findings.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(summary);

            }

        }

        return findings;

    }



    static void collectWorkspaceEdits(Path project, JsonNode edit, Map<Path, byte[]> original,

                                      Map<Path, String> updated) throws Exception {

        Map<String, List<JsonNode>> editsByUri = new HashMap<>();

        edit.path("changes").fields().forEachRemaining(entry ->

            entry.getValue().forEach(e -> editsByUri.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(e)));

        for (JsonNode documentChange : edit.path("documentChanges")) {

            if (!documentChange.has("textDocument") || !documentChange.path("edits").isArray()) {

                throw new IllegalArgumentException("WorkspaceEdit contiene operaciones de recurso no soportadas.");

            }

            String uri = documentChange.path("textDocument").path("uri").asText();

            documentChange.path("edits").forEach(e ->

                editsByUri.computeIfAbsent(uri, ignored -> new ArrayList<>()).add(e));

        }

        for (var entry : editsByUri.entrySet()) {

            Path path = Path.of(java.net.URI.create(entry.getKey())).toRealPath();

            Path root = project.toRealPath();

            if (!path.startsWith(root) || !Files.isRegularFile(path)) {

                throw new IllegalArgumentException("WorkspaceEdit apunta fuera del proyecto o a un archivo inexistente: " + path);

            }

            byte[] bytes = original.computeIfAbsent(path, p -> {

                try { return Files.readAllBytes(p); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }

            });

            String source;

            try {

                source = StandardCharsets.UTF_8.newDecoder()

                    .onMalformedInput(CodingErrorAction.REPORT)

                    .onUnmappableCharacter(CodingErrorAction.REPORT)

                    .decode(ByteBuffer.wrap(bytes)).toString();

            } catch (CharacterCodingException malformedUtf8) {

                throw new IllegalArgumentException("No se puede reparar un archivo que no sea UTF-8 válido: " + path,

                    malformedUtf8);

            }

            List<JsonNode> fileEdits = entry.getValue();

            List<int[]> ranges = new ArrayList<>();

            for (JsonNode e : fileEdits) {

                int start = offset(source, e.path("range").path("start"));

                int end = offset(source, e.path("range").path("end"));

                if (end < start) throw new IllegalArgumentException("Rango de edición inválido.");

                ranges.add(new int[]{start, end});

            }

            Set<Integer> redundantDeletes = new HashSet<>();

            for (int i = 0; i < fileEdits.size(); i++) {

                if (!fileEdits.get(i).path("newText").asText().isEmpty()) continue;

                int[] outer = ranges.get(i);

                for (int j = 0; j < fileEdits.size(); j++) {

                    int[] inner = ranges.get(j);

                    if (i != j && outer[0] <= inner[0] && outer[1] >= inner[1]

                        && (outer[0] != inner[0] || outer[1] != inner[1])) redundantDeletes.add(j);

                }

            }

            List<Integer> order = new ArrayList<>();

            for (int i = 0; i < fileEdits.size(); i++) {

                if (!redundantDeletes.contains(i)) order.add(i);

            }

            order.sort(Comparator.comparingInt((Integer i) -> ranges.get(i)[0]).reversed());

            int priorStart = Integer.MAX_VALUE;

            StringBuilder text = new StringBuilder(source);

            for (int i : order) {

                int[] range = ranges.get(i);

                if (range[1] > priorStart) throw new IllegalArgumentException("Ediciones solapadas para " + path);

                text.replace(range[0], range[1], fileEdits.get(i).path("newText").asText());

                priorStart = range[0];

            }

            updated.put(path, text.toString());

        }

    }



    static int offset(String text, JsonNode position) {

        int line = position.path("line").asInt(-1);

        int column = position.path("character").asInt(-1);

        if (line < 0 || column < 0) throw new IllegalArgumentException("Posición LSP inválida.");

        int index = 0;

        for (int current = 0; current < line; current++) {

            index = text.indexOf('\n', index);

            if (index < 0) throw new IllegalArgumentException("Rango LSP fuera del documento.");

            index++;

        }

        int end = text.indexOf('\n', index);

        if (end < 0) end = text.length();

        int result = index + column;

        if (result > end) throw new IllegalArgumentException("Columna LSP fuera del documento.");

        return result;

    }



    static void writeTransaction(Map<Path, byte[]> original, Map<Path, String> updated) throws IOException {

        writeTransaction(original, updated, CancellationToken.none(), ignored -> {}, ignored -> {});

    }



    static void writeTransaction(Map<Path, byte[]> original, Map<Path, String> updated,

                                 TransactionHook beforeReplace) throws IOException {

        writeTransaction(original, updated, CancellationToken.none(), beforeReplace, ignored -> {});

    }



    static void writeTransaction(Map<Path, byte[]> original, Map<Path, String> updated,

                                 CancellationToken cancellation) throws IOException {

        writeTransaction(original, updated, cancellation, ignored -> {}, ignored -> {});

    }



    static void writeTransaction(Map<Path, byte[]> original, Map<Path, String> updated,

                                 CancellationToken cancellation, TransactionHook beforeReplace,

                                 TransactionHook afterReplace) throws IOException {

        cancellation.check();

        List<Path> written = new ArrayList<>();

        Map<Path, byte[]> replacementBytes = new HashMap<>();

        updated.forEach((path, text) -> replacementBytes.put(path, text.getBytes(StandardCharsets.UTF_8)));

        try {

            for (Path path : updated.keySet().stream().sorted().toList()) {

                cancellation.check();

                replaceIfUnchanged(path, original.get(path), replacementBytes.get(path),

                    cancellation, beforeReplace);

                written.add(path);

                afterReplace.run(path);

                cancellation.check();

            }

        } catch (Exception failure) {

            for (Path path : written) {

                try {

                    if (java.util.Arrays.equals(replacementBytes.get(path), Files.readAllBytes(path))) {

                        replaceFile(path, original.get(path));

                    }

                } catch (Exception rollbackFailure) {

                    failure.addSuppressed(rollbackFailure);

                }

            }

            if (failure instanceof CancellationException cancelled) throw cancelled;

            if (failure instanceof IOException io) throw io;

            throw new IOException("No se pudo aplicar la reparación.", failure);

        }

    }



    private static void replaceIfUnchanged(Path path, byte[] expected, byte[] replacement,

                                            CancellationToken cancellation,

                                            TransactionHook beforeReplace) throws IOException {

        Path temp = Files.createTempFile(path.getParent(), ".javafix-", ".tmp");

        try {

            Files.write(temp, replacement);

            preservePosixPermissions(path, temp);

            beforeReplace.run(path);

            cancellation.check();

            if (!java.util.Arrays.equals(expected, Files.readAllBytes(path))) {

                throw new IOException("El archivo cambió durante la reparación: " + path);

            }

            moveReplacing(temp, path);

        } finally {

            Files.deleteIfExists(temp);

        }

    }



    private static void replaceFile(Path path, byte[] replacement) throws IOException {

        Path temp = Files.createTempFile(path.getParent(), ".javafix-rollback-", ".tmp");

        try {

            Files.write(temp, replacement);

            preservePosixPermissions(path, temp);

            moveReplacing(temp, path);

        } finally {

            Files.deleteIfExists(temp);

        }

    }



    private static void preservePosixPermissions(Path source, Path target) throws IOException {

        try {

            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);

            Files.setPosixFilePermissions(target, permissions);

        } catch (UnsupportedOperationException unsupported) {

            // The filesystem does not expose POSIX permissions (for example, Windows).

        }

    }



    private static void moveReplacing(Path source, Path target) throws IOException {

        try {

            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {

            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);

        }

    }



    @FunctionalInterface

    interface TransactionHook {

        void run(Path path) throws IOException;

    }



    private static String sha256(byte[] bytes) throws Exception {

        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

    }



    static String repairId(String uri, String title, JsonNode range, JsonNode edit, byte[] source)

        throws Exception {

        return sha256((uri + "\n" + title + "\n" + range + "\n" + edit + "\n" + sha256(source))

            .getBytes(StandardCharsets.UTF_8));

    }



    private static Map<Path, byte[]> snapshotProjectMetadata(Path root) throws IOException {

        Map<Path, byte[]> snapshot = new HashMap<>();

        for (String relative : List.of(".classpath", ".project",

            ".settings/org.eclipse.jdt.core.prefs", ".settings/org.eclipse.buildship.core.prefs")) {

            Path path = root.resolve(relative);

            snapshot.put(path, Files.isRegularFile(path) ? Files.readAllBytes(path) : null);

        }

        return snapshot;

    }



    private static void restoreProjectMetadata(Map<Path, byte[]> snapshot) throws IOException {

        for (var entry : snapshot.entrySet()) {

            if (entry.getValue() == null) {

                Files.deleteIfExists(entry.getKey());

            } else {

                Files.write(entry.getKey(), entry.getValue());

            }

        }

    }



    private static ObjectNode enrichDiagnostic(LspSession session, String uri, JsonNode diagnostic) throws Exception {

        ObjectNode finding = JSON.createObjectNode();

        finding.put("source", text(diagnostic, "source", "jdtls"));

        JsonNode code = diagnostic.get("code");

        if (code != null && !code.isNull()) {

            finding.set("code", code);

        } else {

            finding.putNull("code");

        }

        finding.putNull("ruleId");

        String severity = switch (diagnostic.path("severity").asInt()) {

            case 1 -> "Error";

            case 2 -> "Warning";

            case 3 -> "Information";

            case 4 -> "Hint";

            default -> "Unknown";

        };

        finding.put("severity", severity);

        String message = text(diagnostic, "message", "Diagnóstico sin mensaje");

        finding.put("message", message);

        finding.set("lspRange", diagnostic.path("range").deepCopy());

        finding.set("range", oneBasedRange(diagnostic.path("range")));



        ObjectNode resolution = finding.putObject("resolution");

        resolution.put("description", resolutionText(message));

        ArrayNode actions = resolution.putArray("jdtlsActions");

        boolean hasFix = false;

        for (JsonNode action : session.codeActions(uri, diagnostic)) {

            ObjectNode entry = actions.addObject();

            String title = text(action, "title", "Acción JDTLS");

            boolean suppression = title.toLowerCase().contains("suppresswarnings");

            String kind = text(action, "kind", "unknown");

            boolean hasFixOperation = action.hasNonNull("edit") && "quickfix".equals(kind);

            entry.put("title", title);

            entry.put("kind", kind);

            entry.put("available", true);

            entry.put("hasWorkspaceEdit", action.hasNonNull("edit"));

            entry.put("suppressesWarning", suppression);

            entry.put("fixesDiagnostic", !suppression && hasFixOperation);

            entry.put("repairable", !suppression && hasFixOperation);

            hasFix |= !suppression && hasFixOperation;

            if (action.hasNonNull("edit") && !suppression && "quickfix".equals(kind)) {

                entry.set("workspaceEdit", action.path("edit").deepCopy());

                byte[] source = Files.readAllBytes(Path.of(java.net.URI.create(uri)));

                entry.put("repairId", repairId(uri, title, diagnostic.path("range"),

                    action.path("edit"), source));

            }

            if (action.has("command")) {

                entry.set("command", action.get("command").deepCopy());

            }

        }

        resolution.put("hasJdtlsAction", !actions.isEmpty());

        resolution.put("automaticFixAvailable", hasFix);

        if (actions.isEmpty()) {

            resolution.put("note", "JDTLS no ha ofrecido una acción para este diagnóstico.");

        }

        return finding;

    }



    static ObjectNode oneBasedRange(JsonNode lspRange) {

        ObjectNode range = JSON.createObjectNode();

        range.put("lineNumbering", "1-based");

        range.put("columnNumbering", "1-based");

        range.put("endExclusive", true);

        range.set("start", oneBasedPosition(lspRange.path("start")));

        range.set("end", oneBasedPosition(lspRange.path("end")));

        return range;

    }



    private static ObjectNode oneBasedPosition(JsonNode lspPosition) {

        ObjectNode position = JSON.createObjectNode();

        if (lspPosition.has("line")) position.put("line", lspPosition.path("line").asInt() + 1);

        if (lspPosition.has("character")) position.put("column", lspPosition.path("character").asInt() + 1);

        return position;

    }



    static String resolutionText(String message) {

        String normalized = message.toLowerCase();

        if (normalized.contains("import") && normalized.contains("never used")) {

            return "Elimina el import no utilizado; revisa las acciones JDTLS disponibles.";

        }

        if (normalized.contains("resource leak") || normalized.contains("not closed")) {

            return "Cierra el recurso en todos los caminos de ejecución o usa try-with-resources.";

        }

        if (normalized.contains("null")) {

            return "Comprueba el valor antes de desreferenciarlo y revisa el contrato de nulabilidad.";

        }

        if (normalized.contains("never used") || normalized.contains("unused")) {

            return "Elimina la declaración o uso innecesario, o corrige la lógica para utilizarlo.";

        }

        return "Revisa el mensaje del diagnóstico y las acciones de corrección que ofrece JDTLS.";

    }



    private static int countSeverity(List<ObjectNode> files, String severity) {

        return files.stream().flatMap(f -> {

            List<JsonNode> list = new ArrayList<>();

            f.path("diagnostics").forEach(list::add);

            return list.stream();

        }).mapToInt(d -> severity.equals(d.path("severity").asText()) ? 1 : 0).sum();

    }



    private static String text(JsonNode node, String property, String fallback) {

        JsonNode value = node.get(property);

        return value == null || value.isNull() ? fallback : value.asText(fallback);

    }



    record ProjectContext(Path root, Path discoveryRoot, Path inferredFile) {}



    static ProjectContext resolveProject(Path input) throws IOException {

        Path project = input.toRealPath();

        if (Files.isDirectory(project)) {

            List<Path> descriptors = descriptorsIn(project);

            if (descriptors.size() > 1) {

                throw ambiguousDescriptors(project, descriptors);

            }

            if (descriptors.size() == 1) return new ProjectContext(project, project, null);

            Path parentDescriptor = nearestDescriptor(project.getParent());

            if (parentDescriptor != null) {

                return new ProjectContext(parentDescriptor.getParent(), project, null);

            }

            return new ProjectContext(project, project, null);

        }

        if (!Files.isRegularFile(project)) {

            throw new IllegalArgumentException("El proyecto no es un fichero ni un directorio: " + input);

        }

        if (isDescriptor(project)) return new ProjectContext(project.getParent(), project.getParent(), null);

        if (project.getFileName().toString().endsWith(".java")) {

            Path descriptor = nearestDescriptor(project.getParent());

            if (descriptor == null) {

                throw new IllegalArgumentException("No se encontró un pom.xml o build.gradle cercano a "

                    + project + ". Usa --project <descriptor|directorio> para fijar el contexto.");

            }

            return new ProjectContext(descriptor.getParent(), descriptor.getParent(), project);

        }

        throw new IllegalArgumentException("Descriptor no compatible: " + project

            + ". Se esperaba pom.xml, build.gradle o build.gradle.kts.");

    }



    private static Path nearestDescriptor(Path start) throws IOException {

        for (Path directory = start; directory != null; directory = directory.getParent()) {

            List<Path> descriptors = descriptorsIn(directory);

            if (descriptors.size() > 1) throw ambiguousDescriptors(directory, descriptors);

            if (!descriptors.isEmpty()) return descriptors.get(0);

        }

        return null;

    }



    private static List<Path> descriptorsIn(Path directory) throws IOException {

        if (directory == null || !Files.isDirectory(directory)) return List.of();

        List<Path> descriptors = new ArrayList<>();

        for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {

            Path candidate = directory.resolve(name);

            if (Files.isRegularFile(candidate)) descriptors.add(candidate.toRealPath());

        }

        return descriptors;

    }



    private static IllegalArgumentException ambiguousDescriptors(Path directory, List<Path> descriptors) {

        return new IllegalArgumentException("Hay varios descriptores en " + directory + ": "

            + descriptors.stream().map(p -> p.getFileName().toString()).toList()

            + ". Indica uno con --project.");

    }



    private static boolean isDescriptor(Path path) {

        String name = path.getFileName().toString();

        return "pom.xml".equals(name) || "build.gradle".equals(name) || "build.gradle.kts".equals(name);

    }



    static List<Path> resolveTargets(Options options, Path root) throws IOException {

        return resolveTargets(options, root, root, null);

    }



    static List<Path> resolveTargets(Options options, Path root, Path discoveryRoot, Path inferredFile)

        throws IOException {

        root = root.toRealPath();

        discoveryRoot = discoveryRoot.toRealPath();

        if (!discoveryRoot.startsWith(root)) {

            throw new IllegalArgumentException("La carpeta de búsqueda queda fuera del proyecto: " + discoveryRoot);

        }

        List<Path> files = new ArrayList<>();

        if (!options.files.isEmpty()) {

            for (Path input : options.files) {

                Path resolved = (input.isAbsolute() ? input : root.resolve(input)).toRealPath();

                if (!resolved.startsWith(root)) {

                    throw new IllegalArgumentException("El fichero queda fuera del proyecto: " + input);

                }

                if (!Files.isRegularFile(resolved) || !resolved.toString().endsWith(".java")) {

                    throw new IllegalArgumentException("No es un fichero Java válido: " + input);

                }

                files.add(resolved);

            }

        } else if (inferredFile != null) {

            files.add(inferredFile.toRealPath());

        } else {

            try (var paths = Files.walk(discoveryRoot)) {

                for (Path candidate : paths.filter(Files::isRegularFile)

                    .filter(p -> p.toString().endsWith(".java")).toList()) {

                    boolean generated = false;

                    for (Path part : discoveryRoot.relativize(candidate)) {

                        String name = part.toString();

                        if (Set.of("target", "build", "bin", "out", ".gradle").contains(name)) {

                            generated = true;

                            break;

                        }

                    }

                    if (generated) continue;

                    Path resolved = candidate.toRealPath();

                    if (resolved.startsWith(root)) files.add(resolved);

                }

            }

        }

        return files.stream().distinct().sorted().toList();

    }



    static Path extractJdtls() throws IOException {

        Path cache = Paths.get(System.getProperty("user.home"), ".cache", "javafix", "jdtls-1.61.0");

        Path marker = cache.resolve(".extracted");

        if (Files.exists(marker)) {

            return cache;

        }

        Files.createDirectories(cache.getParent());

        Path lockPath = cache.resolveSibling(cache.getFileName() + ".lock");

        try (FileChannel channel = FileChannel.open(lockPath,

                StandardOpenOption.CREATE, StandardOpenOption.WRITE);

             var lock = channel.lock()) {

            if (Files.exists(marker)) return cache;



            Path staging = Files.createTempDirectory(cache.getParent(), "jdtls-1.61.0-extract-");

            try {

                try (InputStream resource = Main.class.getResourceAsStream(JDTLS_RESOURCE)) {

                    if (resource == null) {

                        throw new IOException("No se encontró JDTLS empaquetado en el JAR.");

                    }

                    try (TarArchiveInputStream tar = new TarArchiveInputStream(

                        new GZIPInputStream(new BufferedInputStream(resource)))) {

                        TarArchiveEntry entry;

                        while ((entry = tar.getNextEntry()) != null) {

                            Path destination = staging.resolve(entry.getName()).normalize();

                            if (!destination.startsWith(staging)) {

                                throw new IOException("Entrada no segura en el archivo JDTLS.");

                            }

                            if (entry.isDirectory()) {

                                Files.createDirectories(destination);

                            } else {

                                Files.createDirectories(destination.getParent());

                                Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING);

                            }

                        }

                    }

                }

                Files.createFile(staging.resolve(".extracted"));

                deleteTree(cache);

                try {

                    Files.move(staging, cache, StandardCopyOption.ATOMIC_MOVE);

                } catch (IOException atomicMoveUnsupported) {

                    Files.move(staging, cache);

                }

            } finally {

                deleteTree(staging);

            }

        }

        return cache;

    }



    static void deleteTree(Path root) {

        try (var paths = Files.walk(root)) {

            paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {

                try { Files.deleteIfExists(path); } catch (IOException ignored) {}

            });

        } catch (IOException ignored) {}

    }



    private static void usage() {

        System.out.println("""

                javafix analyze --project <pom.xml|build.gradle|directorio> [--files <A.java> <B.java> ...] [--cancel-file <ruta>]

                javafix repair --project <pom.xml|build.gradle|directorio> --plan <plan.json> [--apply] [--cancel-file <ruta>]



                Analiza fuentes Java mediante JDTLS y devuelve JSON en stdout.

                Sin --files, analiza los ficheros Java encontrados en el proyecto.

                repair muestra una vista previa por defecto; solo --apply escribe cambios.

                Crea el archivo indicado con --cancel-file para solicitar cancelación cooperativa.

                """);

    }



    static final class CancellationToken {

        private final Path cancelFile;



        CancellationToken(Path cancelFile) {

            this.cancelFile = cancelFile;

        }



        static CancellationToken none() {

            return new CancellationToken(null);

        }



        void check() {

            if (Thread.currentThread().isInterrupted()

                || (cancelFile != null && Files.exists(cancelFile))) {

                throw new CancellationException("Operación cancelada.");

            }

        }

    }



    static final class Options {

        Path project;

        Path plan;

        Path cancelFile;

        CancellationToken cancellation = CancellationToken.none();

        boolean apply;

        final List<Path> files = new ArrayList<>();



        static Options parse(String[] args) {

            Options options = new Options();

            boolean readingFiles = false;

            for (int i = 1; i < args.length; i++) {

                String arg = args[i];

                if ("--project".equals(arg)) {

                    readingFiles = false;

                    options.project = Paths.get(requireValue(args, ++i, arg));

                } else if ("--plan".equals(arg)) {

                    readingFiles = false;

                    options.plan = Paths.get(requireValue(args, ++i, arg));

                } else if ("--cancel-file".equals(arg)) {

                    readingFiles = false;

                    options.cancelFile = Paths.get(requireValue(args, ++i, arg));

                } else if ("--apply".equals(arg)) {

                    readingFiles = false;

                    options.apply = true;

                } else if ("--files".equals(arg)) {

                    readingFiles = true;

                } else if (arg.startsWith("--")) {

                    throw new IllegalArgumentException("Opción desconocida: " + arg);

                } else if (readingFiles) {

                    options.files.add(Paths.get(arg));

                } else {

                    throw new IllegalArgumentException("Argumento inesperado: " + arg);

                }

            }

            if (options.project == null) {

                throw new IllegalArgumentException("Falta --project.");

            }

            options.cancellation = new CancellationToken(options.cancelFile);

            return options;

        }



        private static String requireValue(String[] args, int index, String option) {

            if (index >= args.length || args[index].startsWith("--")) {

                throw new IllegalArgumentException("Falta el valor de " + option);

            }

            return args[index];

        }

    }



    static final class LspSession implements AutoCloseable {

        private final Process process;

        private final InputStream input;

        private final OutputStream output;

        private final CancellationToken cancellation;

        private final AtomicLong ids = new AtomicLong();

        private final Map<Long, LinkedBlockingQueue<JsonNode>> responses = new HashMap<>();

        private final LinkedBlockingQueue<JsonNode> diagnostics = new LinkedBlockingQueue<>();

        private final Set<String> activeProgress = new HashSet<>();

        private final Set<String> openDocuments = new HashSet<>();



        LspSession(Process process) {

            this(process, CancellationToken.none());

        }



        LspSession(Process process, CancellationToken cancellation) {

            this.process = process;

            this.cancellation = cancellation;

            input = new BufferedInputStream(process.getInputStream());

            output = new BufferedOutputStream(process.getOutputStream());

            Thread reader = new Thread(this::readLoop, "javafix-jdtls-reader");

            reader.setDaemon(true);

            reader.start();

        }



        static LspSession start(Path home, Path configuration, Path workspace, Path root) throws Exception {

            return start(home, configuration, workspace, root, CancellationToken.none());

        }



        static LspSession start(Path home, Path configuration, Path workspace, Path root,

                                CancellationToken cancellation) throws Exception {

            cancellation.check();

            requireJava25();

            Path plugins = home.resolve("plugins");

            Path launcher;

            try (var jars = Files.list(plugins)) {

                launcher = jars.filter(p -> p.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))

                    .filter(p -> p.toString().endsWith(".jar")).findFirst()

                    .orElseThrow(() -> new IOException("No se encontró el launcher de JDTLS."));

            }

            String config = platformConfiguration();

            Path runtimeConfig = configuration.resolve("configuration");

            copyTree(home.resolve(config), runtimeConfig);

            List<String> command = List.of(

                Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString(),

                "-Declipse.application=org.eclipse.jdt.ls.core.id1",

                "-Dosgi.bundles.defaultStartLevel=4",

                "-Declipse.product=org.eclipse.jdt.ls.core.product",

                "-Dlog.level=WARNING",

                "-Xmx1G",

                "--add-modules=ALL-SYSTEM",

                "--add-opens", "java.base/java.util=ALL-UNNAMED",

                "--add-opens", "java.base/java.lang=ALL-UNNAMED",

                "-jar", launcher.toString(),

                "-configuration", runtimeConfig.toString(),

                "-data", workspace.toString());

            cancellation.check();

            Process process = new ProcessBuilder(command)

                .directory(root.toFile())

                .redirectError(ProcessBuilder.Redirect.INHERIT)

                .start();

            LspSession session = new LspSession(process, cancellation);

            try {

                ObjectNode initialize = JSON.createObjectNode();

                initialize.put("processId", ProcessHandle.current().pid());

                initialize.put("rootUri", root.toUri().toString());

                initialize.set("capabilities", clientCapabilities());

                ArrayNode folders = JSON.createArrayNode();

                folders.addObject().put("uri", root.toUri().toString())

                    .put("name", root.getFileName().toString());

                initialize.set("workspaceFolders", folders);

                session.request("initialize", initialize);

                session.notify("initialized", JSON.createObjectNode());

                ObjectNode settings = JSON.createObjectNode();

                settings.putObject("java").putObject("configuration")

                    .put("updateBuildConfiguration", "automatic");

                session.notify("workspace/didChangeConfiguration",

                    JSON.createObjectNode().set("settings", settings));

                ObjectNode importCommand = JSON.createObjectNode();

                importCommand.put("command", "java.project.import");

                importCommand.set("arguments", JSON.createArrayNode());

                session.request("workspace/executeCommand", importCommand);

                return session;

            } catch (Exception failure) {

                session.close();

                throw failure;

            }

        }



        void openDocument(String uri, String text) throws IOException {

            ObjectNode document = JSON.createObjectNode();

            document.put("uri", uri);

            document.put("languageId", "java");

            document.put("version", 1);

            document.put("text", text);

            notify("textDocument/didOpen", JSON.createObjectNode().set("textDocument", document));

            openDocuments.add(uri);

        }



        boolean awaitDiagnostics(Map<String, List<JsonNode>> target, Duration quiet, Duration timeout)

            throws InterruptedException {

            long end = System.nanoTime() + timeout.toNanos();

            long last = System.nanoTime();

            Set<String> published = new HashSet<>();

            while (System.nanoTime() < end) {

                cancellation.check();

                JsonNode message = diagnostics.poll(250, TimeUnit.MILLISECONDS);

                if (message != null) {

                    String uri = message.path("params").path("uri").asText();

                    if (target.containsKey(uri)) {

                        published.add(uri);

                        List<JsonNode> list = target.get(uri);

                        list.clear();

                        message.path("params").path("diagnostics").forEach(d -> list.add(d.deepCopy()));

                    }

                    last = System.nanoTime();

                }

                synchronized (activeProgress) {

                    if (published.containsAll(target.keySet()) && activeProgress.isEmpty()

                        && System.nanoTime() - last >= quiet.toNanos()) {

                        return true;

                    }

                }

            }

            synchronized (activeProgress) {

                return published.containsAll(target.keySet()) && activeProgress.isEmpty();

            }

        }



        List<JsonNode> codeActions(String uri, JsonNode diagnostic) throws Exception {

            ObjectNode params = JSON.createObjectNode();

            params.set("textDocument", JSON.createObjectNode().put("uri", uri));

            params.set("range", diagnostic.path("range").deepCopy());

            params.set("context", JSON.createObjectNode().set("diagnostics",

                JSON.createArrayNode().add(diagnostic.deepCopy())));

            JsonNode result = request("textDocument/codeAction", params);

            List<JsonNode> actions = new ArrayList<>();

            if (result != null && result.isArray()) {

                result.forEach(item -> {

                    JsonNode action = item.has("title") ? item : item.path("command");

                    if (action.isObject()) actions.add(action.deepCopy());

                });

            }

            return actions;

        }



        JsonNode request(String method, JsonNode params) throws Exception {

            return request(method, params, true);

        }



        private JsonNode request(String method, JsonNode params, boolean checkCancellation) throws Exception {

            long id = ids.incrementAndGet();

            LinkedBlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();

            synchronized (responses) { responses.put(id, queue); }

            ObjectNode request = JSON.createObjectNode();

            request.put("jsonrpc", "2.0");

            request.put("id", id);

            request.put("method", method);

            request.set("params", params);

            write(request);

            long deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();

            JsonNode response = null;

            try {

                while (response == null && System.nanoTime() < deadline) {

                    if (checkCancellation) cancellation.check();

                    response = queue.poll(250, TimeUnit.MILLISECONDS);

                    if (response == null && !process.isAlive()) {

                        throw new IOException("JDTLS terminó antes de responder a " + method

                            + ". Comprueba el runtime Java y los logs del servidor.");

                    }

                }

            } finally {

                synchronized (responses) { responses.remove(id); }

            }

            if (response == null) {

                throw new IOException("Timeout esperando respuesta de JDTLS a " + method);

            }

            if (response.has("error")) {

                throw new IOException("JDTLS rechazó " + method + ": "

                    + response.path("error").toString());

            }

            return response.path("result");

        }



        private void notify(String method, JsonNode params) throws IOException {

            ObjectNode notification = JSON.createObjectNode();

            notification.put("jsonrpc", "2.0");

            notification.put("method", method);

            notification.set("params", params);

            write(notification);

        }



        private void write(JsonNode message) throws IOException {

            byte[] body = JSON.writeValueAsBytes(message);

            synchronized (output) {

                output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));

                output.write(body);

                output.flush();

            }

        }



        private void readLoop() {

            try {

                while (process.isAlive()) {

                    int contentLength = -1;

                    String line;

                    while ((line = readHeaderLine(input)) != null && !line.isEmpty()) {

                        if (line.toLowerCase().startsWith("content-length:")) {

                            contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());

                        }

                    }

                    if (contentLength < 0) return;

                    byte[] body = input.readNBytes(contentLength);

                    JsonNode message = JSON.readTree(body);

                    if (message.has("id") && !message.has("method")) {

                        long id = message.path("id").asLong();

                        LinkedBlockingQueue<JsonNode> queue;

                        synchronized (responses) { queue = responses.get(id); }

                        if (queue != null) queue.offer(message);

                    } else if (message.has("id") && message.has("method")) {

                        respondToServerRequest(message);

                    } else if ("textDocument/publishDiagnostics".equals(message.path("method").asText())) {

                        diagnostics.offer(message);

                    } else if ("$/progress".equals(message.path("method").asText())) {

                        JsonNode params = message.path("params");

                        String token = params.path("token").asText();

                        String kind = params.path("value").path("kind").asText();

                        synchronized (activeProgress) {

                            if ("begin".equals(kind)) activeProgress.add(token);

                            else if ("end".equals(kind)) activeProgress.remove(token);

                        }

                    } else if ("window/logMessage".equals(message.path("method").asText())) {

                        // Server logs stay off stdout, which is reserved for the JSON result.

                    }

                }

            } catch (Exception ignored) {

                // Pending requests time out with a user-facing message.

            }

        }



        private void respondToServerRequest(JsonNode request) throws IOException {

            String method = request.path("method").asText();

            JsonNode result;

            switch (method) {

                case "workspace/configuration" -> {

                    ArrayNode items = JSON.createArrayNode();

                    request.path("params").path("items").forEach(ignored -> items.addNull());

                    result = items;

                }

                case "workspace/applyEdit" -> result = JSON.createObjectNode().put("applied", false);

                case "window/workDoneProgress/create", "client/registerCapability",

                     "client/unregisterCapability", "window/showMessageRequest" -> result = JSON.nullNode();

                default -> {

                    ObjectNode error = JSON.createObjectNode();

                    error.put("jsonrpc", "2.0");

                    error.set("id", request.get("id"));

                    error.putObject("error").put("code", -32601)

                        .put("message", "Unsupported client request: " + method);

                    write(error);

                    return;

                }

            }

            ObjectNode response = JSON.createObjectNode();

            response.put("jsonrpc", "2.0");

            response.set("id", request.get("id"));

            response.set("result", result);

            write(response);

        }



        private static String readHeaderLine(InputStream input) throws IOException {

            ByteArrayOutputStream line = new ByteArrayOutputStream();

            int previous = -1;

            int current;

            while ((current = input.read()) != -1) {

                if (previous == '\r' && current == '\n') {

                    byte[] bytes = line.toByteArray();

                    return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);

                }

                line.write(current);

                previous = current;

            }

            return null;

        }



        private static ObjectNode clientCapabilities() {

            ObjectNode capabilities = JSON.createObjectNode();

            capabilities.putObject("window").put("workDoneProgress", true);

            ObjectNode codeAction = capabilities.putObject("textDocument").putObject("codeAction");

            ArrayNode kinds = codeAction.putObject("codeActionLiteralSupport")

                .putObject("codeActionKind").putArray("valueSet");

            kinds.add("quickfix").add("refactor").add("refactor.extract").add("refactor.inline").add("source");

            ObjectNode resolveSupport = JSON.createObjectNode();

            resolveSupport.putArray("properties").add("edit").add("command");

            codeAction.set("resolveSupport", resolveSupport);

            capabilities.putObject("workspace").put("applyEdit", false);

            return capabilities;

        }



        private static void requireJava25() {

            if (Runtime.version().feature() < 25) {

                throw new IllegalStateException("JDTLS 1.61.0 requiere Java 25 para ejecutarse. "

                    + "Runtime actual: " + Runtime.version().feature());

            }

        }



        private static boolean isWindows() {

            return System.getProperty("os.name").toLowerCase().contains("win");

        }



        private static String platformConfiguration() {

            String os = System.getProperty("os.name").toLowerCase();

            String architecture = System.getProperty("os.arch").toLowerCase();

            boolean arm = architecture.contains("aarch64") || architecture.contains("arm64");

            if (os.contains("mac")) return arm ? "config_mac_arm" : "config_mac";

            if (os.contains("win")) return "config_win";

            if (os.contains("linux")) return arm ? "config_linux_arm" : "config_linux";

            throw new IllegalStateException("Sistema operativo no soportado por el JDTLS incluido: " + os);

        }



        private static void copyTree(Path source, Path destination) throws IOException {

            try (var paths = Files.walk(source)) {

                for (Path path : paths.toList()) {

                    Path relative = source.relativize(path);

                    Path target = destination.resolve(relative);

                    if (Files.isDirectory(path)) {

                        Files.createDirectories(target);

                    } else {

                        Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    }

                }

            }

        }



        @Override

        public void close() {

            try {

                for (String uri : openDocuments) {

                    notify("textDocument/didClose",

                        JSON.createObjectNode().set("textDocument", JSON.createObjectNode().put("uri", uri)));

                }

                request("shutdown", JSON.createObjectNode(), false);

                notify("exit", JSON.createObjectNode());

                if (!process.waitFor(5, TimeUnit.SECONDS)) {

                    process.destroy();

                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();

                }

            } catch (Exception ignored) {

                process.destroyForcibly();

            }

        }

    }

}
