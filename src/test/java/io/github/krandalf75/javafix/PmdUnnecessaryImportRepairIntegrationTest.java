package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PmdUnnecessaryImportRepairIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void linksTheSameJdtlsRepairToPmdAndRemovesOnlyTheUnusedImport() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25, "Requires JDTLS Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("unused-import-project"));
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>unused-import</artifactId><version>1</version>
              <properties><maven.compiler.release>25</maven.compiler.release></properties>
            </project>
            """);
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Example.java");
        String original = """
            package sample;
            import java.util.Set;
            import java.util.List;
            class Example { List<String> values; }
            """;
        Files.writeString(source, original);
        ObjectNode analysis = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        JsonNode pmd = findRule(analysis, "PMD/UnnecessaryImport");
        JsonNode java = findUnusedImport(analysis);
        JsonNode javaAction = findAction(java);
        JsonNode pmdAction = findAction(pmd);
        String id = javaAction.path("repairId").asText();
        assertFalse(id.isBlank(), analysis.toPrettyString());
        assertEquals(id, pmdAction.path("repairId").asText());

        Path plan = project.resolve("plan.json");
        JSON.writeValue(plan.toFile(), JSON.createObjectNode().put("schemaVersion", "1")
            .set("repairs", JSON.createArrayNode().add(id)));
        ObjectNode preview = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString()}), ignored -> analysis);
        assertEquals("preview", preview.path("status").asText(), preview.toPrettyString());
        String proposed = preview.path("changes").get(0).path("content").asText();
        assertFalse(proposed.contains("import java.util.Set;"), proposed);
        assertTrue(proposed.contains("import java.util.List;"), proposed);
        assertEquals(original, Files.readString(source));

        AtomicInteger analyses = new AtomicInteger();
        ObjectNode applied = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString(), "--apply"}), ignored ->
            analyses.getAndIncrement() == 0 ? analysis : Main.analyze(ignored));
        assertEquals("applied", applied.path("status").asText(), applied.toPrettyString());
        assertEquals(proposed, Files.readString(source));
        assertFalse(hasRule(applied.path("postAnalysis"), "PMD/UnnecessaryImport"));
        assertEquals("completed", applied.path("postAnalysis").path("status").asText());
    }

    @Test
    void keepsUnsupportedUnusedImportFindingsDiagnosticOnly() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25, "Requires JDTLS Java 25");
        assertDiagnosticOnly("static-import", """
            package sample;
            import static java.util.Collections.emptyList;
            class Example {}
            """, false);
        assertDiagnosticOnly("wildcard-import", """
            package sample;
            import java.util.*;
            class Example {}
            """, false);
        assertDiagnosticOnly("commented-import", """
            package sample;
            import java.util.Set; /* preserve this import comment */
            import java.util.List;
            class Example { List<String> values; }
            """, false);
        assertDiagnosticOnly("disabled-unused-import-warning", """
            package sample;
            import java.util.Set;
            import java.util.List;
            class Example { List<String> values; }
            """, true);
    }

    @Test
    void associatesSeparateEditsWhenSeveralImportsAreUnused() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25, "Requires JDTLS Java 25");
        String sourceText = """
            package sample;
            import java.util.Set;
            import java.util.Map;
            class Example {}
            """;
        Path project = createProject("several-unused-imports", sourceText, false);
        ObjectNode analysis = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        ArrayList<JsonNode> findings = new ArrayList<>();
        for (JsonNode file : analysis.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if ("PMD/UnnecessaryImport".equals(diagnostic.path("ruleId").asText())) {
                    findings.add(diagnostic);
                }
            }
        }
        assertEquals(2, findings.size(), analysis.toPrettyString());
        Set<String> repairIds = new HashSet<>();
        for (JsonNode finding : findings) {
            JsonNode action = findSingleImportAction(finding);
            assertEquals("Remove unused import", action.path("title").asText());
            assertTrue(repairIds.add(action.path("repairId").asText()), "Each import edit needs its own ID");
            JsonNode changes = action.path("workspaceEdit").path("changes");
            assertEquals(1, changes.size(), action.toPrettyString());
            JsonNode edits = changes.fields().next().getValue();
            assertEquals(1, edits.size(), action.toPrettyString());
            JsonNode edit = edits.get(0);
            String after = applyEdit(sourceText, edit);
            String associatedImport = finding.path("message").asText().contains("java.util.Set")
                ? "import java.util.Set;" : "import java.util.Map;";
            String otherImport = associatedImport.contains("Set")
                ? "import java.util.Map;" : "import java.util.Set;";
            assertFalse(after.contains(associatedImport), after);
            assertTrue(after.contains(otherImport), after);
        }
    }

    private void assertDiagnosticOnly(String name, String sourceText, boolean disableUnusedImportWarning)
        throws Exception {
        Path project = createProject(name, sourceText, disableUnusedImportWarning);
        ObjectNode analysis = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        assertEquals("completed", analysis.path("status").asText(), analysis.toPrettyString());
        JsonNode finding = findRule(analysis, "PMD/UnnecessaryImport");
        assertFalse(finding.path("resolution").path("automaticFixAvailable").asBoolean(),
            finding.toPrettyString());
        assertTrue(finding.path("resolution").path("jdtlsActions").isEmpty(), finding.toPrettyString());
    }

    private Path createProject(String name, String sourceText, boolean disableUnusedImportWarning)
        throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve(name));
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>unused-import</artifactId><version>1</version>
              <properties><maven.compiler.release>25</maven.compiler.release></properties>
            </project>
            """);
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Example.java");
        Files.writeString(source, sourceText);
        if (disableUnusedImportWarning) {
            Path settings = Files.createDirectories(project.resolve(".settings"));
            Files.writeString(settings.resolve("org.eclipse.jdt.core.prefs"), """
                eclipse.preferences.version=1
                org.eclipse.jdt.core.compiler.problem.unusedImport=ignore
                """);
        }
        return project;
    }

    private static String applyEdit(String source, JsonNode edit) {
        JsonNode range = edit.path("range");
        int start = offsetAt(source, range.path("start"));
        int end = offsetAt(source, range.path("end"));
        return source.substring(0, start) + edit.path("newText").asText() + source.substring(end);
    }

    private static int offsetAt(String source, JsonNode position) {
        int targetLine = position.path("line").asInt();
        int character = position.path("character").asInt();
        int line = 0;
        int offset = 0;
        while (line < targetLine) {
            int newline = source.indexOf('\n', offset);
            if (newline < 0) throw new AssertionError("LSP line outside source: " + position);
            offset = newline + 1;
            line++;
        }
        return offset + character;
    }

    private static JsonNode findRule(JsonNode analysis, String id) {
        for (JsonNode file : analysis.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (id.equals(diagnostic.path("ruleId").asText())) return diagnostic;
            }
        }
        throw new AssertionError("Missing " + id + ": " + analysis.toPrettyString());
    }

    private static JsonNode findUnusedImport(JsonNode analysis) {
        for (JsonNode file : analysis.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if ("Java".equals(diagnostic.path("source").asText())
                    && "268435844".equals(diagnostic.path("code").asText())) return diagnostic;
            }
        }
        throw new AssertionError("Missing JDTLS UnusedImport: " + analysis.toPrettyString());
    }

    private static JsonNode findAction(JsonNode diagnostic) {
        for (JsonNode action : diagnostic.path("resolution").path("jdtlsActions")) {
            if ("Remove all unused imports".equals(action.path("title").asText())
                && action.path("fixesDiagnostic").asBoolean() && !action.path("suppressesWarning").asBoolean()) {
                return action;
            }
        }
        throw new AssertionError("Missing verified unused-import action: " + diagnostic.toPrettyString());
    }

    private static JsonNode findSingleImportAction(JsonNode diagnostic) {
        for (JsonNode action : diagnostic.path("resolution").path("jdtlsActions")) {
            if ("Remove unused import".equals(action.path("title").asText())
                && action.path("fixesDiagnostic").asBoolean()
                && !action.path("suppressesWarning").asBoolean()
                && action.path("repairable").asBoolean()) return action;
        }
        throw new AssertionError("Missing single-import action: " + diagnostic.toPrettyString());
    }

    private static boolean hasRule(JsonNode analysis, String id) {
        for (JsonNode file : analysis.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (id.equals(diagnostic.path("ruleId").asText())) return true;
            }
        }
        return false;
    }
}
