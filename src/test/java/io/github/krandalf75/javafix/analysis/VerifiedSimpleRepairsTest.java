package io.github.krandalf75.javafix.analysis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.krandalf75.javafix.analysis.PmdRuleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

class VerifiedSimpleRepairsTest {
    @TempDir Path temporaryDirectory;

    @Test
    void sourceOffsetsUseUtf16AndRejectCrLfAndSurrogateSplits() {
        String source = "\tα😀\r\nnext\n";
        SourceEdit valid = new SourceEdit(1, 2, "b");
        var edit = valid.workspaceEdit("file:///Sample.java", source)
            .path("changes").path("file:///Sample.java").get(0);
        assertEquals(0, edit.path("range").path("start").path("line").asInt());
        assertEquals(1, edit.path("range").path("start").path("character").asInt());
        assertThrows(IllegalArgumentException.class,
            () -> new SourceEdit(5, 6, "x").workspaceEdit("file:///Sample.java", source));
        assertThrows(IllegalArgumentException.class,
            () -> new SourceEdit(3, 3, "x").workspaceEdit("file:///Sample.java", source));

        var parsed = AstSupport.parse(source, "Sample.java", false);
        assertEquals(1, AstSupport.offsetAt(parsed, source, 1, 2));
        assertEquals(-1, AstSupport.offsetAt(parsed, source, 1, 99));
        assertEquals(-1, AstSupport.offsetAt(parsed, source, 0, 1));
    }

    @Test
    void addsBracesToOneLocatedIfBodyAndDeclinesMultilineOrCommentedBodies() throws Exception {
        String source = """
            class Sample {
                void run(boolean ready) {
                    if (ready) execute();
                }
                void execute() {}
            }
            """;
        int[] bodyPosition = position(source, source.indexOf("execute();"));
        var edit = ControlStatementBracesRepair.rewrite(source, "Sample.java",
            bodyPosition[0], bodyPosition[1]).orElse(null);
        assertNotNull(edit, "body position was " + bodyPosition[0] + ":" + bodyPosition[1]);
        String changed = source.substring(0, edit.start()) + edit.replacement() + source.substring(edit.end());
        assertTrue(changed.contains("if (ready) { execute(); }"), changed);
        assertTrue(AstSupport.hasErrors(AstSupport.parse(changed, "Sample.java", false)) == false);

        String multiline = """
            class Sample {
                void run(boolean ready) {
                    if (ready) execute(
                        ready);
                }
                void execute(boolean value) {}
            }
            """;
        int[] multilinePosition = position(multiline, multiline.indexOf("execute("));
        assertTrue(ControlStatementBracesRepair.rewrite(multiline, "Sample.java",
            multilinePosition[0], multilinePosition[1]).isEmpty());
        String commented = """
            class Sample {
                void run(boolean b) {
                    if (b) execute(); // keep comment
                }
                void execute() {}
            }
            """;
        int[] commentPosition = position(commented, commented.indexOf("execute();"));
        assertTrue(ControlStatementBracesRepair.rewrite(commented, "Sample.java",
            commentPosition[0], commentPosition[1]).isEmpty());
    }

    @Test
    void collapsesReportedNestedIfWithShortCircuitingAndPreservesInnerBlock() throws Exception {
        String source = """
            class Sample {
                boolean outer;
                boolean inner() { return true; }
                void body() {}
                void run() {
                    if (outer || inner()) {
                        if (inner()) {
                            body();
                        }
                    }
                }
            }
            """;
        int[] innerPosition = position(source, source.indexOf("if (inner())"));
        var edit = CollapsibleIfRepair.rewrite(source, "Sample.java",
            innerPosition[0], innerPosition[1]).orElseThrow();
        String changed = source.substring(0, edit.start()) + edit.replacement() + source.substring(edit.end());
        assertTrue(changed.contains("if ((outer || inner()) && (inner()))"), changed);
        assertTrue(changed.contains("{\n                body();\n            }"), changed);
        assertFalse(AstSupport.hasErrors(AstSupport.parse(changed, "Sample.java", false)));

        String withElse = """
            class Sample {
                void run(boolean a, boolean b) {
                    if (a) {
                        if (b) { work(); }
                    } else { fallback(); }
                }
                void work() {}
                void fallback() {}
            }
            """;
        int[] elsePosition = position(withElse, withElse.indexOf("if (b)"));
        assertTrue(CollapsibleIfRepair.rewrite(withElse, "Sample.java",
            elsePosition[0], elsePosition[1]).isEmpty());
    }

    @Test
    void standardCharsetRepairReplacesOnlyResolvedJdkCallsFromTheAllowlist() throws Exception {
        String source = """
            import java.nio.charset.Charset;
            class Sample {
                Charset charset() { return Charset.forName("utf-8"); }
            }
            """;
        var finding = finding(source, "PMD/UseStandardCharsets");
        var edit = StandardCharsetRepair.rewrite(source, "Sample.java",
            finding.path("range").path("start").path("line").asInt(),
            finding.path("range").path("start").path("column").asInt()).orElseThrow();
        String changed = source.substring(0, edit.start()) + edit.replacement() + source.substring(edit.end());
        assertEquals("java.nio.charset.StandardCharsets.UTF_8", edit.replacement());
        assertTrue(changed.contains("return java.nio.charset.StandardCharsets.UTF_8;"), changed);
        assertFalse(AstSupport.hasErrors(AstSupport.parse(changed, "Sample.java", true)));

        String unsupported = """
            import java.nio.charset.Charset;
            class Sample {
                static final String NAME = "UTF-8";
                Charset charset() { return Charset.forName(NAME); }
            }
            """;
        var unsupportedFinding = finding(unsupported, "PMD/UseStandardCharsets");
        assertTrue(StandardCharsetRepair.rewrite(unsupported, "Sample.java",
            unsupportedFinding.path("range").path("start").path("line").asInt(),
            unsupportedFinding.path("range").path("start").path("column").asInt()).isEmpty());

        var mappings = java.util.Map.of(
            "US-ASCII", "US_ASCII",
            "ISO-8859-1", "ISO_8859_1",
            "UTF-8", "UTF_8",
            "UTF-16", "UTF_16",
            "UTF-16BE", "UTF_16BE",
            "UTF-16LE", "UTF_16LE");
        for (var entry : mappings.entrySet()) {
            String input = "class Sample { java.nio.charset.Charset value() { return "
                + "java.nio.charset.Charset.forName(\"" + entry.getKey().toLowerCase(java.util.Locale.ROOT)
                + "\"); } }";
            int[] callPosition = position(input, input.indexOf("Charset.forName"));
            var mapped = StandardCharsetRepair.rewrite(input, "Sample.java",
                callPosition[0], callPosition[1]).orElseThrow();
            assertEquals("java.nio.charset.StandardCharsets." + entry.getValue(), mapped.replacement());
        }

        String unicodeCaseFold = """
            import java.nio.charset.Charset;
            class Sample {
                Charset charset() { return Charset.forName("ıso-8859-1"); }
            }
            """;
        int[] unicodePosition = position(unicodeCaseFold, unicodeCaseFold.indexOf("forName"));
        assertTrue(StandardCharsetRepair.rewrite(unicodeCaseFold, "Sample.java",
            unicodePosition[0], unicodePosition[1]).isEmpty(),
            "Non-ASCII case folds must not turn an invalid charset name into a valid constant.");
    }

    @Test
    void standardCharsetRepairDeclinesShadowedReceiversCommentsAndExpressionReceivers() throws Exception {
        String shadowedType = """
            class Sample {
                static class Charset {
                    static java.nio.charset.Charset forName(String name) {
                        return java.nio.charset.Charset.defaultCharset();
                    }
                }
                java.nio.charset.Charset charset() { return Charset.forName("UTF-8"); }
            }
            """;
        int[] typeCall = position(shadowedType, shadowedType.indexOf("forName"));
        assertTrue(StandardCharsetRepair.rewrite(shadowedType, "Sample.java",
            typeCall[0], typeCall[1]).isEmpty());

        String shadowedVariable = """
            import java.nio.charset.Charset;
            class Sample {
                Charset charset() {
                    Charset Charset = null;
                    return Charset.forName("UTF-8");
                }
            }
            """;
        int[] variableCall = position(shadowedVariable, shadowedVariable.indexOf("forName"));
        assertTrue(StandardCharsetRepair.rewrite(shadowedVariable, "Sample.java",
            variableCall[0], variableCall[1]).isEmpty());

        String commentBetweenReceiverAndMethod = """
            import java.nio.charset.Charset;
            class Sample {
                Charset charset() { return Charset /* keep */ .forName("UTF-8"); }
            }
            """;
        int[] receiverCommentCall = position(commentBetweenReceiverAndMethod,
            commentBetweenReceiverAndMethod.indexOf("forName"));
        assertTrue(StandardCharsetRepair.rewrite(commentBetweenReceiverAndMethod, "Sample.java",
            receiverCommentCall[0], receiverCommentCall[1]).isEmpty());

        String commentInArguments = """
            import java.nio.charset.Charset;
            class Sample {
                Charset charset() { return Charset.forName(/* keep */ "UTF-8"); }
            }
            """;
        int[] argumentCommentCall = position(commentInArguments, commentInArguments.indexOf("forName"));
        assertTrue(StandardCharsetRepair.rewrite(commentInArguments, "Sample.java",
            argumentCommentCall[0], argumentCommentCall[1]).isEmpty());

        String expressionReceiver = """
            import java.nio.charset.Charset;
            class Sample {
                Charset receiver() { return Charset.defaultCharset(); }
                Charset charset() { return receiver().forName("UTF-8"); }
            }
            """;
        int[] expressionCall = position(expressionReceiver, expressionReceiver.indexOf("forName"));
        assertTrue(StandardCharsetRepair.rewrite(expressionReceiver, "Sample.java",
            expressionCall[0], expressionCall[1]).isEmpty());
    }

    @Test
    void standardCharsetRewriteCompilesAndPreservesRuntimeBehaviorAtRelease8() throws Exception {
        String before = """
            import java.nio.charset.Charset;
            class CharsetFixture {
                static Charset charset() { return Charset.forName("utf-8"); }
                public static void main(String[] args) {
                    System.out.print(charset().name() + ":" + charset().newEncoder().canEncode("café"));
                }
            }
            """;
        var finding = finding(before, "PMD/UseStandardCharsets");
        var edit = StandardCharsetRepair.rewrite(before, "CharsetFixture.java",
            finding.path("range").path("start").path("line").asInt(),
            finding.path("range").path("start").path("column").asInt()).orElseThrow();
        String after = before.substring(0, edit.start()) + edit.replacement() + before.substring(edit.end());

        assertEquals(0, compileAtRelease(before, "charset-before", "CharsetFixture.java"));
        assertEquals(0, compileAtRelease(after, "charset-after", "CharsetFixture.java"));
        assertEquals(runClass("charset-before", "CharsetFixture"), runClass("charset-after", "CharsetFixture"));
    }

    @Test
    void removesUnusedPrimitiveLiteralLocalsOnlyWhenTheBindingHasNoOtherUse() throws Exception {
        String source = """
            class Sample {
                int value() {
                    int dead = 42;
                    return 7;
                }
            }
            """;
        int[] namePosition = position(source, source.indexOf("dead"));
        var edit = UnusedPrimitiveLocalRepair.rewrite(source, "Sample.java",
            namePosition[0], namePosition[1]).orElseThrow();
        String changed = source.substring(0, edit.start()) + edit.replacement() + source.substring(edit.end());
        assertFalse(changed.contains("int dead"), changed);
        assertTrue(changed.contains("return 7;"), changed);
        assertFalse(AstSupport.hasErrors(AstSupport.parse(changed, "Sample.java", true)));
        int[] initializerPosition = position(source, source.indexOf("42"));
        assertTrue(UnusedPrimitiveLocalRepair.rewrite(source, "Sample.java",
            initializerPosition[0], initializerPosition[1]).isPresent(),
            "A reported range within the initializer must still locate its declaration.");
        int[] typePosition = position(source, source.indexOf("int dead"));
        assertTrue(UnusedPrimitiveLocalRepair.rewrite(source, "Sample.java",
            typePosition[0], typePosition[1]).isEmpty(),
            "An offset outside the variable fragment must not select a nearby declaration.");

        String sideEffect = """
            class Sample {
                int calls;
                int next() { calls++; return calls; }
                int value() {
                    int dead = next();
                    return 7;
                }
            }
            """;
        int[] sideEffectPosition = position(sideEffect, sideEffect.indexOf("dead"));
        assertTrue(UnusedPrimitiveLocalRepair.rewrite(sideEffect, "Sample.java",
            sideEffectPosition[0], sideEffectPosition[1]).isEmpty());

        String trailingComment = """
            class Sample {
                int value() {
                    int dead = 42; // keep explanation
                    return 7;
                }
            }
            """;
        int[] commentPosition = position(trailingComment, trailingComment.indexOf("dead"));
        assertTrue(UnusedPrimitiveLocalRepair.rewrite(trailingComment, "Sample.java",
            commentPosition[0], commentPosition[1]).isEmpty());

        String leadingComment = """
            class Sample {
                int value() {
                    // explain this declaration
                    int dead = 42;
                    return 7;
                }
            }
            """;
        int[] leadingPosition = position(leadingComment, leadingComment.indexOf("dead"));
        assertTrue(UnusedPrimitiveLocalRepair.rewrite(leadingComment, "Sample.java",
            leadingPosition[0], leadingPosition[1]).isEmpty());

        String captured = """
            class Sample {
                void run() {
                    int dead = 42;
                    Runnable task = () -> System.out.println(dead);
                }
            }
            """;
        int[] capturedPosition = position(captured, captured.indexOf("dead"));
        assertTrue(UnusedPrimitiveLocalRepair.rewrite(captured, "Sample.java",
            capturedPosition[0], capturedPosition[1]).isEmpty(),
            "A binding reference captured by a lambda must keep the declaration.");

        String assignedLater = """
            class Sample {
                void run() {
                    int dead = 42;
                    dead++;
                }
            }
            """;
        int[] assignedPosition = position(assignedLater, assignedLater.indexOf("dead"));
        assertTrue(UnusedPrimitiveLocalRepair.rewrite(assignedLater, "Sample.java",
            assignedPosition[0], assignedPosition[1]).isEmpty(),
            "A later write is still a binding reference.");

        String multipleFragments = """
            class Sample {
                void run() {
                    int dead = 42, other = 7;
                }
            }
            """;
        int[] multiplePosition = position(multipleFragments, multipleFragments.indexOf("dead"));
        assertTrue(UnusedPrimitiveLocalRepair.rewrite(multipleFragments, "Sample.java",
            multiplePosition[0], multiplePosition[1]).isEmpty(),
            "A declaration with multiple fragments is outside the supported shape.");

        String forInitializer = """
            class Sample {
                void run() {
                    for (int dead = 42; dead < 43; dead++) { }
                }
            }
            """;
        int[] forPosition = position(forInitializer, forInitializer.indexOf("dead"));
        assertTrue(UnusedPrimitiveLocalRepair.rewrite(forInitializer, "Sample.java",
            forPosition[0], forPosition[1]).isEmpty(),
            "Loop initializer declarations are not local declaration statements in a block.");

        String unresolvedSource = """
            class Sample {
                void run() {
                    int dead = 42;
                    System.out.println(missingName);
                }
            }
            """;
        int[] unresolvedPosition = position(unresolvedSource, unresolvedSource.indexOf("dead"));
        assertTrue(UnusedPrimitiveLocalRepair.rewrite(unresolvedSource, "Sample.java",
            unresolvedPosition[0], unresolvedPosition[1]).isEmpty(),
            "A source unit with unresolved references must fail closed.");

        String annotatedType = """
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE_USE)
            @interface Marker {}
            class Sample {
                void run() {
                    @Marker int dead = 42;
                }
            }
            """;
        int[] annotatedPosition = position(annotatedType, annotatedType.indexOf("dead"));
        assertTrue(UnusedPrimitiveLocalRepair.rewrite(annotatedType, "Sample.java",
            annotatedPosition[0], annotatedPosition[1]).isEmpty(),
            "Deleting an annotated primitive local would remove runtime-visible type metadata.");
    }

    @Test
    void unusedPrimitiveLiteralDeletionCompilesAndPreservesRuntimeOutput() throws Exception {
        String before = """
            class PrimitiveFixture {
                static int compute() {
                    int dead = 42;
                    return 7;
                }
                public static void main(String[] args) {
                    System.out.print(compute());
                }
            }
            """;
        var edit = UnusedPrimitiveLocalRepair.rewrite(before, "PrimitiveFixture.java",
            position(before, before.indexOf("dead"))[0],
            position(before, before.indexOf("dead"))[1]).orElseThrow();
        String after = before.substring(0, edit.start()) + edit.replacement() + before.substring(edit.end());

        assertEquals(0, compileAtRelease(before, "unused-local-before", "PrimitiveFixture.java"));
        assertEquals(0, compileAtRelease(after, "unused-local-after", "PrimitiveFixture.java"));
        assertEquals(runClass("unused-local-before", "PrimitiveFixture"),
            runClass("unused-local-after", "PrimitiveFixture"));
    }

    @Test
    void diamondRequiresBindingIdenticalLocalInference() throws Exception {
        String source = """
            import java.util.ArrayList;
            class Sample {
                void run() {
                    ArrayList<String> names = new ArrayList<String>();
                }
            }
            """;
        var finding = finding(source, "PMD/UseDiamondOperator");
        var edit = DiamondOperatorRepair.rewrite(source, "Sample.java",
            finding.path("range").path("start").path("line").asInt(),
            finding.path("range").path("start").path("column").asInt(), 25).orElseThrow();
        String changed = source.substring(0, edit.start()) + edit.replacement() + source.substring(edit.end());
        assertEquals("<>", edit.replacement());
        assertTrue(changed.contains("new ArrayList<>()"), changed);

        String unsupported = """
            import java.util.ArrayList;
            class Sample {
                void run() {
                    ArrayList<String> names = new ArrayList<String>(4);
                }
            }
            """;
        var unsupportedFinding = finding(unsupported, "PMD/UseDiamondOperator");
        assertTrue(DiamondOperatorRepair.rewrite(unsupported, "Sample.java",
            unsupportedFinding.path("range").path("start").path("line").asInt(),
            unsupportedFinding.path("range").path("start").path("column").asInt(), 25).isEmpty());
        assertTrue(DiamondOperatorRepair.rewrite(source, "Sample.java",
            finding.path("range").path("start").path("line").asInt(),
            finding.path("range").path("start").path("column").asInt(), 6).isEmpty());

        String annotatedArgument = """
            import java.lang.annotation.*;
            import java.util.ArrayList;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE_USE)
            @interface Marker {}
            class Sample {
                void run() {
                    ArrayList<@Marker String> names = new ArrayList<@Marker String>();
                }
            }
            """;
        int annotatedPosition = annotatedArgument.indexOf("new ArrayList");
        int annotatedLine = annotatedArgument.substring(0, annotatedPosition).split("\\n", -1).length;
        int annotatedColumn = annotatedPosition
            - annotatedArgument.lastIndexOf('\n', annotatedPosition - 1);
        assertTrue(DiamondOperatorRepair.rewrite(annotatedArgument, "Sample.java",
            annotatedLine, annotatedColumn, 25).isEmpty(),
            "Replacing the explicit constructor argument must not drop its type-use annotation.");
    }

    @Test
    void diamondRewriteCompilesBeforeAndAfterAtConfiguredRelease() throws Exception {
        String before = """
            import java.util.ArrayList;
            class DiamondFixture {
                static ArrayList<String> create() {
                    ArrayList<String> names = new ArrayList<String>();
                    names.add("JavaFix");
                    return names;
                }
            }
            """;
        var finding = finding(before, "PMD/UseDiamondOperator");
        var edit = DiamondOperatorRepair.rewrite(before, "DiamondFixture.java",
            finding.path("range").path("start").path("line").asInt(),
            finding.path("range").path("start").path("column").asInt(), 8).orElseThrow();
        String after = before.substring(0, edit.start()) + edit.replacement() + before.substring(edit.end());

        assertEquals(0, compileAtRelease(before, "before"));
        assertEquals(0, compileAtRelease(after, "after"));
    }

    private int compileAtRelease(String source, String label) throws Exception {
        return compileAtRelease(source, label, "DiamondFixture.java");
    }

    private int compileAtRelease(String source, String label, String filename) throws Exception {
        Path sourceFile = temporaryDirectory.resolve(label).resolve(filename);
        Path classes = temporaryDirectory.resolve(label + "-classes");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classes);
        Files.writeString(sourceFile, source);
        return ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "8", "-d", classes.toString(), sourceFile.toString());
    }

    private String runClass(String label, String className) throws Exception {
        Path classes = temporaryDirectory.resolve(label + "-classes");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classes.toString(), className).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    @Test
    void collectionIsEmptyOnlyRewritesDirectlyConstructedAllowlistedJdkCollections() throws Exception {
        String source = """
            class Sample {
                boolean empty() {
                    return new java.util.ArrayList<String>().size() == 0;
                }
            }
            """;
        var finding = finding(source, "PMD/UseCollectionIsEmpty");
        var edit = CollectionIsEmptyRepair.rewrite(source, "Sample.java",
            finding.path("range").path("start").path("line").asInt(),
            finding.path("range").path("start").path("column").asInt()).orElseThrow();
        String changed = source.substring(0, edit.start()) + edit.replacement() + source.substring(edit.end());
        assertTrue(changed.contains("new java.util.ArrayList<String>().isEmpty()"), changed);
        assertFalse(AstSupport.hasErrors(AstSupport.parse(changed, "Sample.java", true)));

        String unknown = """
            import java.util.Collection;
            class Sample {
                boolean empty(Collection<String> items) { return items.size() == 0; }
            }
            """;
        var unknownFinding = finding(unknown, "PMD/UseCollectionIsEmpty");
        assertTrue(CollectionIsEmptyRepair.rewrite(unknown, "Sample.java",
            unknownFinding.path("range").path("start").path("line").asInt(),
            unknownFinding.path("range").path("start").path("column").asInt()).isEmpty());

        String inequality = """
            class Sample {
                boolean nonempty() {
                    return new java.util.HashSet<String>().size() != 0;
                }
            }
            """;
        var inequalityFinding = finding(inequality, "PMD/UseCollectionIsEmpty");
        var inequalityEdit = CollectionIsEmptyRepair.rewrite(inequality, "Sample.java",
            inequalityFinding.path("range").path("start").path("line").asInt(),
            inequalityFinding.path("range").path("start").path("column").asInt())
            .orElseThrow(() -> new AssertionError(inequalityFinding.toPrettyString()));
        assertEquals("(!new java.util.HashSet<String>().isEmpty())", inequalityEdit.replacement());

        assertCollectionRepairDeclined("""
            class Sample {
                boolean empty() { return new java.util.ArrayList<String>(1).size() == 0; }
            }
            """);
        assertCollectionRepairDeclined("""
            class Sample {
                boolean empty() { return new java.util.ArrayList<String>() { }.size() == 0; }
            }
            """);
        assertCollectionRepairDeclined("""
            import java.util.ArrayList;
            class CustomList extends ArrayList<String> {
                @Override public boolean isEmpty() { return false; }
                boolean empty() { return new CustomList().size() == 0; }
            }
            """);
        assertCollectionRepairDeclined("""
            class Sample {
                boolean empty() { return new java.util.ArrayList<String>().size() == 0x0; }
            }
            """);
        assertCollectionRepairDeclined("""
            class Sample {
                boolean empty() { return new java.util.ArrayList<String>().size() < 1; }
            }
            """);
        assertCollectionRepairDeclined("""
            class Sample {
                boolean empty() { return new java.util.ArrayList<String>().size() /* preserve */ == 0; }
            }
            """);
    }

    private void assertCollectionRepairDeclined(String source) throws Exception {
        int[] location = position(source, source.indexOf("size()"));
        assertTrue(CollectionIsEmptyRepair.rewrite(source, "Sample.java",
            location[0], location[1]).isEmpty(), source);
    }

    @Test
    void collectionIsEmptyRewriteCompilesAndPreservesRuntimeResults() throws Exception {
        String before = """
            public class CollectionFixture {
                static boolean emptyList() {
                    return new java.util.ArrayList<String>().size() == 0;
                }
                static boolean emptySetNotNonempty() {
                    return new java.util.HashSet<String>().size() != 0;
                }
                public static void main(String[] args) {
                    System.out.print(emptyList() + ":" + emptySetNotNonempty());
                }
            }
            """;
        int[] listPosition = position(before, before.indexOf("size()"));
        var listEdit = CollectionIsEmptyRepair.rewrite(before, "CollectionFixture.java",
            listPosition[0], listPosition[1]).orElseThrow();
        String afterList = before.substring(0, listEdit.start()) + listEdit.replacement()
            + before.substring(listEdit.end());
        int[] setPosition = position(afterList, afterList.indexOf("size()"));
        var setEdit = CollectionIsEmptyRepair.rewrite(afterList, "CollectionFixture.java",
            setPosition[0], setPosition[1]).orElseThrow();
        String after = afterList.substring(0, setEdit.start()) + setEdit.replacement()
            + afterList.substring(setEdit.end());

        assertEquals(0, compileAtRelease(before, "collection-before", "CollectionFixture.java"));
        assertEquals(0, compileAtRelease(after, "collection-after", "CollectionFixture.java"));
        assertEquals(runClass("collection-before", "CollectionFixture"),
            runClass("collection-after", "CollectionFixture"));
    }

    @Test
    void unconditionalIfKeepsSelectedBlockScopeAndRejectsNoElseAndUnreachableCandidates() throws Exception {
        String source = """
            class Sample {
                int value() {
                    if (false) { int local = 1; return local; }
                    else { int local = 2; return local; }
                }
            }
            """;
        var finding = finding(source, "PMD/UnconditionalIfStatement");
        var edit = UnconditionalIfRepair.rewrite(source, "Sample.java",
            finding.path("range").path("start").path("line").asInt(),
            finding.path("range").path("start").path("column").asInt())
            .orElseThrow(() -> new AssertionError(finding.toPrettyString()));
        String changed = source.substring(0, edit.start()) + edit.replacement() + source.substring(edit.end());
        assertTrue(changed.contains("{ int local = 2; return local; }"), changed);
        assertFalse(AstSupport.hasErrors(AstSupport.parse(changed, "Sample.java", true)));

        String noElse = "class Sample { void run() { if (true) { work(); } } void work() {} }";
        assertTrue(UnconditionalIfRepair.rewrite(noElse, "Sample.java", 1,
            noElse.indexOf("if (") + 1).isEmpty());
        String nonLiteral = """
            class Sample {
                int value(boolean enabled) {
                    if (enabled) { return 1; } else { return 2; }
                }
            }
            """;
        int[] nonLiteralPosition = position(nonLiteral, nonLiteral.indexOf("if ("));
        assertTrue(UnconditionalIfRepair.rewrite(nonLiteral, "Sample.java",
            nonLiteralPosition[0], nonLiteralPosition[1]).isEmpty());
        String unbraced = "class Sample { int value() { if (true) return 1; else return 2; } }";
        int[] unbracedPosition = position(unbraced, unbraced.indexOf("if ("));
        assertTrue(UnconditionalIfRepair.rewrite(unbraced, "Sample.java",
            unbracedPosition[0], unbracedPosition[1]).isEmpty());
        String commented = """
            class Sample {
                int value() {
                    if (true) { return 1; } /* retain this note */ else { return 2; }
                }
            }
            """;
        int[] commentedPosition = position(commented, commented.indexOf("if ("));
        assertTrue(UnconditionalIfRepair.rewrite(commented, "Sample.java",
            commentedPosition[0], commentedPosition[1]).isEmpty());
        String trueCase = """
            class Sample {
                int value() {
                    if (true) { return 1; } else { return 2; }
                }
            }
            """;
        var trueFinding = finding(trueCase, "PMD/UnconditionalIfStatement");
        var trueEdit = UnconditionalIfRepair.rewrite(trueCase, "Sample.java",
            trueFinding.path("range").path("start").path("line").asInt(),
            trueFinding.path("range").path("start").path("column").asInt()).orElseThrow();
        assertEquals("{ return 1; }", trueEdit.replacement());
    }

    @Test
    void unconditionalIfCompilesAndPreservesRuntimeOutput() throws Exception {
        String before = """
            public class UnconditionalFixture {
                static int value() {
                    if (false) { int result = 1; return result; }
                    else { int result = 2; return result; }
                }
                public static void main(String[] args) {
                    System.out.print(value());
                }
            }
            """;
        var finding = finding(before, "PMD/UnconditionalIfStatement");
        var edit = UnconditionalIfRepair.rewrite(before, "UnconditionalFixture.java",
            finding.path("range").path("start").path("line").asInt(),
            finding.path("range").path("start").path("column").asInt()).orElseThrow();
        String after = before.substring(0, edit.start()) + edit.replacement() + before.substring(edit.end());
        assertEquals(0, compileAtRelease(before, "unconditional-before", "UnconditionalFixture.java"));
        assertEquals(0, compileAtRelease(after, "unconditional-after", "UnconditionalFixture.java"));
        assertEquals(runClass("unconditional-before", "UnconditionalFixture"),
            runClass("unconditional-after", "UnconditionalFixture"));
    }

    @Test
    void mergesOnlyIdenticalIntLiteralReturnsGuardedByBooleanParameter() throws Exception {
        String source = """
            class Sample {
                int value(boolean enabled) {
                    if (enabled) { return 7; } else { return 7; }
                }
            }
            """;
        var finding = finding(source, "PMD/IdenticalConditionalBranches");
        var edit = IdenticalLiteralReturnsRepair.rewrite(source, "Sample.java",
            finding.path("range").path("start").path("line").asInt(),
            finding.path("range").path("start").path("column").asInt()).orElseThrow();
        String changed = source.substring(0, edit.start()) + edit.replacement() + source.substring(edit.end());
        assertTrue(changed.contains("return 7;"), changed);
        assertFalse(changed.contains("if (enabled)"), changed);
        assertFalse(AstSupport.hasErrors(AstSupport.parse(changed, "Sample.java", true)));

        String boxed = """
            class Sample {
                int value(Boolean enabled) {
                    if (enabled) { return 7; } else { return 7; }
                }
            }
            """;
        var boxedFinding = finding(boxed, "PMD/IdenticalConditionalBranches");
        assertTrue(IdenticalLiteralReturnsRepair.rewrite(boxed, "Sample.java",
            boxedFinding.path("range").path("start").path("line").asInt(),
            boxedFinding.path("range").path("start").path("column").asInt()).isEmpty());
    }

    @Test
    void removesOnlyImmediateDeadLiteralStore() throws Exception {
        String source = """
            class Sample {
                int value() {
                    int count;
                    count = 1;
                    count = 2;
                    return count;
                }
            }
            """;
        var findings = new PmdRuleProvider().analyze(List.of(
            Files.writeString(temporaryDirectory.resolve("Sample.java"), source)))
            .get(temporaryDirectory.resolve("Sample.java").toRealPath());
        var finding = findings.stream()
            .filter(value -> "PMD/UnusedAssignment".equals(value.path("ruleId").asText()))
            .findFirst().orElseThrow(() -> new AssertionError("Expected PMD/UnusedAssignment, got " + findings));
        var edit = UnusedLiteralAssignmentRepair.rewrite(source, "Sample.java",
            finding.path("range").path("start").path("line").asInt(),
            finding.path("range").path("start").path("column").asInt())
            .orElseThrow(() -> new AssertionError(finding.toPrettyString()));
        String changed = source.substring(0, edit.start()) + edit.replacement() + source.substring(edit.end());
        assertTrue(changed.contains("count = 2;"), changed);
        assertFalse(changed.contains("count = 1;"), changed);
        assertFalse(AstSupport.hasErrors(AstSupport.parse(changed, "Sample.java", true)));

        String effect = """
            class Sample {
                int calls;
                int next() { calls++; return calls; }
                int value() {
                    int count;
                    count = next();
                    count = 2;
                    return count;
                }
            }
            """;
        Path effectFile = Files.writeString(temporaryDirectory.resolve("Effect.java"), effect);
        var effectFinding = new PmdRuleProvider().analyze(List.of(effectFile)).get(effectFile.toRealPath())
            .stream().filter(value -> "PMD/UnusedAssignment".equals(value.path("ruleId").asText()))
            .findFirst().orElseThrow();
        assertTrue(UnusedLiteralAssignmentRepair.rewrite(effect, "Effect.java",
            effectFinding.path("range").path("start").path("line").asInt(),
            effectFinding.path("range").path("start").path("column").asInt()).isEmpty());

        String trailingComment = """
            class Sample {
                int value() {
                    int count;
                    count = 1; // do not discard this comment
                    count = 2;
                    return count;
                }
            }
            """;
        Path commentFile = Files.writeString(temporaryDirectory.resolve("Comment.java"), trailingComment);
        var commentFinding = new PmdRuleProvider().analyze(List.of(commentFile)).get(commentFile.toRealPath())
            .stream().filter(value -> "PMD/UnusedAssignment".equals(value.path("ruleId").asText()))
            .findFirst().orElseThrow();
        assertTrue(UnusedLiteralAssignmentRepair.rewrite(trailingComment, "Comment.java",
            commentFinding.path("range").path("start").path("line").asInt(),
            commentFinding.path("range").path("start").path("column").asInt()).isEmpty());

        String leadingComment = """
            class Sample {
                int value() {
                    int count;
                    // preserve explanation for first assignment
                    count = 1;
                    count = 2;
                    return count;
                }
            }
            """;
        Path leadingFile = Files.writeString(temporaryDirectory.resolve("LeadingComment.java"), leadingComment);
        var leadingFinding = new PmdRuleProvider().analyze(List.of(leadingFile)).get(leadingFile.toRealPath())
            .stream().filter(value -> "PMD/UnusedAssignment".equals(value.path("ruleId").asText()))
            .findFirst().orElseThrow();
        assertTrue(UnusedLiteralAssignmentRepair.rewrite(leadingComment, "LeadingComment.java",
            leadingFinding.path("range").path("start").path("line").asInt(),
            leadingFinding.path("range").path("start").path("column").asInt()).isEmpty());
    }

    @Test
    void unusedLiteralAssignmentRejectsInterveningReadsBranchesCompoundStoresAndFields() throws Exception {
        assertAssignmentRepairDeclined("""
            class Sample {
                int value() {
                    int count;
                    count = 1;
                    int observed = count;
                    count = 2;
                    return observed + count;
                }
            }
            """);
        assertAssignmentRepairDeclined("""
            class Sample {
                int value(boolean enabled) {
                    int count;
                    count = 1;
                    if (enabled) { count = 2; }
                    count = 3;
                    return count;
                }
            }
            """);
        assertAssignmentRepairDeclined("""
            class Sample {
                int value() {
                    int count;
                    count = 1;
                    count += 1;
                    count = 2;
                    return count;
                }
            }
            """);
        assertAssignmentRepairDeclined("""
            class Sample {
                int count;
                int value() {
                    this.count = 1;
                    this.count = 2;
                    return count;
                }
            }
            """);
    }

    private void assertAssignmentRepairDeclined(String source) throws Exception {
        int[] location = position(source, source.indexOf("= 1"));
        assertTrue(UnusedLiteralAssignmentRepair.rewrite(source, "Sample.java",
            location[0], location[1]).isEmpty(), source);
    }

    @Test
    void unusedLiteralAssignmentCompilesAndPreservesRuntimeOutput() throws Exception {
        String before = """
            public class AssignmentFixture {
                static int value() {
                    int count;
                    count = 1;
                    count = 2;
                    return count;
                }
                public static void main(String[] args) {
                    System.out.print(value());
                }
            }
            """;
        var finding = finding(before, "PMD/UnusedAssignment");
        var edit = UnusedLiteralAssignmentRepair.rewrite(before, "AssignmentFixture.java",
            finding.path("range").path("start").path("line").asInt(),
            finding.path("range").path("start").path("column").asInt()).orElseThrow();
        String after = before.substring(0, edit.start()) + edit.replacement() + before.substring(edit.end());
        assertEquals(0, compileAtRelease(before, "assignment-before", "AssignmentFixture.java"));
        assertEquals(0, compileAtRelease(after, "assignment-after", "AssignmentFixture.java"));
        assertEquals(runClass("assignment-before", "AssignmentFixture"),
            runClass("assignment-after", "AssignmentFixture"));
    }

    private ObjectNode finding(String source, String rule) throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("Sample.java"), source);
        List<ObjectNode> findings = new PmdRuleProvider().analyze(List.of(file))
            .get(file.toRealPath());
        return findings.stream().filter(value -> rule.equals(value.path("ruleId").asText()))
            .findFirst().orElseThrow(() -> new AssertionError("Expected " + rule + ", got " + findings));
    }

    private static int[] position(String source, int offset) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[]{line, offset - lineStart + 1};
    }
}
