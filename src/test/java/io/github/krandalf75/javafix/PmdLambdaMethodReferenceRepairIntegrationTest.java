package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmdLambdaMethodReferenceRepairIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void previewsAndAppliesSafeStaticLambdaMethodReferenceRepair() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("lambda-method-ref"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("StaticLambda.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>lambda-method-ref</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        String original = """
            package sample;
            import java.util.function.IntUnaryOperator;
            public class StaticLambda {
                public int apply(int input) {
                    IntUnaryOperator operation = value -> Math.abs(value);
                    return operation.applyAsInt(input);
                }
            }
            """;
        Files.writeString(source, original);

        ObjectNode report = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        JsonNode finding = findRule(report, "PMD/LambdaCanBeMethodReference");
        JsonNode action = finding.path("resolution").path("providerActions").get(0);
        assertEquals("Convert lambda to method reference", action.path("title").asText(),
            report.toPrettyString());
        assertTrue(action.path("workspaceEdit").isObject());
        String id = action.path("repairId").asText();

        Path plan = temporaryDirectory.resolve("lambda-plan.json");
        JSON.writeValue(plan.toFile(), JSON.createObjectNode().put("schemaVersion", "1")
            .set("repairs", JSON.createArrayNode().add(id)));
        ObjectNode preview = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString()}),
            ignored -> report);
        String previewContent = preview.path("changes").get(0).path("content").asText();
        assertEquals("preview", preview.path("status").asText());
        assertTrue(previewContent.contains("Math::abs"), previewContent);
        assertEquals(original, Files.readString(source));

        AtomicInteger analyses = new AtomicInteger();
        ObjectNode applied = Main.repair(Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", plan.toString(), "--apply"}),
            ignored -> analyses.getAndIncrement() == 0 ? report : Main.analyze(ignored));
        assertEquals("applied", applied.path("status").asText(), applied.toPrettyString());
        assertTrue(Files.readString(source).contains("Math::abs"));
        assertEquals(0, applied.path("postAnalysis").path("summary").path("errors").asInt(),
            applied.path("postAnalysis").toPrettyString());
        assertFalse(hasRule(applied.path("postAnalysis"), "PMD/LambdaCanBeMethodReference"),
            applied.path("postAnalysis").toPrettyString());
    }

    @Test
    void doesNotOfferMethodReferenceForInstanceReceiverWithCaptureSemantics() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 25,
            "JDTLS integration tests require Java 25");
        Path project = Files.createDirectory(temporaryDirectory.resolve("lambda-instance-receiver"));
        Path source = Files.createDirectories(project.resolve("src/main/java/sample"))
            .resolve("InstanceLambda.java");
        Files.writeString(project.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>lambda-instance</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        Files.writeString(source, """
            package sample;
            import java.util.function.Consumer;
            class InstanceLambda {
                Consumer<String> consumer = value -> System.out.println(value);
            }
            """);

        JsonNode report = Main.analyze(Main.Options.parse(new String[]{
            "analyze", "--project", project.toString()}));
        JsonNode finding = findRule(report, "PMD/LambdaCanBeMethodReference");
        assertFalse(finding.path("resolution").path("automaticFixAvailable").asBoolean(),
            finding.toPrettyString());
        assertTrue(finding.path("resolution").path("providerActions").isEmpty(),
            finding.toPrettyString());
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
}
