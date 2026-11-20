package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmdCloseResourceRepairIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void repairsPmdCloseResourceUsingVerifiedJdtlsEditAndReanalyzes() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("pmd-close-resource"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Leaky.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>close-resource</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String original = """
            package sample;
            import java.io.FileInputStream;
            import java.io.IOException;
            class Leaky {
                int read(String path) throws IOException {
                    FileInputStream stream = new FileInputStream(path);
                    return stream.read();
                }
            }
            """;
        Files.writeString(source, original);

        ObjectMapper json = new ObjectMapper();
        ObjectNode analysis = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        JsonNode pmdResource = findRule(analysis, "PMD/CloseResource");
        assertTrue(pmdResource.path("resolution").path("automaticFixAvailable").asBoolean(),
            analysis.toPrettyString());
        JsonNode jdtlsAction = pmdResource.path("resolution").path("jdtlsActions").get(0);
        assertTrue(jdtlsAction.hasNonNull("repairId"), pmdResource.toPrettyString());
        assertTrue(jdtlsAction.hasNonNull("workspaceEdit"), pmdResource.toPrettyString());

        Path plan = project.resolve("pmd-repair.json");
        json.writeValue(plan.toFile(), json.createObjectNode().put("schemaVersion", "1")
            .set("repairs", json.createArrayNode().add(jdtlsAction.path("repairId").asText())));
        byte[] originalBytes = Files.readAllBytes(source);
        ObjectNode preview = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString()}), ignored -> analysis);
        assertEquals("preview", preview.path("status").asText(), preview.toPrettyString());
        assertTrue(preview.path("changes").path(0).path("content").asText().contains("try ("),
            preview.toPrettyString());
        assertTrue(java.util.Arrays.equals(originalBytes, Files.readAllBytes(source)),
            "The CloseResource preview must not write source bytes");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        JsonNode result;
        try (PrintStream capture = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            Main.main(new String[]{"repair", "--project", project.toString(),
                "--plan", plan.toString(), "--apply"});
            capture.flush();
            result = json.readTree(stdout.toByteArray());
        } finally {
            System.setOut(previousOut);
        }

        String repairedSource = Files.readString(source);
        assertEquals("applied", result.path("status").asText(), result.toPrettyString());
        assertTrue(repairedSource.contains("try ("), repairedSource);
        assertFalse(repairedSource.equals(original), "The selected JDTLS WorkspaceEdit must be written");
        assertFalse(hasRule(result.path("postAnalysis"), "PMD/CloseResource"),
            result.path("postAnalysis").toPrettyString());
        assertTrue(hasChangeRule(result.path("diagnosticChanges").path("resolved"), "PMD/CloseResource"),
            result.path("diagnosticChanges").toPrettyString());
        assertTrue(result.path("diagnosticChanges").path("persistent").isArray());
        assertTrue(result.path("diagnosticChanges").path("new").isArray());
        assertTrue(result.path("diagnosticChanges").path("complete").asBoolean());
    }

    @Test
    void doesNotClaimManualFinallyConversionWithoutVerifiedJdtlsEdit() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("pmd-try-with-resources"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("ManualClose.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>manual-close</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        Files.writeString(source, """
            package sample;
            import java.io.FileInputStream;
            import java.io.IOException;
            class ManualClose {
                int read(String path) throws IOException {
                    FileInputStream stream = new FileInputStream(path);
                    try {
                        return stream.read();
                    } finally {
                        stream.close();
                    }
                }
            }
            """);
        Path sourceWithFinallyLogic = source.resolveSibling("ManualCloseWithFinallyLogic.java");
        Files.writeString(sourceWithFinallyLogic, """
            package sample;
            import java.io.FileInputStream;
            import java.io.IOException;
            class ManualCloseWithFinallyLogic {
                int read(String path) throws IOException {
                    FileInputStream stream = new FileInputStream(path);
                    try {
                        return stream.read();
                    } finally {
                        System.getLogger(getClass().getName()).log(
                            System.Logger.Level.DEBUG, "closing " + path);
                        stream.close();
                    }
                }
            }
            """);

        JsonNode analysis = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        JsonNode candidate = findRule(analysis, "PMD/UseTryWithResources");
        assertFalse(candidate.path("resolution").path("automaticFixAvailable").asBoolean(),
            candidate.toPrettyString());
        JsonNode candidateWithFinallyLogic = findRule(analysis, sourceWithFinallyLogic,
            "PMD/UseTryWithResources");
        assertFalse(candidateWithFinallyLogic.path("resolution").path("automaticFixAvailable").asBoolean(),
            candidateWithFinallyLogic.toPrettyString());
    }

    private static JsonNode findRule(JsonNode report, String ruleId) {
        return findRule(report, null, ruleId);
    }

    private static JsonNode findRule(JsonNode report, Path source, String ruleId) {
        for (JsonNode file : report.path("files")) {
            if (source != null && !source.toString().replace('\\', '/').endsWith(file.path("path").asText())) {
                continue;
            }
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

    private static boolean hasChangeRule(JsonNode findings, String ruleId) {
        for (JsonNode finding : findings) {
            if (ruleId.equals(finding.path("ruleId").asText())) return true;
        }
        return false;
    }
}
