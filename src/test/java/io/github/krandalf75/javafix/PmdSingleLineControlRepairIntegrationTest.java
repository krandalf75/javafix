package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PmdSingleLineControlRepairIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void previewsAndAppliesReportedControlStatementBracesAndPreservesRuntimeOutput() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25, "Requires the bundled JDTLS Java 25 runtime");
        String source = """
            package sample;
            public class Example {
                static int calls;
                static void run(boolean ready) { if (ready) execute(); }
                static void execute() { calls++; }
                public static void main(String[] args) { run(true); System.out.print(calls); }
            }
            """;
        verifyRepair("PMD/ControlStatementBraces", "braces", source,
            "if (ready) { execute(); }", "1");
    }

    @Test
    void previewsAndAppliesReportedCollapsibleIfAndPreservesShortCircuitOutput() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25, "Requires the bundled JDTLS Java 25 runtime");
        String source = """
            package sample;
            public class Example {
                static int calls;
                static boolean outer;
                static boolean inner;
                static boolean outerCheck() { calls++; return outer; }
                static boolean innerCheck() { calls++; return inner; }
                static void work() { calls += 10; }
                static void run() { if (outerCheck()) { if (innerCheck()) { work(); } } }
                public static void main(String[] args) {
                    outer = true; inner = false; run();
                    System.out.print(calls + ":");
                    calls = 0; outer = false; inner = true; run();
                    System.out.print(calls);
                }
            }
            """;
        verifyRepair("PMD/CollapsibleIfStatements", "collapsible-if", source,
            "if ((outerCheck()) && (innerCheck()))", "2:1");
    }

    private void verifyRepair(String ruleId, String label, String original, String expectedSource,
                              String expectedOutput) throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve(label));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Example.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>single-line-control</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        Files.createDirectories(project.resolve(".settings"));
        Files.writeString(project.resolve(".settings/org.eclipse.jdt.core.prefs"), """
            eclipse.preferences.version=1
            org.eclipse.jdt.core.compiler.problem.deadCode=ignore
            org.eclipse.jdt.core.compiler.problem.unusedLocal=ignore
            org.eclipse.jdt.core.compiler.problem.unusedImport=ignore
            """);
        Files.writeString(source, original);

        String beforeOutput = compileAndRun(original, label + "-before");
        ObjectNode analysis = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        assertEquals("completed", analysis.path("status").asText(), analysis.toPrettyString());
        JsonNode finding = findRule(analysis, ruleId);
        JsonNode action = providerAction(finding);
        String repairId = action.path("repairId").asText();
        assertFalse(repairId.isBlank(), finding.toPrettyString());

        Path plan = project.resolve("plan.json");
        ObjectNode planJson = JSON.createObjectNode().put("schemaVersion", "1");
        ArrayNode repairs = planJson.putArray("repairs");
        repairs.add(repairId);
        JSON.writeValue(plan.toFile(), planJson);
        ObjectNode preview = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString()}));
        assertEquals("preview", preview.path("status").asText(), preview.toPrettyString());
        assertEquals("not-written", preview.path("changes").get(0).path("preview").asText());
        assertEquals(original, Files.readString(source), "Preview must not change source bytes.");
        String proposed = preview.path("changes").get(0).path("content").asText();
        assertTrue(proposed.contains(expectedSource), proposed);
        assertFalse(proposed.equals(original), proposed);

        Path reviewedPreview = project.resolve("reviewed-preview.json");
        JSON.writeValue(reviewedPreview.toFile(), preview);
        ObjectNode applied = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString(), "--apply",
            "--expect-preview", reviewedPreview.toString()}));
        assertEquals("applied", applied.path("status").asText(), applied.toPrettyString());
        assertEquals(proposed, Files.readString(source));
        JsonNode postAnalysis = applied.path("postAnalysis");
        assertEquals("completed", postAnalysis.path("status").asText(), postAnalysis.toPrettyString());
        assertFalse(hasRule(postAnalysis, ruleId), postAnalysis.toPrettyString());
        assertEquals(0, postAnalysis.path("summary").path("errors").asInt(), postAnalysis.toPrettyString());
        assertEquals(beforeOutput, compileAndRun(proposed, label + "-after"));
        assertEquals(expectedOutput, beforeOutput);
    }

    private String compileAndRun(String source, String label) throws Exception {
        Path sourceFile = temporaryDirectory.resolve(label).resolve("Example.java");
        Path classes = temporaryDirectory.resolve(label + "-classes");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classes);
        Files.writeString(sourceFile, source);
        int compileStatus = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "8", "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, compileStatus, "javac --release 8 should compile " + label);
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classes.toString(), "sample.Example")
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
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
            if (action.path("repairable").asBoolean() && action.hasNonNull("repairId")) return action;
        }
        throw new AssertionError("Missing repairable provider action: " + finding.toPrettyString());
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
