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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtlsInlineLocalIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void doesNotAdvertiseInlineLocalWhenJdtlsReturnsNoHeadlessAction() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");

        Path project = Files.createDirectory(temporaryDirectory.resolve("inline-local"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("InlineMe.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>inline-local</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String text = "package sample; class InlineMe { int value() { int temp = 1 + 2; return temp; } }";
        Files.writeString(source, text);
        String uri = source.toUri().toString();
        int start = text.indexOf("temp =");
        int end = text.indexOf(";", start) + 1;
        ObjectMapper json = new ObjectMapper();
        var params = json.createObjectNode();
        params.putObject("textDocument").put("uri", uri);
        var range = params.putObject("range");
        range.putObject("start").put("line", 0).put("character", start);
        range.putObject("end").put("line", 0).put("character", end);
        var context = params.putObject("context");
        context.putArray("diagnostics");
        context.putArray("only").add("refactor.inline");

        Path workspace = Files.createTempDirectory(temporaryDirectory, "jdtls-inline-workspace-");
        Path configuration = Files.createTempDirectory(temporaryDirectory, "jdtls-inline-config-");
        try (Main.LspSession session = Main.LspSession.start(
            Main.extractJdtls(), configuration, workspace, project)) {
            session.openDocument(uri, text);
            Map<String, List<JsonNode>> diagnostics = new HashMap<>();
            diagnostics.put(uri, new ArrayList<>());
            session.awaitDiagnostics(diagnostics, Duration.ofMillis(500), Duration.ofSeconds(15));

            JsonNode response = session.request("textDocument/codeAction", params);
            for (JsonNode candidate : response) {
                assertFalse(candidate.path("kind").asText().equals("refactor.inline")
                        || candidate.path("title").asText().toLowerCase().contains("inline"),
                    "This fixture must not advertise an unsupported Inline Local repair: "
                        + candidate.toPrettyString());
            }
            assertTrue(response.isArray(), response.toPrettyString());
        } finally {
            Main.deleteTree(workspace);
            Main.deleteTree(configuration);
        }
    }

}
