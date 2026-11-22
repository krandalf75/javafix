package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmdInlineLocalRepairIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void previewsAndAppliesNarrowPmdInlineLocalRepair() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("inline-local-project"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Inline.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>inline-local</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String original = """
            package sample;
            class Inline {
                Object value() {
                    long result = 1;
                    return result;
                }
            }
            """;
        Files.writeString(source, original);

        ObjectNode analysis = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        JsonNode finding = findRule(analysis, "PMD/UnnecessaryLocalBeforeReturn");
        JsonNode variableFinding = findRule(analysis, "PMD/VariableCanBeInlined");
        JsonNode action = finding.path("resolution").path("providerActions").get(0);
        JsonNode associatedAction = variableFinding.path("resolution").path("providerActions").get(0);
        assertTrue(action.hasNonNull("repairId"), finding.toPrettyString());
        assertEquals(action.path("repairId").asText(), associatedAction.path("repairId").asText(),
            "The two PMD findings for the same local must share one repair ID.");
        assertEquals("JavaFix", action.path("source").asText());
        String repairId = action.path("repairId").asText();
        Path plan = project.resolve("plan.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(plan.toFile(),
            JSON.createObjectNode().put("schemaVersion", "1")
                .set("repairs", JSON.createArrayNode().add(repairId)));

        Main.Options previewOptions = Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString()});
        ObjectNode preview = Main.repair(previewOptions, ignored -> analysis);
        assertEquals("preview", preview.path("status").asText(), preview.toPrettyString());
        assertTrue(preview.path("changes").get(0).path("content").asText().contains("return (long) 1;"));
        assertEquals(original, Files.readString(source), "Preview must leave the source unchanged");

        Main.Options applyOptions = Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString(), "--apply"});
        AtomicInteger analyses = new AtomicInteger();
        ObjectNode applied = Main.repair(applyOptions, ignored ->
            analyses.getAndIncrement() == 0 ? analysis : Main.analyze(ignored));
        String updated = Files.readString(source);
        assertEquals("applied", applied.path("status").asText(), applied.toPrettyString());
        assertTrue(updated.contains("return (long) 1;"), updated);
        assertFalse(updated.contains("long result"), updated);
        assertFalse(hasRule(applied.path("postAnalysis"), "PMD/UnnecessaryLocalBeforeReturn"),
            applied.path("postAnalysis").toPrettyString());
    }

    @Test
    void doesNotOfferInlineLocalRepairWhenAffectedRangeContainsAComment() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("inline-local-comment"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("InlineComment.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>inline-local-comment</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String original = """
            package sample;
            class InlineComment {
                Object value() {
                    long result = 1; // keep explanation
                    return result;
                }
            }
            """;
        Files.writeString(source, original);

        JsonNode report = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        JsonNode finding = findRule(report, "PMD/UnnecessaryLocalBeforeReturn");
        assertFalse(finding.path("resolution").path("automaticFixAvailable").asBoolean(),
            finding.toPrettyString());
        assertTrue(finding.path("resolution").path("providerActions").isEmpty(),
            finding.toPrettyString());
        assertEquals(original, Files.readString(source));
    }

    private static JsonNode findRule(JsonNode report, String ruleId) {
        for (JsonNode file : report.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (ruleId.equals(diagnostic.path("ruleId").asText())) return diagnostic;
            }
        }
        throw new AssertionError("Missing rule " + ruleId + ": " + report.toPrettyString());
    }

    private static boolean hasRule(JsonNode report, String ruleId) {
        for (JsonNode file : report.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (ruleId.equals(diagnostic.path("ruleId").asText())) return true;
            }
        }
        return false;
    }
}
