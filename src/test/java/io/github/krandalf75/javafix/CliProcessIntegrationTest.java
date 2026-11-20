package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliProcessIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void runsHeadlessWithJsonStdoutAndPathsContainingSpaces() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("project with spaces"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("UnusedImport.java");
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>cli-process</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        byte[] original = """
            package sample;
            import java.util.List;
            class UnusedImport {}
            """.getBytes(StandardCharsets.UTF_8);
        Files.write(source, original);

        ProcessResult analysisProcess = runCli("analyze", "--project", pom.toString());
        assertEquals(0, analysisProcess.exitCode(), analysisProcess.stderr());
        assertJsonOnly(analysisProcess.stdout());
        JsonNode analysis = JSON.readTree(analysisProcess.stdout());
        assertEquals("completed", analysis.path("status").asText(), analysis.toPrettyString());
        String repairId = findUnusedImportRepairId(analysis);

        Path plan = project.resolve("repair plan.json");
        JSON.writeValue(plan.toFile(), JSON.createObjectNode().put("schemaVersion", "1")
            .set("repairs", JSON.createArrayNode().add(repairId)));
        ProcessResult previewProcess = runCli("repair", "--project", project.toString(),
            "--plan", plan.toString());
        assertEquals(0, previewProcess.exitCode(), previewProcess.stderr());
        assertJsonOnly(previewProcess.stdout());
        JsonNode preview = JSON.readTree(previewProcess.stdout());
        assertEquals("preview", preview.path("status").asText(), preview.toPrettyString());
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(source)),
            "Headless preview must not write source files");

        ProcessResult invalidCommand = runCli("unknown-command");
        assertEquals(2, invalidCommand.exitCode());
        assertEquals("", invalidCommand.stdout());
        assertTrue(invalidCommand.stderr().startsWith("javafix: "),
            invalidCommand.stderr());
    }

    @Test
    void cooperativelyCancelsSubprocessAfterJdtlsStarts() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("cancel-project"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Source.java");
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>cancel-project</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        byte[] original = "package sample; class Source { int value() { return 1; } }\n"
            .getBytes(StandardCharsets.UTF_8);
        Files.write(source, original);
        Path cancelFile = temporaryDirectory.resolve("cancel-request");

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Main.class.getName());
        command.addAll(List.of("analyze", "--project", pom.toString(),
            "--cancel-file", cancelFile.toString()));
        Path stdout = temporaryDirectory.resolve("cancel.stdout");
        Path stderr = temporaryDirectory.resolve("cancel.stderr");
        Process process = new ProcessBuilder(command)
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start();

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean jdtlsStarted = false;
        while (process.isAlive() && System.nanoTime() < deadline) {
            if (process.descendants().findAny().isPresent()) {
                jdtlsStarted = true;
                break;
            }
            Thread.sleep(25);
        }
        assertTrue(jdtlsStarted, "JDTLS subprocess did not start in time");
        Files.createFile(cancelFile);
        assertTrue(process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS),
            "JavaFix did not stop after cooperative cancellation");

        String output = Files.readString(stdout, StandardCharsets.UTF_8);
        String error = Files.readString(stderr, StandardCharsets.UTF_8);
        assertEquals(2, process.exitValue(), error);
        assertEquals("", output, "Cancelled analysis must not emit a partial JSON report");
        assertTrue(error.contains("cancel"), error);
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(source)),
            "Cancellation during analysis must not modify source files");
    }

    private ProcessResult runCli(String... arguments) throws Exception {
        Path stdout = temporaryDirectory.resolve("cli-" + System.nanoTime() + ".stdout");
        Path stderr = temporaryDirectory.resolve("cli-" + System.nanoTime() + ".stderr");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Main.class.getName());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start();
        if (!process.waitFor(Duration.ofSeconds(180).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("JavaFix subprocess timed out: " + String.join(" ", command));
        }
        return new ProcessResult(process.exitValue(),
            Files.readString(stdout, StandardCharsets.UTF_8),
            Files.readString(stderr, StandardCharsets.UTF_8));
    }

    private static void assertJsonOnly(String output) throws Exception {
        String trimmed = output.strip();
        assertTrue(trimmed.startsWith("{") && trimmed.endsWith("}"),
            "Expected stdout to contain only a JSON object, got: " + output);
        assertTrue(JSON.readTree(trimmed).isObject(), output);
        assertFalse(trimmed.contains("javafix:"), output);
    }

    private static String findUnusedImportRepairId(JsonNode report) {
        for (JsonNode file : report.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (!diagnostic.path("message").asText().contains("never used")) continue;
                for (JsonNode action : diagnostic.path("resolution").path("jdtlsActions")) {
                    if (action.hasNonNull("repairId")) return action.path("repairId").asText();
                }
            }
        }
        throw new AssertionError("No unused-import repairId in report: " + report.toPrettyString());
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
