package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.krandalf75.javafix.analysis.SpringBootRuleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringBootRuleProviderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsAdvisoriesForSpringInjectionConfigurationTransactionsAndGetApi() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("SpringIssues.java"), """
            package sample;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            import org.springframework.transaction.annotation.Transactional;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RestController;
            import jakarta.validation.constraints.NotBlank;

            @RestController
            @ConfigurationProperties(prefix = "service")
            class SpringIssues {
                @NotBlank String endpoint;
                @Autowired
                private Client client;

                @Transactional
                private void update() {}

                @GetMapping("/search")
                String search(@RequestBody String query) { return query; }

                interface Client {}
            }
            """);

        List<ObjectNode> diagnostics = new SpringBootRuleProvider()
            .analyze(List.of(source)).get(source.toRealPath());
        Set<String> rules = diagnostics.stream()
            .map(node -> node.path("ruleId").asText())
            .collect(Collectors.toSet());

        assertEquals(Set.of("SpringBoot/FieldInjection", "SpringBoot/ConfigurationValidation",
            "SpringBoot/PrivateTransactionalMethod", "SpringBoot/GetMappingRequestBody"), rules,
            diagnostics.toString());
        assertTrue(diagnostics.stream().allMatch(node -> "SpringBoot".equals(node.path("source").asText())));
    }

    @Test
    void acceptsConstructorInjectionValidatedConfigurationAndNonGetBodyApis() throws Exception {
        Path source = Files.writeString(temporaryDirectory.resolve("SpringClean.java"), """
            package sample;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            import org.springframework.validation.annotation.Validated;
            import org.springframework.web.bind.annotation.PostMapping;
            import jakarta.validation.constraints.NotBlank;

            @Validated
            @ConfigurationProperties(prefix = "service")
            class SpringClean {
                @NotBlank String endpoint;
                private final Client client;
                SpringClean(Client client) { this.client = client; }

                @PostMapping("/search")
                String search(String query) { return query; }

                interface Client {}
            }
            """);

        List<ObjectNode> diagnostics = new SpringBootRuleProvider()
            .analyze(List.of(source)).get(source.toRealPath());

        assertEquals(List.of(), diagnostics);
    }
}
