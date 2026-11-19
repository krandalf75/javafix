package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairPreviewIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cliRepairPreviewDoesNotChangeSourceBytes() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");

        Path project = Files.createDirectory(temporaryDirectory.resolve("repair preview project"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("UnusedImport.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>repair-preview</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        byte[] originalSource = """
            package sample;
            import java.util.List;
            class UnusedImport {}
            """.getBytes(StandardCharsets.UTF_8);
        Files.write(source, originalSource);

        JsonNode analysis = Main.analyze(
            Main.Options.parse(new String[]{"analyze", "--project", project.resolve("pom.xml").toString()}));
        List<String> repairIds = new ArrayList<>();
        for (JsonNode diagnostic : analysis.path("files").path(0).path("diagnostics")) {
            if (!diagnostic.path("message").asText().contains("never used")) continue;
            for (JsonNode action : diagnostic.path("resolution").path("jdtlsActions")) {
                if (action.hasNonNull("repairId") && action.hasNonNull("workspaceEdit")) {
                    repairIds.add(action.path("repairId").asText());
                }
            }
        }
        assertFalse(repairIds.isEmpty(), analysis.toPrettyString());

        Path plan = project.resolve("repair plan.json");
        ObjectMapper json = new ObjectMapper();
        json.writeValue(plan.toFile(), json.createObjectNode().put("schemaVersion", "1")
            .set("repairs", json.valueToTree(repairIds)));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        JsonNode result;
        int exitCode;
        try (PrintStream output = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream error = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = Main.run(new String[]{"repair", "--project", project.toString(),
                "--plan", plan.toString()}, output, error);
            result = json.readTree(stdout.toByteArray());
        }

        assertEquals(0, exitCode, stderr.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        assertEquals("preview", result.path("status").asText(), result.toPrettyString());
        assertTrue(result.path("changes").isArray(), result.toPrettyString());
        assertEquals(originalSource.length, Files.readAllBytes(source).length);
        assertTrue(java.util.Arrays.equals(originalSource, Files.readAllBytes(source)),
            "Preview must leave the source bytes unchanged");
    }
}
