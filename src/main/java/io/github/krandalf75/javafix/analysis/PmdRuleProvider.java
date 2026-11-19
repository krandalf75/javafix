package io.github.krandalf75.javafix.analysis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PMD provider restricted to the reviewed JavaFix MVP ruleset. */
public final class PmdRuleProvider implements DiagnosticProvider {
    @Override
    public String id() {
        return "PMD";
    }

    @Override
    public Map<Path, List<ObjectNode>> analyze(List<Path> targets) throws IOException {
        Map<Path, List<ObjectNode>> findings = new HashMap<>();
        for (Path target : targets) findings.put(target.toRealPath(), new ArrayList<>());
        if (findings.isEmpty()) return findings;

        PMDConfiguration configuration = new PMDConfiguration();
        configuration.setThreads(1);
        try (PmdAnalysis pmd = PmdAnalysis.create(configuration)) {
            pmd.addRuleSet(pmd.newRuleSetLoader().loadFromResource("pmd/javafix-rules.xml"));
            for (Path target : findings.keySet()) pmd.files().addFile(target);
            Report report = pmd.performAnalysisAndCollectReport();
            if (!report.getConfigurationErrors().isEmpty()) {
                throw new IOException("Configuración de reglas PMD inválida: "
                    + report.getConfigurationErrors().get(0));
            }
            for (RuleViolation violation : report.getViolations()) {
                Path file = pathFromUri(violation.getFileId().getUriString());
                List<ObjectNode> fileFindings = findings.get(file);
                if (fileFindings != null) fileFindings.add(toDiagnostic(violation));
            }
        } catch (RuntimeException e) {
            throw new IOException("Falló el proveedor PMD al analizar los ficheros seleccionados: "
                + e.getMessage(), e);
        }
        return findings;
    }

    private static Path pathFromUri(String uri) {
        try {
            return Path.of(URI.create(uri)).toRealPath();
        } catch (Exception ignored) {
            return Path.of(uri).toAbsolutePath().normalize();
        }
    }

    private ObjectNode toDiagnostic(RuleViolation violation) {
        String ruleName = violation.getRule().getName();
        ObjectNode diagnostic = JsonNodeFactory.instance.objectNode();
        diagnostic.put("source", id());
        diagnostic.put("code", ruleName);
        diagnostic.put("ruleId", "PMD/" + ruleName);
        diagnostic.put("severity", "Warning");
        diagnostic.put("message", violation.getDescription());
        diagnostic.putNull("lspRange");
        ObjectNode range = diagnostic.putObject("range");
        range.put("lineNumbering", "1-based");
        range.put("columnNumbering", "1-based");
        range.put("endExclusive", false);
        position(range.putObject("start"), violation.getBeginLine(), violation.getBeginColumn());
        position(range.putObject("end"), violation.getEndLine(), violation.getEndColumn());

        ObjectNode resolution = diagnostic.putObject("resolution");
        resolution.put("description", "Consulta la documentación de la regla " + ruleName
            + " de PMD; JavaFix no aplica cambios automáticamente.");
        resolution.putArray("providerActions");
        resolution.put("automaticFixAvailable", false);
        resolution.put("hasJdtlsAction", false);
        return diagnostic;
    }

    private static void position(ObjectNode position, int line, int column) {
        if (line > 0) position.put("line", line);
        if (column > 0) position.put("column", column);
    }
}
