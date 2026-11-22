package io.github.krandalf75.javafix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MainTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir
    Path temporaryDirectory;

    @Test
    void suggestsRemovingUnusedImports() {
        assertTrue(Main.resolutionText("The import java.util.List is never used").contains("import"));
    }

    @Test
    void applyCanBeBoundToTheExactReviewedPreview() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("preview-binding"));
        String hashBefore = "a".repeat(64);
        String hashAfter = "b".repeat(64);
        ObjectNode preview = JSON.createObjectNode().put("schemaVersion", "1").put("status", "preview");
        preview.putArray("repairs").addObject().put("id", "repair-1");
        preview.putArray("changes").addObject().put("path", "src/Sample.java")
            .put("beforeHash", hashBefore).put("afterHash", hashAfter);
        Path previewFile = project.resolve("preview.json");
        JSON.writeValue(previewFile.toFile(), preview);
        ObjectNode generated = JSON.createObjectNode().put("schemaVersion", "1").put("status", "applied");
        generated.putArray("changes").addObject().put("path", "src/Sample.java")
            .put("beforeHash", hashBefore).put("afterHash", hashAfter);

        Main.verifyExpectedPreview(project, previewFile, generated, java.util.Set.of("repair-1"));
        assertThrows(IllegalArgumentException.class, () ->
            Main.verifyExpectedPreview(project, previewFile, generated, java.util.Set.of("different-id")));

        ((ObjectNode) generated.path("changes").get(0)).put("afterHash", "c".repeat(64));
        assertThrows(IllegalArgumentException.class, () ->
            Main.verifyExpectedPreview(project, previewFile, generated, java.util.Set.of("repair-1")));
        ObjectNode duplicatePath = preview.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) duplicatePath.path("changes"))
            .add(duplicatePath.path("changes").get(0).deepCopy());
        Path duplicatePreview = project.resolve("duplicate-preview.json");
        JSON.writeValue(duplicatePreview.toFile(), duplicatePath);
        assertThrows(IllegalArgumentException.class, () ->
            Main.verifyExpectedPreview(project, duplicatePreview, generated,
                java.util.Set.of("repair-1")));
        ObjectNode incomplete = preview.deepCopy();
        incomplete.put("status", "incomplete");
        Path incompletePreview = project.resolve("incomplete-preview.json");
        JSON.writeValue(incompletePreview.toFile(), incomplete);
        assertThrows(IllegalArgumentException.class, () ->
            Main.verifyExpectedPreview(project, incompletePreview, generated,
                java.util.Set.of("repair-1")));
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{
            "repair", "--project", project.toString(), "--plan", previewFile.toString(),
            "--expect-preview", previewFile.toString()}));
    }

    @Test
    void sourceLevelDetectionForSyntaxRepairsRequiresExplicitBuildMetadata() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("source-level"));
        assertEquals(0, Main.configuredJavaSourceLevel(project));
        Files.writeString(project.resolve("pom.xml"), """
            <project><properties><!-- <maven.compiler.release>25</maven.compiler.release> --></properties></project>
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(project));
        Files.writeString(project.resolve("pom.xml"), """
            <project><properties><maven.compiler.release>8</maven.compiler.release></properties></project>
            """);
        assertEquals(8, Main.configuredJavaSourceLevel(project));
        Files.writeString(project.resolve("pom.xml"), """
            <project><properties>
              <maven.compiler.release>${java.version}</maven.compiler.release>
              <maven.compiler.source>8</maven.compiler.source>
            </properties></project>
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(project),
            "An unresolved configured level must disable syntax-sensitive repairs.");
        Files.delete(project.resolve("pom.xml"));
        Files.writeString(project.resolve("build.gradle"), """
            plugins { id 'java' }
            // sourceCompatibility = JavaVersion.VERSION_25
            java {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            """);
        assertEquals(17, Main.configuredJavaSourceLevel(project));
        Files.writeString(project.resolve("build.gradle"), """
            plugins { id 'java' }
            java {
                sourceCompatibility = javaVersion
                targetCompatibility = JavaVersion.VERSION_17
            }
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(project),
            "A symbolic Gradle level must not be ignored in favor of a numeric declaration.");
    }

    @Test
    void sourceLevelDetectionIsScopedToTheTargetBuildAndMavenPluginOverrides() throws Exception {
        Path gradleRoot = Files.createDirectory(temporaryDirectory.resolve("multi-gradle"));
        Files.writeString(gradleRoot.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            """);
        Path subproject = Files.createDirectories(gradleRoot.resolve("sub"));
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = javaVersion
            targetCompatibility = JavaVersion.VERSION_17
            """);
        Path gradleTarget = Files.createDirectories(subproject.resolve("src/main/java")).resolve("Sample.java");
        Files.writeString(gradleTarget, "class Sample {}");
        assertEquals(0, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "An unresolved subproject source level must disable the repair for that source.");
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
            """);
        assertEquals(8, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "A numeric subproject level must constrain the root level.");
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins { id 'java' }
            java { sourceCompatibility = JavaVersion.VERSION_1_6; targetCompatibility = JavaVersion.VERSION_1_6 }
            """);
        assertEquals(6, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "Inline older subproject assignments must not be hidden by a numeric root level.");
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins { id 'java' }
            java { options.release = 6 }
            """);
        assertEquals(6, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "Inline release overrides must constrain the subproject level.");
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            apply from: 'compiler-settings.gradle'
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "An applied build script with unknown compiler settings must disable the repair.");
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_17
            tasks.withType(JavaCompile).configureEach {
                options.compilerArgs += ['-source', '6', '-target', '6']
            }
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "Compiler arguments may override source compatibility and must fail closed.");
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_17
            tasks.withType(JavaCompile).configureEach {
                options.compilerArgumentProviders.add(new CommandLineArgumentProvider() {
                    Iterable<String> asArguments() { return ['-source', '6', '-target', '6'] }
                })
            }
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "Compiler argument providers may override source compatibility and must fail closed.");
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_17
            tasks.withType(JavaCompile).configureEach {
                options.setRelease(6)
            }
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "setRelease may override source compatibility and must fail closed.");
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            tasks.withType(JavaCompile).configureEach {
                setSourceCompatibility('1.6')
                setTargetCompatibility('1.6')
            }
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "JavaCompile compatibility setter calls may override declared compatibility and must fail closed.");
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            tasks.withType(JavaCompile).configureEach {
                setSourceCompatibility '1.6'
                setTargetCompatibility '1.6'
            }
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "Groovy parenthesis-free compatibility setter calls must fail closed.");
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_17
            java { toolchain { languageVersion = JavaLanguageVersion.of(6) } }
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "A toolchain language version may set the effective source level and must fail closed.");
        Files.writeString(subproject.resolve("build.gradle"), """
            plugins {
                id 'java'
                id 'com.example.java-conventions'
            }
            sourceCompatibility = JavaVersion.VERSION_17
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "An external convention plugin can override compiler settings and must fail closed.");
        Files.writeString(subproject.resolve("build.gradle"), """
            buildscript {
                dependencies { classpath 'com.example:java-conventions:1.0' }
            }
            def conventions = 'com.example.java-conventions'
            apply plugin: conventions
            sourceCompatibility = JavaVersion.VERSION_17
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "A variable-based convention plugin application must fail closed.");
        Files.writeString(subproject.resolve("build.gradle"), """
            def conventions = 'com.example.java-conventions'
            apply(plugin: conventions)
            sourceCompatibility = JavaVersion.VERSION_17
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(gradleRoot, gradleTarget),
            "A Groovy named-argument plugin application must fail closed when unresolved.");

        Path initScriptProject = Files.createDirectory(temporaryDirectory.resolve("gradle-init-script-project"));
        Files.writeString(initScriptProject.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            """);
        Path initTarget = Files.createDirectories(initScriptProject.resolve("src/main/java"))
            .resolve("Sample.java");
        Files.writeString(initTarget, "class Sample {}");
        Path gradleUserHome = Files.createDirectory(temporaryDirectory.resolve("gradle-user-home"));
        assertEquals(17, Main.configuredJavaSourceLevel(initScriptProject, initTarget, gradleUserHome));
        Path initDirectory = Files.createDirectories(gradleUserHome.resolve("init.d"));
        Files.writeString(initDirectory.resolve("compiler-policy.gradle"), """
            allprojects {
                tasks.withType(JavaCompile).configureEach {
                    sourceCompatibility = '1.6'
                }
            }
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(initScriptProject, initTarget, gradleUserHome),
            "An init script in Gradle user home may override project source levels and must fail closed.");
        Path settingsProject = Files.createDirectory(temporaryDirectory.resolve("gradle-settings-script-project"));
        Files.writeString(settingsProject.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            """);
        Files.writeString(settingsProject.resolve("settings.gradle"), """
            gradle.beforeProject { project ->
                project.plugins.withId('java') {
                    project.tasks.withType(JavaCompile).configureEach {
                        sourceCompatibility = '1.6'
                        targetCompatibility = '1.6'
                    }
                }
            }
            """);
        Path settingsTarget = Files.createDirectories(settingsProject.resolve("src/main/java"))
            .resolve("Sample.java");
        Files.writeString(settingsTarget, "class Sample {}");
        assertEquals(0, Main.configuredJavaSourceLevel(settingsProject, settingsTarget, gradleUserHome),
            "An uninspected Gradle settings script may change the effective compiler level and must fail closed.");
        Path multiProjectRoot = Files.createDirectory(temporaryDirectory.resolve("gradle-settings-root"));
        Files.writeString(multiProjectRoot.resolve("settings.gradle"), """
            gradle.beforeProject { project ->
                project.plugins.withId('java') {
                    project.tasks.withType(JavaCompile).configureEach {
                        sourceCompatibility = '1.6'
                        targetCompatibility = '1.6'
                    }
                }
            }
            include 'module'
            """);
        Path module = Files.createDirectory(multiProjectRoot.resolve("module"));
        Files.writeString(module.resolve("build.gradle"), """
            plugins { id 'java' }
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            """);
        Path moduleTarget = Files.createDirectories(module.resolve("src/main/java")).resolve("Sample.java");
        Files.writeString(moduleTarget, "class Sample {}");
        assertEquals(0, Main.configuredJavaSourceLevel(multiProjectRoot, moduleTarget, gradleUserHome),
            "A root settings script must be inspected even when the root has no build.gradle.");

        Path mavenRoot = Files.createDirectory(temporaryDirectory.resolve("multi-maven"));
        Files.writeString(mavenRoot.resolve("pom.xml"), """
            <project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>
            """);
        Path mavenModule = Files.createDirectories(mavenRoot.resolve("module"));
        Files.writeString(mavenModule.resolve("pom.xml"), """
            <project><build><plugins><plugin>
              <artifactId>maven-compiler-plugin</artifactId>
              <configuration><release>${module.release}</release></configuration>
            </plugin></plugins></build></project>
            """);
        Path mavenTarget = Files.createDirectories(mavenModule.resolve("src/main/java"))
            .resolve("Sample.java");
        Files.writeString(mavenTarget, "class Sample {}");
        assertEquals(0, Main.configuredJavaSourceLevel(mavenRoot, mavenTarget),
            "An unresolved compiler-plugin override must disable the repair.");
        Files.writeString(mavenModule.resolve("pom.xml"), """
            <project><build><plugins><plugin>
              <artifactId>maven-compiler-plugin</artifactId>
              <configuration><release>8</release></configuration>
            </plugin></plugins></build></project>
            """);
        assertEquals(8, Main.configuredJavaSourceLevel(mavenRoot, mavenTarget),
            "A numeric module compiler override must constrain the target level.");
        Files.writeString(mavenModule.resolve("pom.xml"), """
            <project><build><plugins><plugin>
              <artifactId>maven-compiler-plugin</artifactId>
              <configuration>
                <compilerArgs><arg>--release</arg><arg>6</arg></compilerArgs>
              </configuration>
            </plugin></plugins></build></project>
            """);
        assertEquals(0, Main.configuredJavaSourceLevel(mavenRoot, mavenTarget),
            "Compiler arguments can override the configured Java level and must fail closed.");
        Files.writeString(mavenModule.resolve("pom.xml"), """
            <project><properties>
              <maven.compiler.testSource>1.6</maven.compiler.testSource>
              <maven.compiler.testTarget>1.6</maven.compiler.testTarget>
            </properties></project>
            """);
        Path mavenTestTarget = Files.createDirectories(mavenModule.resolve("src/test/java"))
            .resolve("SampleTest.java");
        Files.writeString(mavenTestTarget, "class SampleTest {}");
        assertEquals(6, Main.configuredJavaSourceLevel(mavenRoot, mavenTestTarget),
            "Test-compile source levels must be included in the target gate.");
        Files.writeString(mavenModule.resolve("pom.xml"), """
            <project><build><plugins><plugin>
              <artifactId>maven-compiler-plugin</artifactId>
              <configuration><testRelease>6</testRelease></configuration>
            </plugin></plugins></build></project>
            """);
        assertEquals(6, Main.configuredJavaSourceLevel(mavenRoot, mavenTestTarget),
            "A test-specific compiler plugin release must constrain the level.");

        Path inheritedParent = Files.createDirectory(temporaryDirectory.resolve("inherited-parent"));
        Files.writeString(inheritedParent.resolve("pom.xml"), """
            <project><build><plugins><plugin>
              <artifactId>maven-compiler-plugin</artifactId>
              <configuration><release>6</release></configuration>
            </plugin></plugins></build></project>
            """);
        Path selectedModule = Files.createDirectories(inheritedParent.resolve("selected-module"));
        Files.writeString(selectedModule.resolve("pom.xml"), """
            <project>
              <parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></parent>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
            """);
        Path inheritedTarget = Files.createDirectories(selectedModule.resolve("src/main/java"))
            .resolve("Sample.java");
        Files.writeString(inheritedTarget, "class Sample {}");
        assertEquals(0, Main.configuredJavaSourceLevel(selectedModule, inheritedTarget),
            "An unexamined Maven parent may contribute compiler settings and must fail closed.");

        Path mavenCliProject = Files.createDirectory(temporaryDirectory.resolve("maven-cli-override"));
        Files.writeString(mavenCliProject.resolve("pom.xml"), """
            <project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>
            """);
        Path mavenConfig = Files.createDirectories(mavenCliProject.resolve(".mvn"));
        Files.writeString(mavenConfig.resolve("maven.config"), "-Dmaven.compiler.release=6\n");
        Path cliTarget = Files.createDirectories(mavenCliProject.resolve("src/main/java"))
            .resolve("Sample.java");
        Files.writeString(cliTarget, "class Sample {}");
        assertEquals(0, Main.configuredJavaSourceLevel(mavenCliProject, cliTarget),
            "A persisted Maven user-property override must disable the repair.");
        Files.writeString(mavenConfig.resolve("maven.config"), "--define=maven.compiler.release=6\n");
        assertEquals(0, Main.configuredJavaSourceLevel(mavenCliProject, cliTarget),
            "The Maven --define long option must be recognized as a compiler override.");
        Files.writeString(mavenConfig.resolve("jvm.config"), "-Dmaven.compiler.release=6\n");
        assertEquals(0, Main.configuredJavaSourceLevel(mavenCliProject, cliTarget),
            "A project Maven JVM option must be recognized as a compiler override.");
    }

    @Test
    void suggestsClosingLeakedResources() {
        assertTrue(Main.resolutionText("Resource leak: 'stream' is never closed").contains("try-with-resources"));
    }

    @Test
    void givesNullSafetyGuidance() {
        assertTrue(Main.resolutionText("Potential null pointer access").contains("Comprueba"));
    }

    @Test
    void convertsLspZeroBasedPositionsToOneBasedRanges() throws Exception {
        JsonNode lsp = JSON.readTree("""
            {"start":{"line":4,"character":9},"end":{"line":4,"character":13}}
            """);

        JsonNode range = Main.oneBasedRange(lsp);

        assertEquals(5, range.path("start").path("line").asInt());
        assertEquals(10, range.path("start").path("column").asInt());
        assertEquals(true, range.path("endExclusive").asBoolean());
    }

    @Test
    void repairCommandRequiresExplicitApplyAndAcceptsVersionedPlan() {
        Main.Options preview = Main.Options.parse(new String[]{
            "repair", "--project", temporaryDirectory.toString(), "--plan", "repairs.json"});
        assertFalse(preview.apply);
        assertEquals(Path.of("repairs.json"), preview.plan);

        Main.Options apply = Main.Options.parse(new String[]{
            "repair", "--project", temporaryDirectory.toString(), "--plan", "repairs.json", "--apply"});
        assertTrue(apply.apply);
    }

    @Test
    void validatesRepairPlansAgainstTheVersionOneContract() throws Exception {
        Main.validateRepairPlan(JSON.readTree("""
            {"schemaVersion":"1","repairs":[{"id":"repair-1","parameters":{}}]}
            """));

        for (String invalidPlan : List.of(
            """
            {"schemaVersion":"1","repairs":["repair-1"],"unexpected":true}
            """,
            """
            {"schemaVersion":"1","repairs":[{"id":"repair-1","unexpected":true}]}
            """,
            """
            {"schemaVersion":"1","repairs":[{"id":"repair-1","parameters":{"unknown":"LIMIT"}}]}
            """,
            """
            {"schemaVersion":"1","repairs":[{"id":"repair-1","parameters":{"constantName":17}}]}
            """,
            """
            {"schemaVersion":"1","repairs":[42]}
            """,
            """
            {"schemaVersion":"1","repairs":[{"id":42}]}
            """
        )) {
            assertThrows(IllegalArgumentException.class,
                () -> Main.validateRepairPlan(JSON.readTree(invalidPlan)), invalidPlan);
        }
    }

    @Test
    void convertsLspOffsetsUsingUtf16Positions() throws Exception {
        String source = "first\r\n🧪value";
        JsonNode position = JSON.readTree("{\"line\":1,\"character\":2}");
        assertEquals(source.indexOf("value"), Main.offset(source, position));
    }

    @Test
    void repairIdsBecomeStaleWhenSourceChanges() throws Exception {
        JsonNode range = JSON.readTree("{\"start\":{\"line\":0,\"character\":0}}");
        JsonNode edit = JSON.readTree("{\"changes\":{}}");
        String before = Main.repairId("file:///A.java", "Fix", range, edit,
            "class A {}".getBytes(StandardCharsets.UTF_8));
        String after = Main.repairId("file:///A.java", "Fix", range, edit,
            "class A { }".getBytes(StandardCharsets.UTF_8));
        assertFalse(before.equals(after));
    }

    @Test
    void classifiesResolvedPersistentAndNewFindingsAcrossRangeShifts() throws Exception {
        JsonNode before = JSON.readTree("""
            {"status":"completed","files":[{"path":"A.java","diagnostics":[
              {"source":"PMD","ruleId":"PMD/Old","code":"Old","message":"gone",
               "range":{"start":{"line":2,"column":4}}},
              {"source":"Java","code":"42","message":"still here",
               "range":{"start":{"line":3,"column":8}}}
            ]}]}
            """);
        JsonNode after = JSON.readTree("""
            {"status":"completed","files":[{"path":"A.java","diagnostics":[
              {"source":"Java","code":"42","message":"still here",
               "range":{"start":{"line":9,"column":8}}},
              {"source":"PMD","ruleId":"PMD/New","code":"New","message":"new",
               "range":{"start":{"line":10,"column":1}}}
            ]}]}
            """);

        JsonNode changes = Main.compareDiagnostics(before, after);

        assertEquals(1, changes.path("resolved").size());
        assertEquals("PMD/Old", changes.path("resolved").path(0).path("ruleId").asText());
        assertEquals(1, changes.path("persistent").size());
        assertEquals(9, changes.path("persistent").path(0).path("range").path("start").path("line").asInt());
        assertEquals(1, changes.path("new").size());
        assertEquals("PMD/New", changes.path("new").path(0).path("ruleId").asText());
        assertTrue(changes.path("complete").asBoolean());
    }

    @Test
    void appliesValidatedWorkspaceEditAndPreservesUntouchedBytes() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("repair-project"));
        Path source = Files.writeString(root.resolve("A.java"), "class A {}");
        JsonNode edit = JSON.readTree("""
            {"changes":{"%s":[{"range":{"start":{"line":0,"character":8},
            "end":{"line":0,"character":10}},"newText":"{ int x; }"}]}}
            """.formatted(source.toUri()));
        Map<Path, byte[]> original = new HashMap<>();
        Map<Path, String> updated = new HashMap<>();

        Main.collectWorkspaceEdits(root, edit, original, updated);
        assertEquals("class A { int x; }", updated.get(source.toRealPath()));
        Main.writeTransaction(original, updated);

        assertEquals("class A { int x; }", Files.readString(source));
    }

    @Test
    void rejectsOverlappingWorkspaceEditsBeforeWriting() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("conflict-project"));
        Path source = Files.writeString(root.resolve("A.java"), "class A {}");
        JsonNode edit = JSON.readTree("""
            {"changes":{"%s":[
              {"range":{"start":{"line":0,"character":6},"end":{"line":0,"character":9}},"newText":"B"},
              {"range":{"start":{"line":0,"character":8},"end":{"line":0,"character":10}},"newText":"{}"}
            ]}}
            """.formatted(source.toUri()));
        assertThrows(IllegalArgumentException.class,
            () -> Main.collectWorkspaceEdits(root, edit, new HashMap<>(), new HashMap<>()));
        assertEquals("class A {}", Files.readString(source));
    }

    @Test
    void cliRejectsOverlappingWorkspaceEditsBeforeWriting() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("cli-conflict-project"));
        Path source = Files.writeString(root.resolve("A.java"), "class A {}");
        String uri = source.toUri().toString();
        JsonNode firstEdit = JSON.readTree("""
            {"changes":{"%s":[{"range":{"start":{"line":0,"character":6},
            "end":{"line":0,"character":9}},"newText":"B"}]}}
            """.formatted(uri));
        JsonNode secondEdit = JSON.readTree("""
            {"changes":{"%s":[{"range":{"start":{"line":0,"character":8},
            "end":{"line":0,"character":10}},"newText":"{}"}]}}
            """.formatted(uri));
        ObjectNode report = JSON.createObjectNode().put("status", "completed");
        ObjectNode file = report.putArray("files").addObject().put("path", "A.java");
        ObjectNode diagnostic = file.putArray("diagnostics").addObject();
        var actions = diagnostic.putObject("resolution").putArray("jdtlsActions");
        actions.addObject().put("repairId", "first").set("workspaceEdit", firstEdit);
        actions.addObject().put("repairId", "second").set("workspaceEdit", secondEdit);
        Path plan = root.resolve("repairs.json");
        JSON.writeValue(plan.toFile(), JSON.createObjectNode().put("schemaVersion", "1")
            .set("repairs", JSON.createArrayNode().add("first").add("second")));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream output = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream error = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = Main.run(new String[]{"repair", "--project", root.toString(),
                "--plan", plan.toString(), "--apply"}, output, error,
                ignored -> report.deepCopy());
        }

        assertEquals(2, exitCode);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Ediciones solapadas"),
            stderr.toString(StandardCharsets.UTF_8));
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("class A {}", Files.readString(source),
            "The CLI must reject overlapping edits before changing source bytes");
    }

    @Test
    void preservesUtf8BomCrLfAndSupplementaryCharactersDuringMultilineEdit() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("encoding-project"));
        Path sourceFile = root.resolve("A.java");
        String before = "\uFEFFclass A {\r\n  String emoji = \"🧪\";\r\n}\r\n";
        Files.writeString(sourceFile, before, StandardCharsets.UTF_8);
        String uri = sourceFile.toUri().toString();
        JsonNode edit = JSON.readTree("""
            {"changes":{"%s":[{"range":{"start":{"line":2,"character":0},
            "end":{"line":2,"character":0}},"newText":"  int added;\\r\\n"}]}}
            """.formatted(uri));
        Map<Path, byte[]> original = new HashMap<>();
        Map<Path, String> updated = new HashMap<>();

        Main.collectWorkspaceEdits(root, edit, original, updated);
        Main.writeTransaction(original, updated);

        assertEquals("\uFEFFclass A {\r\n  String emoji = \"🧪\";\r\n  int added;\r\n}\r\n",
            Files.readString(sourceFile, StandardCharsets.UTF_8));
        assertTrue(java.util.Arrays.equals(updated.get(sourceFile.toRealPath()).getBytes(StandardCharsets.UTF_8),
            Files.readAllBytes(sourceFile)));
    }

    @Test
    void rejectsMalformedUtf8WithoutChangingSourceBytes() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("invalid-encoding-project"));
        Path sourceFile = root.resolve("Invalid.java");
        byte[] invalidUtf8 = new byte[]{'c', 'l', 'a', 's', 's', ' ', (byte) 0xC3, 0x28};
        Files.write(sourceFile, invalidUtf8);
        JsonNode edit = JSON.readTree("""
            {"changes":{"%s":[{"range":{"start":{"line":0,"character":0},
            "end":{"line":0,"character":1}},"newText":"C"}]}}
            """.formatted(sourceFile.toUri()));

        assertThrows(IllegalArgumentException.class,
            () -> Main.collectWorkspaceEdits(root, edit, new HashMap<>(), new HashMap<>()));
        assertTrue(java.util.Arrays.equals(invalidUtf8, Files.readAllBytes(sourceFile)));
    }

    @Test
    void rollbackDoesNotOverwriteExternalChangesDetectedDuringMultiFileWrite() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("concurrent-write-project"));
        Path first = Files.writeString(root.resolve("A.java"), "class A {}");
        Path second = Files.writeString(root.resolve("B.java"), "class B {}");
        Map<Path, byte[]> original = Map.of(
            first.toRealPath(), Files.readAllBytes(first),
            second.toRealPath(), Files.readAllBytes(second));
        Map<Path, String> updated = Map.of(
            first.toRealPath(), "class A { int changed; }",
            second.toRealPath(), "class B { int changed; }");

        assertThrows(IOException.class, () -> Main.writeTransaction(original, updated, path -> {
            if (path.equals(second.toRealPath())) {
                Files.writeString(first, "external edit A");
                Files.writeString(second, "external edit B");
            }
        }));

        assertEquals("external edit A", Files.readString(first),
            "Rollback must not overwrite an external edit made after JavaFix wrote the file");
        assertEquals("external edit B", Files.readString(second),
            "The externally changed file that triggered the conflict must remain untouched");
    }

    @Test
    void cancellationDuringMultiFileWriteRollsBackCompletedWrites() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("cancelled-write-project"));
        Path first = Files.writeString(root.resolve("A.java"), "class A {}");
        Path second = Files.writeString(root.resolve("B.java"), "class B {}");
        byte[] firstOriginal = Files.readAllBytes(first);
        byte[] secondOriginal = Files.readAllBytes(second);
        Map<Path, byte[]> original = Map.of(first.toRealPath(), firstOriginal, second.toRealPath(), secondOriginal);
        Map<Path, String> updated = Map.of(
            first.toRealPath(), "class A { int changed; }",
            second.toRealPath(), "class B { int changed; }");
        Path cancelFile = root.resolve("cancel.request");
        Main.CancellationToken cancellation = new Main.CancellationToken(cancelFile);

        assertThrows(java.util.concurrent.CancellationException.class,
            () -> Main.writeTransaction(original, updated, cancellation, ignored -> {}, path -> {
                if (path.equals(first.toRealPath())) Files.createFile(cancelFile);
            }));

        assertTrue(java.util.Arrays.equals(firstOriginal, Files.readAllBytes(first)));
        assertTrue(java.util.Arrays.equals(secondOriginal, Files.readAllBytes(second)));
    }

    @Test
    void cliReturnsCancellationErrorWhenCancelFileAlreadyExists() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("cancelled-cli-project"));
        Path cancelFile = Files.createFile(root.resolve("cancel.request"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream output = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream error = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = Main.run(new String[]{"analyze", "--project", root.toString(),
                "--cancel-file", cancelFile.toString()}, output, error,
                ignored -> fail("A pre-cancelled command must not invoke analysis"));
        }

        assertEquals(2, exitCode);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Operación cancelada"),
            stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void cancellationInterruptsPendingLspWorkAndClosesServerGracefully() throws Exception {
        Path cancelFile = temporaryDirectory.resolve("lsp.cancel");
        Main.CancellationToken cancellation = new Main.CancellationToken(cancelFile);
        ControlledLspProcess server = new ControlledLspProcess();
        Main.LspSession session = new Main.LspSession(server, cancellation);
        try {
            session.request("initialize", JSON.createObjectNode());
            Files.createFile(cancelFile);
            assertThrows(java.util.concurrent.CancellationException.class,
                () -> session.awaitDiagnostics(Map.of("file:///Pending.java", List.of()),
                    java.time.Duration.ZERO, java.time.Duration.ofSeconds(5)));
        } finally {
            session.close();
        }
        assertTrue(server.exited, "Cancellation cleanup should shut the LSP server down cleanly");
    }

    @Test
    void shipsVersionedCatalogForObservedJdtProblemCodes() throws Exception {
        try (InputStream catalog = Main.class.getResourceAsStream("/META-INF/jdtls-code-catalog.json")) {
            assertTrue(catalog != null);
            JsonNode entries = JSON.readTree(catalog).path("observedDiagnostics");
            assertEquals(4, entries.size());
            assertEquals("UnusedImport", entries.get(0).path("iProblem").asText());
            assertEquals("268435844", entries.get(0).path("code").asText());
        }
    }

    @Test
    void bundlesVersionedAgentSchemas() throws Exception {
        for (String name : List.of("analysis-v1.schema.json", "repair-plan-v1.schema.json",
            "repair-result-v1.schema.json")) {
            try (InputStream schema = Main.class.getResourceAsStream("/schema/" + name)) {
                assertTrue(schema != null, "Missing schema: " + name);
                assertEquals("https://json-schema.org/draft/2020-12/schema",
                    JSON.readTree(schema).path("$schema").asText());
            }
        }
    }

    @Test
    void onlyDirectEnabledQuickfixEditsAreAutomaticallyRepairable() {
        ObjectNode directQuickfix = JSON.createObjectNode().put("kind", "quickfix");
        directQuickfix.putObject("edit").putObject("changes");
        assertTrue(Main.isAutomaticallyRepairable(directQuickfix));

        ObjectNode refactor = JSON.createObjectNode().put("kind", "refactor.extract");
        refactor.putObject("edit").putObject("changes");
        assertFalse(Main.isAutomaticallyRepairable(refactor));

        ObjectNode disabled = JSON.createObjectNode().put("kind", "quickfix");
        disabled.putObject("edit").putObject("changes");
        disabled.putObject("disabled").put("reason", "Not applicable");
        assertFalse(Main.isAutomaticallyRepairable(disabled));

        ObjectNode suppression = JSON.createObjectNode().put("title", "SuppressWarnings quick fix")
            .put("kind", "quickfix");
        suppression.putObject("edit").putObject("changes");
        assertFalse(Main.isAutomaticallyRepairable(suppression));

        ObjectNode commandBased = JSON.createObjectNode().put("kind", "quickfix")
            .put("command", "java.action.rename");
        commandBased.putObject("edit").putObject("changes");
        assertFalse(Main.isAutomaticallyRepairable(commandBased));
    }

    @Test
    void lspSessionHandlesRequestsNotificationsAndDiagnosticsAgainstControlledServer() throws Exception {
        ControlledLspProcess server = new ControlledLspProcess();
        Main.LspSession session = new Main.LspSession(server);
        try {
            JsonNode initialized = session.request("initialize", JSON.createObjectNode());
            assertTrue(initialized.isObject());
            session.openDocument("file:///Fixture.java", "class Fixture {}");
            session.openDocument("file:///Second.java", "class Second {}");

            Map<String, List<JsonNode>> diagnostics = new HashMap<>();
            diagnostics.put("file:///Fixture.java", new java.util.ArrayList<>());
            diagnostics.put("file:///Second.java", new java.util.ArrayList<>());
            assertTrue(session.awaitDiagnostics(diagnostics, java.time.Duration.ofMillis(25),
                java.time.Duration.ofSeconds(2)));
            assertEquals(1, diagnostics.get("file:///Fixture.java").size());
            assertEquals("current warning",
                diagnostics.get("file:///Fixture.java").get(0).path("message").asText());
            assertEquals("second file warning",
                diagnostics.get("file:///Second.java").get(0).path("message").asText());
            Map<String, List<JsonNode>> incomplete = new HashMap<>();
            incomplete.put("file:///NeverPublished.java", new java.util.ArrayList<>());
            assertFalse(session.awaitDiagnostics(incomplete, java.time.Duration.ZERO,
                java.time.Duration.ofMillis(30)));

            JsonNode actions = session.request("textDocument/codeAction", JSON.createObjectNode());
            assertEquals("Remove unused import", actions.path(0).path("title").asText());
            ObjectNode diagnostic = JSON.createObjectNode();
            List<JsonNode> resolvedActions = session.codeActions("file:///Fixture.java", diagnostic);
            assertEquals(3, resolvedActions.size());
            assertEquals("Resolved action", resolvedActions.get(1).path("title").asText());
            assertTrue(resolvedActions.get(1).hasNonNull("edit"));
            JsonNode unresolved = resolvedActions.get(2);
            assertEquals("Deferred failure", unresolved.path("title").asText());
            assertTrue(unresolved.hasNonNull("resolveError"));
            assertFalse(Main.isAutomaticallyRepairable(unresolved));
            List<JsonNode> refactors = session.codeActions("file:///Fixture.java", diagnostic,
                List.of("refactor.inline"));
            assertEquals("refactor.inline", refactors.get(0).path("kind").asText());
            assertFalse(Main.isAutomaticallyRepairable(refactors.get(0)));
        } finally {
            session.close();
        }
        assertTrue(server.exited);
    }

    @Test
    void reportsUnexpectedJdtlsTerminationImmediately() throws Exception {
        ControlledLspProcess server = new ControlledLspProcess();
        Main.LspSession session = new Main.LspSession(server);
        server.destroy();

        assertThrows(IOException.class,
            () -> session.request("initialize", JSON.createObjectNode()));
        session.close();
    }

    @Test
    void returnsIncompleteWhenDiagnosticsTimeoutWithPendingFiles() throws Exception {
        ControlledLspProcess server = new ControlledLspProcess();
        Main.LspSession session = new Main.LspSession(server);
        try {
            session.request("initialize", JSON.createObjectNode());
            Map<String, List<JsonNode>> pending = Map.of("file:///NeverPublished.java", List.of());

            assertFalse(session.awaitDiagnostics(pending, java.time.Duration.ZERO,
                java.time.Duration.ofMillis(25)));
        } finally {
            session.close();
        }
    }

    @Test
    void discoversJavaFilesAndExcludesBuildOutputs() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("project"));
        Files.createDirectories(root.resolve("src/main/java/sample"));
        Files.createDirectories(root.resolve("target/generated"));
        Files.createDirectories(root.resolve("build/generated"));
        Files.createDirectories(root.resolve("bin/generated"));
        Files.writeString(root.resolve("src/main/java/sample/One.java"), "class One {}");
        Files.writeString(root.resolve("target/generated/Generated.java"), "class Generated {}");
        Files.writeString(root.resolve("build/generated/BuildOutput.java"), "class BuildOutput {}");
        Files.writeString(root.resolve("bin/generated/BinaryOutput.java"), "class BinaryOutput {}");

        List<Path> files = Main.resolveTargets(Main.Options.parse(new String[]{
            "analyze", "--project", root.toString()}), root);

        assertEquals(List.of(root.resolve("src/main/java/sample/One.java").toRealPath()), files);
    }

    @Test
    void explicitFileSelectionIsScopedSortedAndDeduplicated() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path first = Files.writeString(root.resolve("Z.java"), "class Z {}");
        Path second = Files.writeString(root.resolve("A.java"), "class A {}");

        Main.Options options = Main.Options.parse(new String[]{
            "analyze", "--project", root.toString(), "--files",
            first.toString(), second.toString(), first.toString()});

        assertEquals(List.of(second.toRealPath(), first.toRealPath()),
            Main.resolveTargets(options, root.toRealPath()));
    }

    @Test
    void infersProjectFromSourceAndLimitsImplicitSelectionToThatFile() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("maven-project"));
        Path source = root.resolve("src/main/java/sample/One.java");
        Files.createDirectories(source.getParent());
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(source, "class One {}");
        Files.writeString(source.resolveSibling("Two.java"), "class Two {}");

        Main.ProjectContext context = Main.resolveProject(source);
        List<Path> targets = Main.resolveTargets(new Main.Options(), context.root(),
            context.discoveryRoot(), context.inferredFile());

        assertEquals(root.toRealPath(), context.root());
        assertEquals(List.of(source.toRealPath()), targets);
    }

    @Test
    void rejectsAmbiguousDescriptorsAndJavaFileWithoutProjectContext() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("ambiguous"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("build.gradle"), "plugins {}");
        IllegalArgumentException ambiguous = assertThrows(IllegalArgumentException.class,
            () -> Main.resolveProject(root));
        assertTrue(ambiguous.getMessage().contains("--project"));

        Path standalone = Files.createDirectory(temporaryDirectory.resolve("standalone"));
        Path source = Files.writeString(standalone.resolve("Loose.java"), "class Loose {}");
        IllegalArgumentException noContext = assertThrows(IllegalArgumentException.class,
            () -> Main.resolveProject(source));
        assertTrue(noContext.getMessage().contains("No se encontró"));
    }

    @Test
    void handlesDirectoryWithoutSourcesAndUnknownDescriptor() throws Exception {
        Path empty = Files.createDirectory(temporaryDirectory.resolve("empty"));
        Main.ProjectContext context = Main.resolveProject(empty);
        assertTrue(Main.resolveTargets(new Main.Options(), context.root(),
            context.discoveryRoot(), context.inferredFile()).isEmpty());

        Path unknown = Files.writeString(empty.resolve("settings.xml"), "<settings/>");
        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
            () -> Main.resolveProject(unknown));
        assertTrue(unsupported.getMessage().contains("Descriptor no compatible"));
    }

    @Test
    void supportsPathsContainingSpaces() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("project with spaces"));
        Path source = Files.writeString(root.resolve("A file.java"), "class A {}");
        Main.ProjectContext context = Main.resolveProject(root);
        Main.Options options = Main.Options.parse(new String[]{
            "analyze", "--project", root.toString(), "--files", source.toString()});

        assertEquals(List.of(source.toRealPath()), Main.resolveTargets(options, context.root()));
    }

    @Test
    void skipsSymlinksEscapingProjectAndRejectsThemWhenExplicit() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("symlink-project"));
        Path outside = Files.writeString(temporaryDirectory.resolve("Outside.java"), "class Outside {}");
        Path link = root.resolve("Linked.java");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable on this runner: " + unavailable);
        }

        List<Path> discovered = Main.resolveTargets(new Main.Options(), root.toRealPath());
        assertFalse(discovered.contains(outside.toRealPath()));
        Main.Options explicit = Main.Options.parse(new String[]{
            "analyze", "--project", root.toString(), "--files", link.toString()});
        assertThrows(IllegalArgumentException.class,
            () -> Main.resolveTargets(explicit, root.toRealPath()));
    }

    @Test
    void rejectsFilesOutsideProjectAndNonJavaInputs() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path source = Files.writeString(root.resolve("One.java"), "class One {}");
        Path outside = Files.writeString(temporaryDirectory.resolve("Outside.java"), "class Outside {}");

        Main.Options outsideOptions = Main.Options.parse(new String[]{
            "analyze", "--project", root.toString(), "--files", outside.toString()});
        assertThrows(IllegalArgumentException.class, () -> Main.resolveTargets(outsideOptions, root));

        Path text = Files.writeString(root.resolve("One.txt"), "not Java");
        Main.Options textOptions = Main.Options.parse(new String[]{
            "analyze", "--project", root.toString(), "--files", text.toString()});
        assertThrows(IllegalArgumentException.class, () -> Main.resolveTargets(textOptions, root));
    }

    private static final class ControlledLspProcess extends Process {
        private final java.io.PipedInputStream clientInput = new java.io.PipedInputStream(64 * 1024);
        private final java.io.PipedOutputStream serverOutput;
        private final java.io.PipedInputStream serverInput = new java.io.PipedInputStream(64 * 1024);
        private final java.io.PipedOutputStream clientOutput;
        private volatile boolean alive = true;
        private volatile boolean exited;

        ControlledLspProcess() throws IOException {
            serverOutput = new java.io.PipedOutputStream(clientInput);
            clientOutput = new java.io.PipedOutputStream(serverInput);
            Thread server = new Thread(this::serve, "test-controlled-lsp");
            server.setDaemon(true);
            server.start();
        }

        private void serve() {
            try (var input = new BufferedInputStream(serverInput);
                 var output = new BufferedOutputStream(serverOutput)) {
                while (alive) {
                    JsonNode message = readMessage(input);
                    if (message == null) return;
                    String method = message.path("method").asText();
                    JsonNode id = message.get("id");
                    if (id != null) {
                        JsonNode result;
                        if ("initialize".equals(method)) {
                            ObjectNode capabilities = JSON.createObjectNode();
                            capabilities.put("serverInfo", "controlled");
                            result = capabilities;
                        } else if ("textDocument/codeAction".equals(method)) {
                            var actions = JSON.createArrayNode();
                            if (message.path("params").path("context").path("only").isArray()) {
                                actions.addObject().put("title", "Inline local")
                                    .put("kind", "refactor.inline").putObject("edit").putObject("changes");
                            } else {
                                actions.addObject().put("title", "Remove unused import");
                                actions.addObject().put("title", "Deferred action").put("kind", "quickfix")
                                    .putObject("data").put("token", "deferred");
                                actions.addObject().put("title", "Deferred failure").put("kind", "quickfix")
                                    .putObject("data").put("token", "fail");
                            }
                            result = actions;
                        } else if ("codeAction/resolve".equals(method)) {
                            if ("fail".equals(message.path("params").path("data").path("token").asText())) {
                                result = null;
                            } else {
                                ObjectNode resolved = JSON.createObjectNode().put("title", "Resolved action")
                                    .put("kind", "quickfix");
                                resolved.putObject("edit").putObject("changes");
                                result = resolved;
                            }
                        } else {
                            result = JSON.nullNode();
                        }
                        ObjectNode response = JSON.createObjectNode();
                        response.put("jsonrpc", "2.0");
                        response.set("id", id);
                        if ("codeAction/resolve".equals(method)
                            && "fail".equals(message.path("params").path("data").path("token").asText())) {
                            response.putObject("error").put("code", -32602).put("message", "Cannot resolve");
                        } else {
                            response.set("result", result);
                        }
                        writeMessage(output, response);
                    } else if ("textDocument/didOpen".equals(method)) {
                        JsonNode document = message.path("params").path("textDocument");
                        String uri = document.path("uri").asText();
                        if (uri.endsWith("Fixture.java")) {
                            publish(output, uri, "stale warning");
                            publish(output, uri, "current warning");
                        } else {
                            publish(output, uri, "second file warning");
                        }
                    } else if ("exit".equals(method)) {
                        exited = true;
                        alive = false;
                    }
                }
            } catch (IOException ignored) {
                // Closing the test process closes the protocol pipes.
            }
        }

        private static void publish(OutputStream output, String uri, String message) throws IOException {
            ObjectNode diagnostic = JSON.createObjectNode();
            diagnostic.put("message", message);
            diagnostic.put("severity", 2);
            ObjectNode range = diagnostic.putObject("range");
            range.putObject("start").put("line", 0).put("character", 0);
            range.putObject("end").put("line", 0).put("character", 1);
            ObjectNode params = JSON.createObjectNode();
            params.put("uri", uri);
            params.set("diagnostics", JSON.createArrayNode().add(diagnostic));
            ObjectNode notification = JSON.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", "textDocument/publishDiagnostics");
            notification.set("params", params);
            writeMessage(output, notification);
        }

        private static JsonNode readMessage(InputStream input) throws IOException {
            StringBuilder headers = new StringBuilder();
            int matched = 0;
            while (matched < 4) {
                int value = input.read();
                if (value < 0) return null;
                headers.append((char) value);
                matched = switch (matched) {
                    case 0 -> value == '\r' ? 1 : 0;
                    case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                    case 2 -> value == '\r' ? 3 : 0;
                    default -> value == '\n' ? 4 : 0;
                };
            }
            int length = Integer.parseInt(headers.toString().split("(?i)content-length:")[1]
                .trim().split("\\r\\n")[0]);
            return JSON.readTree(input.readNBytes(length));
        }

        private static void writeMessage(OutputStream output, JsonNode message) throws IOException {
            byte[] body = JSON.writeValueAsBytes(message);
            output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }

        @Override public OutputStream getOutputStream() { return clientOutput; }
        @Override public InputStream getInputStream() { return clientInput; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException {
            while (alive) Thread.sleep(5);
            return 0;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (alive && System.nanoTime() < deadline) Thread.sleep(5);
            return !alive;
        }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException("server still running");
            return 0;
        }
        @Override public void destroy() { alive = false; closePipes(); }
        @Override public Process destroyForcibly() { destroy(); return this; }

        private void closePipes() {
            try { clientOutput.close(); } catch (IOException ignored) {}
            try { clientInput.close(); } catch (IOException ignored) {}
        }
    }
}
