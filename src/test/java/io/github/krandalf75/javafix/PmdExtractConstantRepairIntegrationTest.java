package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmdExtractConstantRepairIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void parameterizedPlanPreviewsAndAppliesNamedExtractConstant() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("extract-constant"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("RepeatedText.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>extract-constant</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String original = """
            package sample;
            public class RepeatedText {
                public String first() { return "repeat-me"; }
                public String second() { return "repeat-me"; }
                public String third() { return "repeat-me"; }
                public String fourth() { return "repeat-me"; }
            }
            """;
        Files.writeString(source, original);

        ObjectNode report = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        JsonNode finding = findRule(report, "PMD/AvoidDuplicateLiterals");
        assertTrue(finding.path("resolution").path("automaticFixAvailable").asBoolean(),
            finding.toPrettyString());
        JsonNode action = finding.path("resolution").path("providerActions").get(0);
        assertEquals("Extract string constant", action.path("title").asText(), report.toPrettyString());
        assertEquals("constantName", action.path("requiredParameters").get(0).asText());
        assertTrue(action.path("repairId").isTextual());
        assertFalse(action.hasNonNull("workspaceEdit"),
            "Parameterized refactoring must wait for the user-supplied name");

        Path plan = temporaryDirectory.resolve("extract-plan.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(plan.toFile(), JSON.createObjectNode()
            .put("schemaVersion", "1")
            .set("repairs", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("id", action.path("repairId").asText())
                .set("parameters", JSON.createObjectNode().put("constantName", "REPEATED_TEXT")))));
        Main.Options previewOptions = Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString()});
        ObjectNode preview = Main.repair(previewOptions, ignored -> report);
        String previewContent = preview.path("changes").get(0).path("content").asText();
        assertEquals("preview", preview.path("status").asText());
        assertTrue(previewContent.contains("private static final String REPEATED_TEXT = \"repeat-me\";"),
            previewContent);
        assertEquals(4, occurrences(previewContent, "return REPEATED_TEXT;"));
        assertEquals(original, Files.readString(source), "Preview must leave the source unchanged");

        Main.Options applyOptions = Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString(), "--apply"});
        AtomicInteger analyses = new AtomicInteger();
        ObjectNode applied = Main.repair(applyOptions, ignored ->
            analyses.getAndIncrement() == 0 ? report : Main.analyze(ignored));
        String updated = Files.readString(source);
        assertEquals("applied", applied.path("status").asText(), applied.toPrettyString());
        assertEquals(previewContent, updated);
        assertFalse(hasRule(applied.path("postAnalysis"), "PMD/AvoidDuplicateLiterals"),
            applied.path("postAnalysis").toPrettyString());
    }

    @Test
    void validatesRequiredParameterAndRejectsInvalidNames() throws Exception {
        ObjectNode plan = JSON.createObjectNode().put("schemaVersion", "1");
        plan.putArray("repairs").addObject().put("id", "repair-id")
            .putObject("parameters").put("constantName", "class");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> Main.validateRepairPlan(plan));

        Path project = Files.createDirectory(temporaryDirectory.resolve("invalid-name-project"));
        Path source = Files.writeString(project.resolve("Source.java"),
            "class Source { String value() { return \"x\"; } }");
        String uri = source.toUri().toString();
        ObjectNode action = JSON.createObjectNode().put("repairId", "repair-id")
            .put("targetUri", uri).put("literalOffset", 42)
            .put("sourceHash", sha256(Files.readAllBytes(source)));
        action.putArray("requiredParameters").add("constantName");
        ObjectNode report = JSON.createObjectNode().put("schemaVersion", "1");
        ObjectNode file = report.putArray("files").addObject();
        ObjectNode diagnostic = file.putArray("diagnostics").addObject();
        diagnostic.putObject("resolution").putArray("providerActions").add(action);
        Path missingParameterPlan = temporaryDirectory.resolve("missing-parameter-plan.json");
        JSON.writeValue(missingParameterPlan.toFile(), JSON.createObjectNode()
            .put("schemaVersion", "1").set("repairs", JSON.createArrayNode().add("repair-id")));
        Main.Options missingParameterOptions = Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", missingParameterPlan.toString()});
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> Main.repair(missingParameterOptions, ignored -> report));

        Path invalidPlan = temporaryDirectory.resolve("invalid-name-plan.json");
        JSON.writeValue(invalidPlan.toFile(), plan);
        Main.Options options = Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", invalidPlan.toString()});
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> Main.repair(options, ignored -> report));
    }

    @Test
    void rejectsNameCollisionInPreviewAndApplyWithoutWritingSource() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("extract-constant-collision"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("RepeatedText.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>extract-constant-collision</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String original = """
            package sample;
            class RepeatedText {
                private static final String REPEATED_TEXT = "existing";
                String first() { return "repeat-me"; }
                String second() { return "repeat-me"; }
                String third() { return "repeat-me"; }
                String fourth() { return "repeat-me"; }
            }
            """;
        Files.writeString(source, original);

        ObjectNode report = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        JsonNode action = findRule(report, "PMD/AvoidDuplicateLiterals")
            .path("resolution").path("providerActions").get(0);
        Path plan = temporaryDirectory.resolve("extract-collision-plan.json");
        JSON.writeValue(plan.toFile(), JSON.createObjectNode().put("schemaVersion", "1")
            .set("repairs", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("id", action.path("repairId").asText())
                .set("parameters", JSON.createObjectNode().put("constantName", "REPEATED_TEXT")))));

        for (boolean apply : new boolean[]{false, true}) {
            String[] args = apply
                ? new String[]{"repair", "--project", project.toString(), "--plan", plan.toString(), "--apply"}
                : new String[]{"repair", "--project", project.toString(), "--plan", plan.toString()};
            Main.Options options = Main.Options.parse(args);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Main.repair(options, ignored -> report));
            assertEquals(original, Files.readString(source),
                "Rejected Extract Constant must not write source in preview or apply mode.");
        }
    }

    private static JsonNode findRule(JsonNode report, String id) {
        for (JsonNode file : report.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (id.equals(diagnostic.path("ruleId").asText())) return diagnostic;
            }
        }
        throw new AssertionError("Missing " + id + ": " + report.toPrettyString());
    }

    private static boolean hasRule(JsonNode report, String id) {
        for (JsonNode file : report.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (id.equals(diagnostic.path("ruleId").asText())) return true;
            }
        }
        return false;
    }

    private static int occurrences(String text, String token) {
        return (text.length() - text.replace(token, "").length()) / token.length();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
