package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtlsIntegrationTest {
    private final Path root = Path.of("").toAbsolutePath();
    @TempDir
    Path temporaryDirectory;

    @Test
    void analyzesMavenFixtureWithoutChangingSources() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = root.resolve("acme/maven");
        Map<Path, byte[]> before = sourceSnapshot(project);

        JsonNode result = Main.analyze(options(project.resolve("pom.xml")));

        assertEquals("completed", result.path("status").asText());
        assertEquals(2, result.path("summary").path("filesAnalyzed").asInt());
        assertTrue(result.path("summary").path("warnings").asInt() >= 6, result.toPrettyString());
        assertEquals(1, result.path("summary").path("errors").asInt(), result.toPrettyString());
        assertEquals(5, countProvider(result, "Java"));
        assertTrue(countProvider(result, "PMD") >= 2);
        JsonNode warning = findDiagnostic(result, "The import java.util.Set is never used");
        assertTrue(warning.path("resolution").path("automaticFixAvailable").asBoolean());
        assertTrue(warning.path("resolution").path("jdtlsActions").findValuesAsText("title")
            .contains("Remove unused import"));
        JsonNode suppression = findAction(warning, "SuppressWarnings");
        assertTrue(suppression.path("suppressesWarning").asBoolean());
        assertFalse(suppression.path("fixesDiagnostic").asBoolean());
        JsonNode syntax = findDiagnostic(result, "Syntax error");
        assertFalse(syntax.path("resolution").path("automaticFixAvailable").asBoolean());
        assertEqualsSnapshot(before, sourceSnapshot(project));
    }

    @Test
    void analyzesGradleFixtureAndFindsQuickFixes() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = root.resolve("acme/gradle");
        Map<Path, byte[]> before = sourceSnapshot(project);

        JsonNode result = Main.analyze(options(project.resolve("build.gradle")));

        assertEquals("completed", result.path("status").asText());
        assertEquals(1, result.path("summary").path("filesAnalyzed").asInt());
        assertTrue(result.path("summary").path("warnings").asInt() >= 4, result.toPrettyString());
        assertEquals(3, countProvider(result, "Java"));
        assertTrue(countProvider(result, "PMD") >= 1);
        assertTrue(findDiagnostic(result, "The import java.util.Set is never used")
            .path("resolution").path("automaticFixAvailable").asBoolean());
        assertTrue(findDiagnostic(result, "Resource leak")
            .path("resolution").path("automaticFixAvailable").asBoolean());
        JsonNode pmdResource = findRule(result, "PMD/CloseResource");
        assertTrue(pmdResource.path("resolution").path("automaticFixAvailable").asBoolean(),
            "PMD resource finding should link only to a verified JDTLS quick fix: " + pmdResource);
        assertEqualsSnapshot(before, sourceSnapshot(project));
    }

    @Test
    void analyzesSingleSourceWithInferredMavenContext() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = root.resolve("acme/maven");
        Path source = project.resolve("src/main/java/com/acme/BadCode.java");
        Map<Path, byte[]> before = sourceSnapshot(project);

        JsonNode result = Main.analyze(options(source));

        assertEquals("completed", result.path("status").asText());
        assertEquals(1, result.path("summary").path("filesAnalyzed").asInt());
        assertEquals("src/main/java/com/acme/BadCode.java",
            result.path("files").path(0).path("path").asText());
        assertEqualsSnapshot(before, sourceSnapshot(project));
    }

    @Test
    void respectsProjectCompilerWarningSettings() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("compiler-settings"));
        Path settings = Files.createDirectories(project.resolve(".settings"));
        Path source = project.resolve("src/main/java/sample/Configured.java");
        Files.createDirectories(source.getParent());
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>settings</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        Files.writeString(source, "package sample;\nimport java.util.List;\nclass Configured {}\n");
        Path preferences = settings.resolve("org.eclipse.jdt.core.prefs");

        Files.writeString(preferences, compilerPreferences("ignore"));
        JsonNode ignored = Main.analyze(options(project.resolve("pom.xml")));
        assertFalse(containsDiagnostic(ignored, "The import java.util.List is never used"));

        Files.writeString(preferences, compilerPreferences("warning"));
        JsonNode enabled = Main.analyze(options(project.resolve("pom.xml")));
        assertTrue(containsDiagnostic(enabled, "The import java.util.List is never used"), enabled.toPrettyString());
        assertEquals(compilerPreferences("warning"), Files.readString(preferences));
    }

    @Test
    void jdtlsExtractConstantEditReturnsLinkedRenameCommandWithoutNameParameter() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("extract-constant"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Constants.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>extract</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String text = "package sample; class Constants { String value() { return \"same\"; } }";
        Files.writeString(source, text);
        String uri = source.toUri().toString();
        int start = text.indexOf("\"same\"");
        ObjectMapper json = new ObjectMapper();
        var params = json.createObjectNode();
        params.put("command", "extractConstant");
        params.set("commandArguments", json.createArrayNode());
        var context = params.putObject("context");
        context.putObject("textDocument").put("uri", uri);
        var range = context.putObject("range");
        range.putObject("start").put("line", 0).put("character", start);
        range.putObject("end").put("line", 0).put("character", start + 6);
        context.putObject("context").putArray("diagnostics");
        var options = params.putObject("options");
        options.put("tabSize", 4);
        options.put("insertSpaces", true);

        Path workspace = Files.createTempDirectory(temporaryDirectory, "jdtls-refactor-workspace-");
        Path configuration = Files.createTempDirectory(temporaryDirectory, "jdtls-refactor-config-");
        try (Main.LspSession session = Main.LspSession.start(
            Main.extractJdtls(), configuration, workspace, project)) {
            session.openDocument(uri, text);
            Map<String, java.util.List<JsonNode>> diagnostics = new java.util.HashMap<>();
            diagnostics.put(uri, new java.util.ArrayList<>());
            session.awaitDiagnostics(diagnostics, java.time.Duration.ofMillis(500),
                java.time.Duration.ofSeconds(15));
            JsonNode response = session.request("java/getRefactorEdit", params);
            assertTrue(response.hasNonNull("edit"), response.toPrettyString());
            assertEquals("java.action.rename", response.path("command").path("command").asText(),
                response.toPrettyString());
            assertFalse(response.path("command").toString().contains("constantName"),
                "JDTLS does not expose a constantName parameter in this refactor response");
        } finally {
            Main.deleteTree(workspace);
            Main.deleteTree(configuration);
        }
    }

    @Test
    void jdtlsExtractMethodEditAcceptsExplicitSelectionWithoutName() throws Exception {
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
        String text = """
            package sample;
            class Methods {
                int calculate(int input) {
                    int doubled = input * 2;
                    return doubled + 1;
                }
            }
            """;
        Files.writeString(source, text);
        String uri = source.toUri().toString();
        int start = text.indexOf("int doubled");
        int end = text.indexOf(";", text.indexOf("return doubled")) + 1;
        ObjectMapper json = new ObjectMapper();
        var params = json.createObjectNode();
        params.put("command", "extractMethod");
        var context = params.putObject("context");
        context.putObject("textDocument").put("uri", uri);
        var range = context.putObject("range");
        range.putObject("start").put("line", 3).put("character", start - text.lastIndexOf('\n', start) - 1);
        range.putObject("end").put("line", 4).put("character", end - text.lastIndexOf('\n', end) - 1);
        context.putObject("context").putArray("diagnostics");
        var options = params.putObject("options");
        options.put("tabSize", 4);
        options.put("insertSpaces", true);

        Path workspace = Files.createTempDirectory(temporaryDirectory, "jdtls-extract-method-workspace-");
        Path configuration = Files.createTempDirectory(temporaryDirectory, "jdtls-extract-method-config-");
        try (Main.LspSession session = Main.LspSession.start(
            Main.extractJdtls(), configuration, workspace, project)) {
            session.openDocument(uri, text);
            Map<String, java.util.List<JsonNode>> diagnostics = new java.util.HashMap<>();
            diagnostics.put(uri, new java.util.ArrayList<>());
            session.awaitDiagnostics(diagnostics, java.time.Duration.ofMillis(500),
                java.time.Duration.ofSeconds(15));
            JsonNode response = session.request("java/getRefactorEdit", params);
            assertTrue(response != null && response.hasNonNull("edit"), String.valueOf(response));
            assertTrue(response.hasNonNull("command"), response.toPrettyString());
            assertEquals("java.action.rename", response.path("command").path("command").asText(),
                response.toPrettyString());
        } finally {
            Main.deleteTree(workspace);
            Main.deleteTree(configuration);
        }
    }

    @Test
    void cliAppliesTwoRepairsAndReturnsPostAnalysis() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("repair-cli"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("UnusedImports.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>repair-cli</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String before = "package sample;\nimport java.util.List;\nimport java.util.Set;\nclass UnusedImports {}\n";
        Files.writeString(source, before);

        JsonNode analysis = Main.analyze(options(project.resolve("pom.xml")));
        List<String> repairIds = new ArrayList<>();
        for (JsonNode diagnostic : analysis.path("files").path(0).path("diagnostics")) {
            if (!diagnostic.path("message").asText().contains("never used")) continue;
            for (JsonNode action : diagnostic.path("resolution").path("jdtlsActions")) {
                if (action.hasNonNull("repairId")) repairIds.add(action.path("repairId").asText());
            }
        }
        assertTrue(repairIds.size() >= 2, analysis.toPrettyString());
        Path plan = project.resolve("repair-plan.json");
        ObjectMapper json = new ObjectMapper();
        json.writeValue(plan.toFile(), json.createObjectNode().put("schemaVersion", "1")
            .set("repairs", json.valueToTree(repairIds)));

        Path conflictingPlan = project.resolve("conflicting-repair-plan.json");
        json.writeValue(conflictingPlan.toFile(), json.createObjectNode().put("schemaVersion", "1")
            .set("repairs", json.createArrayNode().add(repairIds.getFirst()).add(repairIds.getFirst())));
        byte[] originalBytes = Files.readAllBytes(source);
        ByteArrayOutputStream conflictStdout = new ByteArrayOutputStream();
        ByteArrayOutputStream conflictStderr = new ByteArrayOutputStream();
        int conflictExitCode;
        try (PrintStream output = new PrintStream(conflictStdout, true, StandardCharsets.UTF_8);
             PrintStream error = new PrintStream(conflictStderr, true, StandardCharsets.UTF_8)) {
            conflictExitCode = Main.run(new String[]{"repair", "--project", project.toString(),
                "--plan", conflictingPlan.toString(), "--apply"}, output, error);
        }
        assertEquals(2, conflictExitCode);
        assertTrue(conflictStderr.toString(StandardCharsets.UTF_8).contains("duplicado"),
            conflictStderr.toString(StandardCharsets.UTF_8));
        assertTrue(java.util.Arrays.equals(originalBytes, Files.readAllBytes(source)),
            "A conflicting CLI plan must be rejected before writing source bytes");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        JsonNode repaired;
        try (PrintStream capture = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            Main.main(new String[]{"repair", "--project", project.toString(),
                "--plan", plan.toString(), "--apply"});
            capture.flush();
            repaired = json.readTree(stdout.toByteArray());
        } finally {
            System.setOut(previousOut);
        }

        assertEquals("applied", repaired.path("status").asText(), repaired.toPrettyString());
        assertTrue(repaired.path("postAnalysis").isObject(), repaired.toPrettyString());
        assertFalse(Files.readString(source).contains("import java.util.List"));
        assertFalse(Files.readString(source).contains("import java.util.Set"));
        assertFalse(containsDiagnostic(repaired.path("postAnalysis"), "never used"),
            repaired.path("postAnalysis").toPrettyString());
        assertTrue(repaired.path("diagnosticChanges").path("complete").asBoolean(),
            repaired.path("diagnosticChanges").toPrettyString());
        assertTrue(repaired.path("diagnosticChanges").path("resolved").size() >= 2,
            repaired.path("diagnosticChanges").toPrettyString());

        byte[] afterSuccessfulRepair = Files.readAllBytes(source);
        ByteArrayOutputStream staleStdout = new ByteArrayOutputStream();
        ByteArrayOutputStream staleStderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream error = new PrintStream(staleStderr, true, StandardCharsets.UTF_8)) {
            exitCode = Main.run(new String[]{"repair", "--project", project.toString(),
                "--plan", plan.toString(), "--apply"}, staleStdout, error);
        }
        assertEquals(2, exitCode);
        assertTrue(staleStderr.toString(StandardCharsets.UTF_8).contains("obsoleta"),
            staleStderr.toString(StandardCharsets.UTF_8));
        assertEquals(java.util.Arrays.toString(afterSuccessfulRepair),
            java.util.Arrays.toString(Files.readAllBytes(source)),
            "A stale CLI plan must not modify source bytes");
    }

    private Main.Options options(Path descriptor) {
        return Main.Options.parse(new String[]{"analyze", "--project", descriptor.toString()});
    }

    private static JsonNode findDiagnostic(JsonNode result, String messagePart) {
        for (JsonNode file : result.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (diagnostic.path("message").asText().contains(messagePart)) return diagnostic;
            }
        }
        throw new AssertionError("No diagnostic contains: " + messagePart);
    }

    private static JsonNode findRule(JsonNode result, String ruleId) {
        for (JsonNode file : result.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (ruleId.equals(diagnostic.path("ruleId").asText())) return diagnostic;
            }
        }
        throw new AssertionError("No diagnostic with ruleId: " + ruleId);
    }

    private static JsonNode findAction(JsonNode diagnostic, String titlePart) {
        for (JsonNode action : diagnostic.path("resolution").path("jdtlsActions")) {
            if (action.path("title").asText().contains(titlePart)) return action;
        }
        throw new AssertionError("No action contains: " + titlePart);
    }

    private static boolean containsDiagnostic(JsonNode result, String messagePart) {
        for (JsonNode file : result.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (diagnostic.path("message").asText().contains(messagePart)) return true;
            }
        }
        return false;
    }

    private static int countProvider(JsonNode result, String provider) {
        int count = 0;
        for (JsonNode file : result.path("files")) {
            for (JsonNode diagnostic : file.path("diagnostics")) {
                if (provider.equals(diagnostic.path("source").asText())) count++;
            }
        }
        return count;
    }

    private static String compilerPreferences(String unusedImportSeverity) {
        return "eclipse.preferences.version=1\n"
            + "org.eclipse.jdt.core.compiler.problem.unusedImport=" + unusedImportSeverity + "\n"
            + "org.eclipse.jdt.core.compiler.source=17\n"
            + "org.eclipse.jdt.core.compiler.compliance=17\n"
            + "org.eclipse.jdt.core.compiler.codegen.targetPlatform=17\n";
    }

    private static Map<Path, byte[]> sourceSnapshot(Path project) throws Exception {
        Map<Path, byte[]> snapshot = new LinkedHashMap<>();
        try (var files = Files.walk(project)) {
            for (Path path : files.filter(Files::isRegularFile)
                .filter(path -> !path.startsWith(project.resolve("target"))
                    && !path.startsWith(project.resolve("build"))
                    && !path.startsWith(project.resolve("bin"))
                    && !path.startsWith(project.resolve(".gradle"))).toList()) {
                snapshot.put(path, Files.readAllBytes(path));
            }
        }
        return snapshot;
    }

    private static void assertEqualsSnapshot(Map<Path, byte[]> expected, Map<Path, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((path, bytes) ->
            assertTrue(java.util.Arrays.equals(bytes, actual.get(path)), "Source changed: " + path));
    }
}
