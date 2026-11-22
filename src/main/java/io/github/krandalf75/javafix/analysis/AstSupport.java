package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Comment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared conservative parsing and source-coordinate helpers for local repairs. */
public final class AstSupport {
    private AstSupport() {}

    public static CompilationUnit parse(String source, String unitName, boolean bindings) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setUnitName(unitName);
        parser.setResolveBindings(bindings);
        parser.setBindingsRecovery(false);
        parser.setStatementsRecovery(false);
        parser.setBindingsRecovery(false);
        parser.setEnvironment(null, null, null, true);
        Map<String, String> options = new HashMap<>(JavaCore.getOptions());
        JavaCore.setComplianceOptions(JavaCore.VERSION_25, options);
        parser.setCompilerOptions(options);
        return (CompilationUnit) parser.createAST(null);
    }

    public static boolean hasErrors(CompilationUnit unit) {
        for (var problem : unit.getProblems()) if (problem.isError()) return true;
        return false;
    }

    public static int offsetAt(CompilationUnit unit, String source, int oneBasedLine, int oneBasedColumn) {
        if (oneBasedLine < 1 || oneBasedColumn < 1) return -1;
        int lineStart = 0;
        for (int line = 1; line < oneBasedLine; line++) {
            int newline = source.indexOf('\n', lineStart);
            if (newline < 0) return -1;
            lineStart = newline + 1;
        }
        int lineEnd = source.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = source.length();
        if (lineEnd > lineStart && source.charAt(lineEnd - 1) == '\r') lineEnd--;
        int offset = lineStart + oneBasedColumn - 1;
        if (offset > lineEnd) return -1;
        if (offset > 0 && offset < source.length()
            && Character.isHighSurrogate(source.charAt(offset - 1))
            && Character.isLowSurrogate(source.charAt(offset))) return -1;
        return offset;
    }

    public static boolean hasComment(CompilationUnit unit, int start, int end) {
        List<Comment> comments = unit.getCommentList();
        if (comments == null) return true;
        for (Comment comment : comments) {
            int commentStart = comment.getStartPosition();
            int commentEnd = commentStart + comment.getLength();
            if (commentStart < end && start < commentEnd) return true;
        }
        return false;
    }

    public static boolean hasLeadingComment(CompilationUnit unit, String source, int start) {
        List<Comment> comments = unit.getCommentList();
        if (comments == null) return true;
        for (Comment comment : comments) {
            int commentEnd = comment.getStartPosition() + comment.getLength();
            if (commentEnd <= start && source.substring(commentEnd, start).isBlank()) return true;
        }
        return false;
    }

    public static boolean containsRecovery(ASTNode node) {
        if (node == null || (node.getFlags() & ASTNode.MALFORMED) != 0
            || (node.getFlags() & ASTNode.RECOVERED) != 0) return true;
        final boolean[] recovered = {false};
        node.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public void preVisit(ASTNode child) {
                if ((child.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0) {
                    recovered[0] = true;
                }
            }
        });
        return recovered[0];
    }
}
