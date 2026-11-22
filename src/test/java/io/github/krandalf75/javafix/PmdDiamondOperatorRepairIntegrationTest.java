package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PmdDiamondOperatorRepairIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void previewsAndAppliesDiamondAtConfiguredReleaseAndPreservesRuntimeOutput() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25, "Requires the bundled JDTLS Java 25 runtime");
        Path project = Files.createDirectory(temporaryDirectory.resolve("diamond-repair"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("DiamondExample.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>diamond-repair</artifactId><version>1</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
            </project>
            """);
        String original = """
            package sample;
            import java.util.ArrayList;
            import java.util.List;
            public class DiamondExample {
                static List<String> values() {
                    ArrayList<String> items = new ArrayList<String>();
                    items.add("same");
                    return items;
                }
                public static void main(String[] args) { System.out.print(values().get(0)); }
            }
            """;
        Files.writeString(source, original);
        String outputBefore = compileAndRun(original, "before");

        ObjectNode analysis = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        assertEquals("completed", analysis.path("status").asText(), analysis.toPrettyString());
        JsonNode finding = findRule(analysis, "PMD/UseDiamondOperator");
        JsonNode action = providerAction(finding);
        String repairId = action.path("repairId").asText();
        assertFalse(repairId.isBlank(), finding.toPrettyString());

        Path plan = project.resolve("plan.json");
        JSON.writeValue(plan.toFile(), JSON.createObjectNode().put("schemaVersion", "1")
            .set("repairs", JSON.valueToTree(List.of(repairId))));
        ObjectNode preview = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString()}), ignored -> analysis);
        assertEquals("preview", preview.path("status").asText(), preview.toPrettyString());
        assertEquals(original, Files.readString(source), "Preview must not modify source bytes");
        String proposed = preview.path("changes").get(0).path("content").asText();
        assertTrue(proposed.contains("new ArrayList<>()"), proposed);

        Path previewFile = project.resolve("reviewed-preview.json");
        JSON.writeValue(previewFile.toFile(), preview);
        AtomicInteger analyses = new AtomicInteger();
        ObjectNode applied = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString(), "--apply",
            "--expect-preview", previewFile.toString()}), ignored ->
            analyses.getAndIncrement() == 0 ? analysis : Main.analyze(ignored));
        assertEquals("applied", applied.path("status").asText(), applied.toPrettyString());
        assertEquals(proposed, Files.readString(source));
        assertEquals(outputBefore, compileAndRun(proposed, "after"));
        assertEquals("completed", applied.path("postAnalysis").path("status").asText());
        assertFalse(hasRule(applied.path("postAnalysis"), "PMD/UseDiamondOperator"));
        assertEquals(0, applied.path("postAnalysis").path("summary").path("errors").asInt(),
            "There should be no post-analysis compiler errors.");
    }

    private String compileAndRun(String source, String label) throws Exception {
        Path root = temporaryDirectory.resolve(label);
        Path sourceFile = root.resolve("src/sample/DiamondExample.java");
        Path classes = root.resolve("classes");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classes);
        Files.writeString(sourceFile, source);
        int compile = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "8", "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, compile, "Fixture must compile at the configured release");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classes.toString(), "sample.DiamondExample")
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
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
