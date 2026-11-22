package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InstanceofExpression;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Merges one reported nested braced if while preserving short-circuit order and block scope. */
public final class CollapsibleIfRepair {
    private CollapsibleIfRepair() {}

    public static Optional<SourceEdit> rewrite(String source, String unitName, int line, int column)
        throws Exception {
        CompilationUnit unit = AstSupport.parse(source, unitName, false);
        if (AstSupport.hasErrors(unit)) return Optional.empty();
        int reportedStart = AstSupport.offsetAt(unit, source, line, column);
        if (reportedStart < 0) return Optional.empty();
        List<Candidate> candidates = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override public boolean visit(IfStatement inner) {
                if (inner.getStartPosition() != reportedStart || inner.getElseStatement() != null
                    || !(inner.getParent() instanceof Block outerBlock)
                    || outerBlock.statements().size() != 1
                    || !(outerBlock.getParent() instanceof IfStatement outer)
                    || outer.getThenStatement() != outerBlock || outer.getElseStatement() != null
                    || !(inner.getThenStatement() instanceof Block innerBlock)
                    || AstSupport.containsRecovery(outer)) return true;
                final boolean[] hasPattern = {false};
                outer.getExpression().accept(new ASTVisitor() {
                    @Override public boolean visit(InstanceofExpression expression) {
                        hasPattern[0] = true;
                        return true;
                    }
                });
                inner.getExpression().accept(new ASTVisitor() {
                    @Override public boolean visit(InstanceofExpression expression) {
                        hasPattern[0] = true;
                        return true;
                    }
                });
                int start = outer.getStartPosition();
                int end = start + outer.getLength();
                if (hasPattern[0] || AstSupport.hasComment(unit, start, end)) return true;
                candidates.add(new Candidate(outer, inner, innerBlock, start, end));
                return true;
            }
        });
        if (candidates.size() != 1) return Optional.empty();
        Candidate candidate = candidates.getFirst();
        String outerCondition = source.substring(candidate.outer().getExpression().getStartPosition(),
            candidate.outer().getExpression().getStartPosition() + candidate.outer().getExpression().getLength());
        String innerCondition = source.substring(candidate.inner().getExpression().getStartPosition(),
            candidate.inner().getExpression().getStartPosition() + candidate.inner().getExpression().getLength());
        String innerBlock = source.substring(candidate.innerBlock().getStartPosition(),
            candidate.innerBlock().getStartPosition() + candidate.innerBlock().getLength());
        String replacement = "if ((" + outerCondition + ") && (" + innerCondition + ")) " + innerBlock;
        String changed = source.substring(0, candidate.start()) + replacement + source.substring(candidate.end());
        CompilationUnit after = AstSupport.parse(changed, unitName, false);
        if (AstSupport.hasErrors(after) || !hasCollapsedIf(after, candidate.start())) return Optional.empty();
        return Optional.of(new SourceEdit(candidate.start(), candidate.end(), replacement));
    }

    private static boolean hasCollapsedIf(CompilationUnit unit, int start) {
        final boolean[] found = {false};
        unit.accept(new ASTVisitor() {
            @Override public boolean visit(IfStatement node) {
                if (node.getStartPosition() == start && node.getElseStatement() == null
                    && node.getExpression() instanceof org.eclipse.jdt.core.dom.InfixExpression infix
                    && infix.getOperator() == org.eclipse.jdt.core.dom.InfixExpression.Operator.CONDITIONAL_AND
                    && node.getThenStatement() instanceof Block) found[0] = true;
                return !found[0];
            }
        });
        return found[0];
    }

    private record Candidate(IfStatement outer, IfStatement inner, Block innerBlock, int start, int end) {}
}
