package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractMethodRepairIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void usesUserSelectionAndNameInPreviewAndApply() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("extract-method"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Methods.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>extract-method</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String original = """
            package sample;
            class Methods {
                int calculate(int input) {
                    int doubled = input * 2;
                    return doubled + 1;
                }
            }
            """;
        Files.writeString(source, original);
        String selected = "int doubled = input * 2;";
        int start = original.indexOf(selected);
        int end = start + selected.length();
        ObjectNode selection = JSON.createObjectNode();
        selection.set("start", position(original, start));
        selection.set("end", position(original, end));

        ObjectNode report = JSON.createObjectNode().put("schemaVersion", "1").put("status", "completed");
        ObjectNode file = report.putArray("files").addObject();
        file.put("path", "src/main/java/sample/Methods.java");
        ObjectNode diagnostic = file.putArray("diagnostics").addObject();
        ObjectNode action = diagnostic.putObject("resolution").putArray("providerActions").addObject();
        action.put("repairId", "explicit-extract-method")
            .put("targetUri", source.toUri().toString())
            .put("sourceHash", sha256(Files.readAllBytes(source)));
        action.putArray("requiredParameters").add("selection").add("methodName");

        Path plan = temporaryDirectory.resolve("extract-method-plan.json");
        JSON.writeValue(plan.toFile(), JSON.createObjectNode().put("schemaVersion", "1")
            .set("repairs", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("id", "explicit-extract-method")
                .set("parameters", JSON.createObjectNode()
                    .put("methodName", "calculateDouble")
                    .set("selection", selection)))));

        ObjectNode preview = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString()}), ignored -> report);
        String previewContent = preview.path("changes").get(0).path("content").asText();
        assertEquals("preview", preview.path("status").asText());
        assertTrue(previewContent.contains("calculateDouble"), previewContent);
        assertTrue(previewContent.contains("int doubled = calculateDouble(input);"), previewContent);
        assertTrue(previewContent.contains("private int calculateDouble(int input)"), previewContent);
        assertEquals(original, Files.readString(source), "Preview must not write source.");

        AtomicInteger analyses = new AtomicInteger();
        ObjectNode applied = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString(), "--apply"}),
            ignored -> analyses.getAndIncrement() == 0 ? report : Main.analyze(ignored));
        assertEquals("applied", applied.path("status").asText(), applied.toPrettyString());
        assertEquals(previewContent, Files.readString(source));
        assertEquals("completed", applied.path("postAnalysis").path("status").asText(),
            applied.path("postAnalysis").toPrettyString());
        assertEquals(0, applied.path("postAnalysis").path("summary").path("errors").asInt(),
            applied.path("postAnalysis").toPrettyString());
    }

    @Test
    void rejectsInvalidSelectionAndMethodNameParameters() throws Exception {
        ObjectNode badSelection = extractPlan("validName", -1);
        assertThrows(() -> Main.validateRepairPlan(badSelection));

        ObjectNode badName = extractPlan("class", 0);
        assertThrows(() -> Main.validateRepairPlan(badName));
    }

    @Test
    void rejectsSelectionOutsideDocumentInPreviewAndApplyWithoutWriting() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("extract-method-invalid-range"));
        Path source = Files.writeString(project.resolve("Methods.java"),
            "class Methods { int value() { return 1; } }\n");
        String original = Files.readString(source);
        ObjectNode report = JSON.createObjectNode().put("schemaVersion", "1").put("status", "completed");
        ObjectNode action = report.putArray("files").addObject().putArray("diagnostics").addObject()
            .putObject("resolution").putArray("providerActions").addObject();
        action.put("repairId", "out-of-document-selection")
            .put("targetUri", source.toUri().toString())
            .put("sourceHash", sha256(Files.readAllBytes(source)));
        action.putArray("requiredParameters").add("selection").add("methodName");
        Path plan = temporaryDirectory.resolve("extract-method-invalid-range-plan.json");
        ObjectNode selection = JSON.createObjectNode();
        selection.putObject("start").put("line", 100).put("character", 0);
        selection.putObject("end").put("line", 100).put("character", 1);
        JSON.writeValue(plan.toFile(), JSON.createObjectNode().put("schemaVersion", "1")
            .set("repairs", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("id", "out-of-document-selection")
                .set("parameters", JSON.createObjectNode().put("methodName", "extracted")
                    .set("selection", selection)))));

        for (boolean apply : new boolean[]{false, true}) {
            String[] args = apply
                ? new String[]{"repair", "--project", project.toString(), "--plan", plan.toString(), "--apply"}
                : new String[]{"repair", "--project", project.toString(), "--plan", plan.toString()};
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Main.repair(Main.Options.parse(args), ignored -> report));
            assertEquals(original, Files.readString(source),
                "Rejected Extract Method must not write source in preview or apply mode.");
        }
    }

    @Test
    void cancellationBetweenJdtlsExtractionAndRenameLeavesOriginalUntouched() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("extract-method-cancel"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Methods.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>extract-method-cancel</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String original = """
            package sample;
            class Methods {
                int calculate(int input) {
                    int doubled = input * 2;
                    return doubled + 1;
                }
            }
            """;
        Files.writeString(source, original);
        String selected = "int doubled = input * 2;";
        int start = original.indexOf(selected);
        ObjectNode selection = JSON.createObjectNode();
        selection.set("start", position(original, start));
        selection.set("end", position(original, start + selected.length()));

        ObjectNode report = JSON.createObjectNode().put("schemaVersion", "1").put("status", "completed");
        ObjectNode diagnostic = report.putArray("files").addObject()
            .putArray("diagnostics").addObject();
        ObjectNode action = diagnostic.putObject("resolution").putArray("providerActions").addObject();
        action.put("repairId", "cancel-between-extract-and-rename")
            .put("targetUri", source.toUri().toString())
            .put("sourceHash", sha256(Files.readAllBytes(source)));
        action.putArray("requiredParameters").add("selection").add("methodName");
        Path plan = temporaryDirectory.resolve("extract-method-cancel-plan.json");
        JSON.writeValue(plan.toFile(), JSON.createObjectNode().put("schemaVersion", "1")
            .set("repairs", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("id", "cancel-between-extract-and-rename")
                .set("parameters", JSON.createObjectNode()
                    .put("methodName", "calculateDouble")
                    .set("selection", selection)))));
        Path cancelFile = temporaryDirectory.resolve("cancel-extract-method");
        Main.Options options = Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString(),
            "--apply", "--cancel-file", cancelFile.toString()});

        org.junit.jupiter.api.Assertions.assertThrows(CancellationException.class,
            () -> Main.repair(options, ignored -> report, () -> Files.createFile(cancelFile)));
        assertEquals(original, Files.readString(source),
            "Cancellation after the extraction edit but before rename must not write the original.");
    }

    private static ObjectNode extractPlan(String methodName, int startLine) {
        ObjectNode plan = JSON.createObjectNode().put("schemaVersion", "1");
        ObjectNode parameters = plan.putArray("repairs").addObject().put("id", "id")
            .putObject("parameters").put("methodName", methodName);
        ObjectNode selection = parameters.putObject("selection");
        selection.putObject("start").put("line", startLine).put("character", 0);
        selection.putObject("end").put("line", 0).put("character", 1);
        return plan;
    }

    private static ObjectNode position(String source, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return JSON.createObjectNode().put("line", line).put("character", offset - lineStart);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertThrows(ThrowingRunnable runnable) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, runnable::run);
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }
}
