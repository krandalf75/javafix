package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PmdVerifiedRepairBatchIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void previewsAndAppliesNonOverlappingVerifiedRepairsAgainstTheReviewedPreview() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25, "Requires the bundled JDTLS Java 25 runtime");
        Path project = Files.createDirectory(temporaryDirectory.resolve("verified-repair-batch"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Example.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>verified-repair-batch</artifactId><version>1</version>
              <properties><maven.compiler.release>25</maven.compiler.release></properties>
            </project>
            """);
        String original = """
            package sample;
            import java.nio.charset.Charset;
            class Example {
                Charset charset() { return Charset.forName("UTF-8"); }
                int value() {
                    int orphanedValue = 42;
                    int store;
                    store = 1;
                    store = 2;
                    return store;
                }
            }
            """;
        Files.writeString(source, original);
        ObjectNode analysis = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        assertEquals("completed", analysis.path("status").asText(), analysis.toPrettyString());
        JsonNode charset = findRule(analysis, "PMD/UseStandardCharsets");
        JsonNode unused = findRule(analysis, "PMD/UnusedLocalVariable");
        JsonNode store = findRule(analysis, "PMD/UnusedAssignment");
        List<String> ids = List.of(
            providerAction(charset).path("repairId").asText(),
            providerAction(unused).path("repairId").asText(),
            providerAction(store).path("repairId").asText());
        assertTrue(ids.stream().allMatch(id -> !id.isBlank()), analysis.toPrettyString());
        assertEquals(3, ids.stream().distinct().count(), "Repair IDs must be unique across distinct edits");

        Path plan = project.resolve("plan.json");
        JSON.writeValue(plan.toFile(), JSON.createObjectNode().put("schemaVersion", "1")
            .set("repairs", JSON.valueToTree(ids)));
        ObjectNode preview = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString()}), ignored -> analysis);
        assertEquals("preview", preview.path("status").asText(), preview.toPrettyString());
        assertEquals(original, Files.readString(source), "Preview must not change source bytes");
        assertEquals(1, preview.path("changes").size());
        String proposed = preview.path("changes").get(0).path("content").asText();
        assertTrue(proposed.contains("StandardCharsets.UTF_8"), proposed);
        assertFalse(proposed.contains("int orphanedValue = 42;"), proposed);
        assertFalse(proposed.contains("store = 1;"), proposed);
        assertTrue(proposed.contains("store = 2;"), proposed);

        Path previewFile = project.resolve("reviewed-preview.json");
        JSON.writeValue(previewFile.toFile(), preview);
        ObjectNode mismatchedPreview = preview.deepCopy();
        ((ObjectNode) mismatchedPreview.path("changes").get(0)).put("afterHash", "0".repeat(64));
        Path mismatchFile = project.resolve("mismatched-preview.json");
        JSON.writeValue(mismatchFile.toFile(), mismatchedPreview);
        assertThrows(IllegalArgumentException.class, () -> Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString(), "--apply",
            "--expect-preview", mismatchFile.toString()}), ignored -> analysis));
        assertEquals(original, Files.readString(source), "A preview mismatch must be rejected before writing");

        AtomicInteger analyses = new AtomicInteger();
        ObjectNode applied = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString(), "--apply",
            "--expect-preview", previewFile.toString()}), ignored ->
            analyses.getAndIncrement() == 0 ? analysis : Main.analyze(ignored));
        assertEquals("applied", applied.path("status").asText(), applied.toPrettyString());
        assertEquals(proposed, Files.readString(source));
        assertEquals("completed", applied.path("postAnalysis").path("status").asText());
        assertFalse(hasRule(applied.path("postAnalysis"), "PMD/UseStandardCharsets"));
        assertFalse(hasRule(applied.path("postAnalysis"), "PMD/UnusedLocalVariable"));
        assertFalse(hasRule(applied.path("postAnalysis"), "PMD/UnusedAssignment"));
        assertEquals(0, applied.path("postAnalysis").path("summary").path("errors").asInt(),
            "There should be no post-analysis compiler errors.");
    }

    @Test
    void keepsStandardCharsetFindingDiagnosticOnlyBelowJava7() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25, "Requires the bundled JDTLS Java 25 runtime");
        Path project = Files.createDirectory(temporaryDirectory.resolve("charset-java6"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("LegacyCharset.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>charset-java6</artifactId><version>1</version>
              <properties><maven.compiler.release>6</maven.compiler.release></properties>
            </project>
            """);
        Files.writeString(source, """
            package sample;
            import java.nio.charset.Charset;
            class LegacyCharset {
                Charset charset() { return Charset.forName("UTF-8"); }
            }
            """);

        ObjectNode analysis = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        assertEquals("completed", analysis.path("status").asText(), analysis.toPrettyString());
        JsonNode finding = findRule(analysis, "PMD/UseStandardCharsets");
        assertFalse(finding.path("resolution").path("automaticFixAvailable").asBoolean(),
            "Java 6 cannot compile StandardCharsets, so the finding must remain diagnostic-only.");
        for (JsonNode action : finding.path("resolution").path("providerActions")) {
            assertFalse(action.path("repairable").asBoolean(), action.toPrettyString());
            assertFalse(action.hasNonNull("repairId"), action.toPrettyString());
        }
    }

    @Test
    void keepsUnsupportedUnusedLocalFindingsDiagnosticOnly() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25, "Requires the bundled JDTLS Java 25 runtime");
        Path project = Files.createDirectory(temporaryDirectory.resolve("unused-local-negatives"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Example.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>unused-local-negatives</artifactId><version>1</version>
              <properties><maven.compiler.release>25</maven.compiler.release></properties>
            </project>
            """);
        String original = """
            package sample;
            class Example {
                int next() { return 1; }
                void run() {
                    int sideEffect = next();
                    int uninitialized;
                    int first = 1, second = 2;
                    String text = "unused";
                    int commented = 3; // preserve this explanation
                }
            }
            """;
        Files.writeString(source, original);
        ObjectNode analysis = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        assertEquals("completed", analysis.path("status").asText(), analysis.toPrettyString());
        Set<String> expectedNames = Set.of(
            "sideEffect", "uninitialized", "first", "second", "text", "commented");
        Set<String> observedNames = new HashSet<>();
        for (JsonNode file : analysis.path("files")) {
            for (JsonNode finding : file.path("diagnostics")) {
                if (!"PMD/UnusedLocalVariable".equals(finding.path("ruleId").asText())) continue;
                String message = finding.path("message").asText();
                int nameStart = message.indexOf('\'') + 1;
                int nameEnd = message.indexOf('\'', nameStart);
                assertTrue(nameStart > 0 && nameEnd > nameStart, message);
                String name = message.substring(nameStart, nameEnd);
                observedNames.add(name);
                assertFalse(finding.path("resolution").path("automaticFixAvailable").asBoolean(), message);
                assertTrue(finding.path("resolution").path("providerActions").isEmpty(), message);
            }
        }
        assertEquals(expectedNames, observedNames, analysis.toPrettyString());
        assertEquals(original, Files.readString(source), "Analysis must not change the source.");
    }

    private static JsonNode findRule(JsonNode analysis, String ruleId) {
        for (JsonNode file : analysis.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (ruleId.equals(diagnostic.path("ruleId").asText())) return diagnostic;
            }
        }
        throw new AssertionError("Missing " + ruleId + ": " + analysis.toPrettyString());
    }

    private static JsonNode providerAction(JsonNode finding) {
        for (JsonNode action : finding.path("resolution").path("providerActions")) {
            if (action.path("repairable").asBoolean() && action.hasNonNull("workspaceEdit")) return action;
        }
        throw new AssertionError("Missing JavaFix provider action: " + finding.toPrettyString());
    }

    private static boolean hasRule(JsonNode analysis, String ruleId) {
        for (JsonNode file : analysis.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (ruleId.equals(diagnostic.path("ruleId").asText())) return true;
            }
        }
        return false;
    }
}
