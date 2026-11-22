package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IfStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Selects a literal if/else branch while retaining its block scope. */
public final class UnconditionalIfRepair {
    private UnconditionalIfRepair() {}

    public static Optional<SourceEdit> rewrite(String source, String unitName, int line, int column)
        throws Exception {
        CompilationUnit before = AstSupport.parse(source, unitName, true);
        if (AstSupport.hasErrors(before)) return Optional.empty();
        int reported = AstSupport.offsetAt(before, source, line, column);
        if (reported < 0) return Optional.empty();
        List<IfStatement> candidates = new ArrayList<>();
        before.accept(new ASTVisitor() {
            @Override public boolean visit(IfStatement node) {
                if (node.getStartPosition() <= reported && reported < node.getStartPosition() + node.getLength()) {
                    candidates.add(node);
                }
                return true;
            }
        });
        candidates.sort(java.util.Comparator.comparingInt(IfStatement::getLength));
        for (IfStatement statement : candidates) {
            if (!(statement.getParent() instanceof Block block)
                || !(block.getParent() instanceof org.eclipse.jdt.core.dom.MethodDeclaration)
                || !(statement.getExpression() instanceof BooleanLiteral literal)
                || !(statement.getThenStatement() instanceof Block)
                || !(statement.getElseStatement() instanceof Block)
                || AstSupport.containsRecovery(statement)) continue;
            int start = statement.getStartPosition();
            int end = start + statement.getLength();
            if (AstSupport.hasComment(before, start, end)) continue;
            Block selected = literal.booleanValue()
                ? (Block) statement.getThenStatement() : (Block) statement.getElseStatement();
            String replacement = source.substring(selected.getStartPosition(),
                selected.getStartPosition() + selected.getLength());
            String updated = source.substring(0, start) + replacement + source.substring(end);
            CompilationUnit after = AstSupport.parse(updated, unitName, true);
            if (AstSupport.hasErrors(after)) continue;
            return Optional.of(new SourceEdit(start, end, replacement));
        }
        return Optional.empty();
    }
}
