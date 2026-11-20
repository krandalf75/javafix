package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.krandalf75.javafix.analysis.PmdRuleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmdRuleProviderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsSelectedResourceAndUnusedVariableRulesOnPositiveFixture() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("PmdIssues.java"), """
            import java.io.FileInputStream;
            import java.io.IOException;
            import java.util.List;

            class PmdIssues {
                int read(String path) throws IOException {
                    int orphanedValue = 3;
                    FileInputStream stream = new FileInputStream(path);
                    return stream.available();
                }
            }
            """);

        List<ObjectNode> diagnostics = new PmdRuleProvider()
            .analyze(List.of(source)).get(source.toRealPath());
        Set<String> rules = diagnostics.stream()
            .map(node -> node.path("ruleId").asText())
            .collect(Collectors.toSet());

        assertTrue(rules.contains("PMD/UnusedLocalVariable"), diagnostics.toString());
        assertTrue(rules.contains("PMD/CloseResource"), diagnostics.toString());
        assertTrue(diagnostics.stream().allMatch(node -> "PMD".equals(node.path("source").asText())));
        assertTrue(diagnostics.stream().allMatch(node -> node.path("ruleId").asText().startsWith("PMD/")));
        assertTrue(diagnostics.stream().allMatch(node -> !node.path("code").isMissingNode()
            && !node.path("code").asText().startsWith("PMD/")));
    }

    @Test
    void doesNotReportUnusedImportsOrClosedResourcesOnNegativeFixture() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("PmdClean.java"), """
            import java.io.FileInputStream;
            import java.io.IOException;

            class PmdClean {
                int read(String path) throws IOException {
                    try (FileInputStream stream = new FileInputStream(path)) {
                        return stream.available();
                    }
                }

                String guarded(String value) {
                    if (value != null && !value.isEmpty()) {
                        return value;
                    }
                    return "";
                }
            }
            """);

        List<ObjectNode> diagnostics = new PmdRuleProvider()
            .analyze(List.of(source)).get(source.toRealPath());

        assertEquals(List.of(), diagnostics);
    }

    @Test
    void identifiesManualFinallyCloseAsUseTryWithResourcesCandidate() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("ManualFinallyClose.java"), """
            import java.io.FileInputStream;
            import java.io.IOException;

            class ManualFinallyClose {
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

        List<ObjectNode> diagnostics = new PmdRuleProvider()
            .analyze(List.of(source)).get(source.toRealPath());

        assertTrue(diagnostics.stream().anyMatch(node ->
            "PMD/UseTryWithResources".equals(node.path("ruleId").asText())), diagnostics.toString());
    }

    @Test
    void reportsRepresentativeExceptionNullApiAndStyleRules() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("CandidateRules.java"), """
            import java.nio.charset.Charset;
            import java.util.List;

            class CandidateRules {
                private String neverRead;

                static void run(List<String> values) throws Exception {
                    if (values.size() == 0)
                        System.out.println(Charset.forName("UTF-8"));
                    try {
                        throw new NullPointerException();
                    } catch (NullPointerException ex) {
                        ex.printStackTrace();
                    } catch (Throwable ex) {
                    }
                    String text = " value ";
                    text.trim();
                }

                static String brokenNullGuard(String value) {
                    if (value != null || !value.equals("")) return value;
                    if (value == null && value.equals("")) return value;
                    return "";
                }
            }
            """);

        List<ObjectNode> diagnostics = new PmdRuleProvider()
            .analyze(List.of(source)).get(source.toRealPath());
        Set<String> rules = diagnostics.stream()
            .map(node -> node.path("ruleId").asText())
            .collect(Collectors.toSet());

        assertTrue(rules.contains("PMD/AvoidPrintStackTrace"), diagnostics.toString());
        assertTrue(rules.contains("PMD/AvoidCatchingGenericException"), diagnostics.toString());
        assertTrue(rules.contains("PMD/EmptyCatchBlock"), diagnostics.toString());
        assertTrue(rules.contains("PMD/AvoidThrowingNullPointerException"), diagnostics.toString());
        assertTrue(rules.contains("PMD/ControlStatementBraces"), diagnostics.toString());
        assertTrue(rules.contains("PMD/UseCollectionIsEmpty"), diagnostics.toString());
        assertTrue(rules.contains("PMD/UnusedPrivateField"), diagnostics.toString());
        assertTrue(rules.contains("PMD/UseStandardCharsets"), diagnostics.toString());
        assertTrue(rules.contains("PMD/UselessPureMethodCall"), diagnostics.toString());
        assertTrue(rules.contains("PMD/BrokenNullCheck"), diagnostics.toString());
    }
}
