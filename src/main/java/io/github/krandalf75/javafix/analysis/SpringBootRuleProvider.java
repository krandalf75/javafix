package io.github.krandalf75.javafix.analysis;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative source-level Spring checks. These are advisory heuristics and do not require
 * Spring on JavaFix's runtime classpath.
 */
public final class SpringBootRuleProvider implements DiagnosticProvider {
    private static final Pattern FIELD_INJECTION = Pattern.compile(
        "(?m)^\\s*@(?:org\\.springframework\\.beans\\.factory\\.annotation\\.)?Autowired\\s*\\R"
            + "(?:\\s*@[\\w.]+(?:\\([^\\n]*\\))?\\s*\\R)*"
            + "\\s*(?:private|protected|public)?\\s*[\\w.$<>?, \\[\\]]+\\s+\\w+\\s*(?:=[^;]*)?;");
    private static final Pattern TRANSACTIONAL_PRIVATE = Pattern.compile(
        "(?m)^\\s*@(?:org\\.springframework\\.transaction\\.annotation\\.)?Transactional\\b"
            + "[^\\n]*\\R(?:\\s*@[\\w.]+(?:\\([^\\n]*\\))?\\s*\\R)*"
            + "\\s*private\\s+[^;{}]+\\([^;{}]*\\)\\s*\\{");
    private static final Pattern CONFIG_PROPERTIES = Pattern.compile(
        "(?m)^\\s*@(?:org\\.springframework\\.boot\\.context\\.properties\\.)?ConfigurationProperties\\b");
    private static final Pattern VALIDATION_CONSTRAINT = Pattern.compile(
        "@(?:jakarta|javax)\\.validation\\.constraints\\.(?:NotNull|NotBlank|NotEmpty)\\b|"
            + "@(?:NotNull|NotBlank|NotEmpty)\\b");
    private static final Pattern VALIDATED = Pattern.compile("@(?:org\\.springframework\\.validation\\.)?Validated\\b");
    private static final Pattern GET_MAPPING_BODY = Pattern.compile(
        "(?m)^\\s*@(?:org\\.springframework\\.web\\.bind\\.annotation\\.)?GetMapping\\b[^\\n]*\\R"
            + "[^{}]{0,300}?@(?:org\\.springframework\\.web\\.bind\\.annotation\\.)?RequestBody\\b");

    @Override
    public String id() {
        return "SpringBoot";
    }

    @Override
    public Map<Path, List<ObjectNode>> analyze(List<Path> targets) throws IOException {
        Map<Path, List<ObjectNode>> findings = new HashMap<>();
        for (Path target : targets) {
            Path file = target.toRealPath();
            findings.put(file, new ArrayList<>());
            String source = Files.readString(file);
            if (!looksLikeSpringSource(source)) continue;

            addMatches(findings.get(file), source, FIELD_INJECTION, "FieldInjection",
                "Spring-managed field injection couples the class to container reflection; prefer constructor injection.");
            addMatches(findings.get(file), source, TRANSACTIONAL_PRIVATE, "PrivateTransactionalMethod",
                "Spring proxy-based transactions do not intercept private methods; move the transaction boundary to an interceptable method.");
            if (CONFIG_PROPERTIES.matcher(source).find()
                && VALIDATION_CONSTRAINT.matcher(source).find()
                && !VALIDATED.matcher(source).find()) {
                addMatches(findings.get(file), source, CONFIG_PROPERTIES, "ConfigurationValidation",
                    "Configuration properties declare validation constraints but the type has no @Validated annotation.");
            }
            addMatches(findings.get(file), source, GET_MAPPING_BODY, "GetMappingRequestBody",
                "A GET endpoint declares a request body; HTTP semantics and intermediaries do not reliably support GET content.");
        }
        return findings;
    }

    private static boolean looksLikeSpringSource(String source) {
        return source.contains("org.springframework") || source.contains("@Autowired")
            || source.contains("@ConfigurationProperties") || source.contains("@Transactional")
            || source.contains("@GetMapping");
    }

    private static void addMatches(List<ObjectNode> findings, String source, Pattern pattern,
                                   String rule, String message) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            int line = 1;
            for (int i = 0; i < matcher.start(); i++) {
                if (source.charAt(i) == '\n') line++;
            }
            findings.add(diagnostic(rule, message, line));
        }
    }

    private static ObjectNode diagnostic(String rule, String message, int line) {
        ObjectNode diagnostic = JsonNodeFactory.instance.objectNode();
        diagnostic.put("source", "SpringBoot");
        diagnostic.put("code", rule);
        diagnostic.put("ruleId", "SpringBoot/" + rule);
        diagnostic.put("severity", "Warning");
        diagnostic.put("message", message);
        diagnostic.putNull("lspRange");
        ObjectNode range = diagnostic.putObject("range");
        range.put("lineNumbering", "1-based");
        range.put("columnNumbering", "1-based");
        range.put("endExclusive", false);
        range.putObject("start").put("line", line);
        range.putObject("end").put("line", line);
        ObjectNode resolution = diagnostic.putObject("resolution");
        resolution.put("description", "Heurística advisory Spring Boot; revisa el contexto antes de cambiar el código.");
        resolution.putArray("providerActions");
        resolution.put("automaticFixAvailable", false);
        resolution.put("hasJdtlsAction", false);
        return diagnostic;
    }
}
