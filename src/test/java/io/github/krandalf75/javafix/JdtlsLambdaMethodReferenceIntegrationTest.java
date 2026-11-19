package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtlsLambdaMethodReferenceIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void requestsPmdLambdaCanBeMethodReferenceAction() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");

        Path project = Files.createDirectory(temporaryDirectory.resolve("lambda-method-reference"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("Lambda.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>lambda-method-reference</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String text = """
            package sample;
            import java.util.function.Consumer;
            class Lambda {
                Consumer<String> consumer = value -> System.out.println(value);
            }
            """;
        Files.writeString(source, text);
        String uri = source.toUri().toString();
        int lambdaStart = text.indexOf("value ->");
        int lambdaEnd = text.indexOf(");", lambdaStart) + 1;

        ObjectMapper json = new ObjectMapper();
        var params = json.createObjectNode();
        params.putObject("textDocument").put("uri", uri);
        var range = params.putObject("range");
        range.putObject("start").put("line", 3).put("character", lambdaStart - text.lastIndexOf('\n', lambdaStart) - 1);
        range.putObject("end").put("line", 3).put("character", lambdaEnd - text.lastIndexOf('\n', lambdaEnd) - 1);
        var diagnostic = json.createObjectNode();
        diagnostic.put("range", range.deepCopy());
        diagnostic.put("severity", 2);
        diagnostic.put("source", "PMD");
        diagnostic.put("code", "LambdaCanBeMethodReference");
        diagnostic.put("message", "Lambda can be replaced with method reference");
        params.putObject("context").putArray("diagnostics").add(diagnostic);

        Path workspace = Files.createTempDirectory(temporaryDirectory, "jdtls-lambda-workspace-");
        Path configuration = Files.createTempDirectory(temporaryDirectory, "jdtls-lambda-config-");
        try (Main.LspSession session = Main.LspSession.start(
            Main.extractJdtls(), configuration, workspace, project)) {
            session.openDocument(uri, text);
            Map<String, List<JsonNode>> diagnostics = new HashMap<>();
            diagnostics.put(uri, new ArrayList<>());
            session.awaitDiagnostics(diagnostics, Duration.ofMillis(500), Duration.ofSeconds(15));

            JsonNode response = session.request("textDocument/codeAction", params);
            assertTrue(response.isArray(), response.toPrettyString());
            JsonNode candidate = null;
            for (JsonNode action : response) {
                String title = action.path("title").asText().toLowerCase();
                if (title.contains("method reference")) {
                    candidate = action;
                    break;
                }
            }
            if (candidate == null) {
                List<String> titles = new ArrayList<>();
                response.forEach(action -> titles.add(action.path("title").asText()));
                assertEquals(List.of(
                    "Convert to anonymous class creation",
                    "Extract to method",
                    "Generate Getters and Setters",
                    "Generate Getters",
                    "Generate Setters"), titles,
                    "JDTLS 1.61.0 does not offer LambdaCanBeMethodReference; actual code actions: "
                        + response.toPrettyString());
                return;
            }

            if (!candidate.hasNonNull("edit") && candidate.hasNonNull("data")) {
                candidate = session.request("codeAction/resolve", candidate);
            }
            assertTrue(candidate.hasNonNull("edit"),
                "The method-reference action must resolve to a direct WorkspaceEdit: " + candidate.toPrettyString());
            assertEquals("value -> System.out.println(value)",
                text.substring(lambdaStart, lambdaEnd));
            String transformed = applyTextEdits(text, candidate.path("edit").path("changes").path(uri));
            assertTrue(transformed.contains("System.out::println"), transformed);
            assertFalse(transformed.contains("value -> System.out.println(value)"), transformed);
        } finally {
            Main.deleteTree(workspace);
            Main.deleteTree(configuration);
        }
    }

    private static String applyTextEdits(String text, JsonNode edits) {
        assertTrue(edits.isArray(), "Expected a WorkspaceEdit changes entry: " + edits);
        List<JsonNode> ordered = new ArrayList<>();
        edits.forEach(ordered::add);
        ordered.sort((left, right) -> Integer.compare(
            offset(text, right.path("range").path("start")),
            offset(text, left.path("range").path("start"))));
        StringBuilder result = new StringBuilder(text);
        for (JsonNode edit : ordered) {
            int start = offset(result.toString(), edit.path("range").path("start"));
            int end = offset(result.toString(), edit.path("range").path("end"));
            result.replace(start, end, edit.path("newText").asText());
        }
        return result.toString();
    }

    private static int offset(String text, JsonNode position) {
        int line = position.path("line").asInt();
        int character = position.path("character").asInt();
        int offset = 0;
        for (int current = 0; current < line; current++) {
            offset = text.indexOf('\n', offset) + 1;
            if (offset == 0) return text.length();
        }
        return Math.min(text.length(), offset + character);
    }
}
