package io.github.krandalf75.javafix;

import io.github.krandalf75.javafix.analysis.ExtractConstantRepair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractConstantRepairTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsRepeatedStringLiteralsAndPreservesRuntimeValue() throws Exception {
        String before = """
            public class RepeatedText {
                public String value() {
                    return "repeated";
                }
                public String other() {
                    return "repeated";
                }
            }
            """;
        int offset = before.indexOf("\"repeated\"");

        String after = ExtractConstantRepair.rewriteAtOffset(before, offset, "REPEATED_TEXT").orElseThrow();

        assertTrue(after.contains("private static final String REPEATED_TEXT = \"repeated\";"), after);
        assertEquals(3, occurrences(after, "REPEATED_TEXT"));
        assertEquals("repeated", invokeValue("RepeatedText", before));
        assertEquals("repeated", invokeValue("RepeatedText", after));
    }

    @Test
    void rejectsInvalidNamesCollisionsAndNonStringSelections() throws Exception {
        String source = """
            class Sample {
                int value() {
                    return 123;
                }
            }
            """;
        int offset = source.indexOf("123");
        assertFalse(ExtractConstantRepair.canExtractAtOffset(source, offset));
        assertTrue(ExtractConstantRepair.rewriteAtOffset(source, offset, "class").isEmpty());
        assertTrue(ExtractConstantRepair.rewriteAtOffset(source, offset, "123BAD").isEmpty());
        assertTrue(ExtractConstantRepair.isValidName("SAMPLE_CONSTANT"));
        assertTrue(ExtractConstantRepair.rewriteAtOffset(source, offset, "SAMPLE_CONSTANT").isEmpty());

        String withCollision = """
            class Sample {
                private static final String TEXT = "other";
                String value() {
                    return "repeated" + "repeated";
                }
            }
            """;
        assertTrue(ExtractConstantRepair.rewriteAtOffset(withCollision,
            withCollision.indexOf("\"repeated\""), "TEXT").isEmpty());
    }

    private static int occurrences(String text, String token) {
        return (text.length() - text.replace(token, "").length()) / token.length();
    }

    private String invokeValue(String className, String source) throws Exception {
        Path sourceFile = temporaryDirectory.resolve(className + ".java");
        Path classes = Files.createDirectories(temporaryDirectory.resolve(className + "-classes"));
        Files.writeString(sourceFile, source);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, "Fixture must compile:\n" + source);
        try (URLClassLoader loader = new URLClassLoader(
            new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var type = loader.loadClass(className);
            return (String) type.getDeclaredMethod("value").invoke(type.getConstructor().newInstance());
        }
    }
}
