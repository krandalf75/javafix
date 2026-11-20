package io.github.krandalf75.javafix;

import io.github.krandalf75.javafix.analysis.LambdaMethodReferenceRepair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaMethodReferenceRepairTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void convertsStaticallyQualifiedForwardingLambdaAndPreservesBehavior() throws Exception {
        String before = """
            public class StaticLambda {
                public int apply(int input) {
                    java.util.function.IntUnaryOperator operation = value -> Math.abs(value);
                    return operation.applyAsInt(input);
                }
            }
            """;
        String after = LambdaMethodReferenceRepair.rewriteAtOffset(before, "StaticLambda.java",
            before.indexOf("value ->")).orElseThrow();

        assertTrue(after.contains("Math::abs"), after);
        assertEquals(7, invokeApply("StaticLambda", before, -7));
        assertEquals(7, invokeApply("StaticLambda", after, -7));
    }

    @Test
    void rejectsInstanceReceiversAndNonForwardingArguments() throws Exception {
        String instanceReceiver = """
            class InstanceReceiver {
                java.util.function.Consumer<String> consumer =
                    value -> System.out.println(value);
            }
            """;
        assertTrue(LambdaMethodReferenceRepair.rewriteAtOffset(instanceReceiver,
            instanceReceiver.indexOf("value ->")).isEmpty());

        String reordered = """
            class Reordered {
                java.util.function.IntBinaryOperator operator =
                    (left, right) -> Math.max(right, left);
            }
            """;
        assertTrue(LambdaMethodReferenceRepair.rewriteAtOffset(reordered,
            reordered.indexOf("(left, right)")).isEmpty());

        String instanceCall = """
            class InstanceCall {
                java.util.function.Function<String, String> function =
                    value -> value.trim();
            }
            """;
        assertTrue(LambdaMethodReferenceRepair.rewriteAtOffset(instanceCall,
            instanceCall.indexOf("value ->")).isEmpty());
    }

    private int invokeApply(String className, String source, int value) throws Exception {
        Path sourceFile = temporaryDirectory.resolve(className + ".java");
        Path classes = Files.createDirectories(temporaryDirectory.resolve(className + "-classes"));
        Files.writeString(sourceFile, source);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, "Fixture must compile:\n" + source);
        try (URLClassLoader loader = new URLClassLoader(
            new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Object instance = loader.loadClass(className).getConstructor().newInstance();
            return (int) loader.loadClass(className).getMethod("apply", int.class).invoke(instance, value);
        }
    }
}
