package io.github.krandalf75.javafix;

import io.github.krandalf75.javafix.analysis.InlineLocalRepair;
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

class InlineLocalRepairTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesPrimitiveAssignmentConversionWhileInliningBeforeReturn() throws Exception {
        String before = """
            public class InlinedPrimitive {
                public Object value() {
                    long result = 1;
                    return result;
                }
            }
            """;

        String after = InlineLocalRepair.rewriteAtLine(before, 3).orElseThrow();

        assertFalse(after.contains("long result"));
        assertTrue(after.contains("return (long) 1;") || after.contains("return (long)1;"), after);
        assertEquals(Long.class, invokeValue("InlinedPrimitive", before).getClass());
        assertEquals(Long.class, invokeValue("InlinedPrimitive", after).getClass());
    }

    @Test
    void preservesBoxedLongReturnBehavior() throws Exception {
        String before = """
            public class InlinedBoxedLong {
                public Object value() {
                    Long result = 1000L;
                    return result;
                }
            }
            """;
        String after = InlineLocalRepair.rewriteAtLine(before, 3).orElseThrow();
        Object beforeValue = invokeValue("InlinedBoxedLong", before);
        Object afterValue = invokeValue("InlinedBoxedLong", after);
        assertEquals(Long.class, beforeValue.getClass());
        assertEquals(beforeValue, afterValue);
        assertTrue(after.contains("return (Long) 1000L;") || after.contains("return (Long)1000L;"), after);
    }

    @Test
    void rejectsOtherUsesNonAdjacentReturnsVarAndComments() throws Exception {
        assertRejected("""
            class OtherUse {
                int value() {
                    int result = 1;
                    System.out.println(result);
                    return result;
                }
            }
            """);
        assertRejected("""
            class NotAdjacent {
                int value() {
                    int result = 1;
                    int other = 2;
                    return result;
                }
            }
            """);
        assertRejected("""
            class Inferred {
                Object value() {
                    var result = 1;
                    return result;
                }
            }
            """);
        assertRejected("""
            class Commented {
                int value() {
                    int result = /* important */ 1;
                    return result;
                }
            }
            """);
        assertRejected("""
            class ThrowInsteadOfReturn {
                int value() {
                    int result = 1;
                    throw new IllegalStateException();
                }
            }
            """);
        assertRejected("""
            class YieldInsteadOfReturn {
                int value(int selector) {
                    return switch (selector) {
                        case 0 -> {
                            int result = 1;
                            yield result;
                        }
                        default -> 0;
                    };
                }
            }
            """);
        assertRejected("""
            class TwoCandidatesOnOneLine {
                int first() { int result = 1; return result; } int second() { int result = 2; return result; }
            }
            """);
        assertRejected("""
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE_USE)
            @interface Marker {}
            class AnnotatedLocal {
                Object value() {
                    @Marker Long result = 1000L;
                    return result;
                }
            }
            """);
    }

    private void assertRejected(String source) throws Exception {
        int declarationLine = 1;
        String[] lines = source.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains("result =")) {
                declarationLine = index + 1;
                break;
            }
        }
        Optional<String> transformed = InlineLocalRepair.rewriteAtLine(source, declarationLine);
        assertTrue(transformed.isEmpty(), transformed.orElse(source));
    }

    private Object invokeValue(String className, String source) throws Exception {
        Path sourceFile = temporaryDirectory.resolve(className + ".java");
        Path classes = Files.createDirectories(temporaryDirectory.resolve(className + "-classes"));
        Files.writeString(sourceFile, source.replace("InlinedPrimitive", className));
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, "Fixture must compile:\n" + source);
        try (URLClassLoader loader = new URLClassLoader(
            new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            return loader.loadClass(className).getDeclaredMethod("value").invoke(
                loader.loadClass(className).getDeclaredConstructor().newInstance());
        }
    }
}
