package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Deletes a dead decimal-int store immediately overwritten by another literal store. */
public final class UnusedLiteralAssignmentRepair {
    private UnusedLiteralAssignmentRepair() {}

    public static Optional<SourceEdit> rewrite(String source, String unitName, int line, int column)
        throws Exception {
        CompilationUnit unit = AstSupport.parse(source, unitName, true);
        if (AstSupport.hasErrors(unit)) return Optional.empty();
        int reported = AstSupport.offsetAt(unit, source, line, column);
        if (reported < 0) return Optional.empty();
        List<Assignment> candidates = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override public boolean visit(Assignment node) {
                if (node.getStartPosition() <= reported && reported < node.getStartPosition() + node.getLength()) {
                    candidates.add(node);
                }
                return true;
            }
        });
        candidates.sort(java.util.Comparator.comparingInt(Assignment::getLength));
        for (Assignment first : candidates) {
            if (first.getOperator() != Assignment.Operator.ASSIGN
                || !(first.getParent() instanceof ExpressionStatement firstStatement)
                || !(firstStatement.getParent() instanceof Block block)
                || !(first.getLeftHandSide() instanceof SimpleName left)
                || !(first.getRightHandSide() instanceof NumberLiteral firstValue)
                || !isIntLiteral(firstValue.getToken())
                || !(left.resolveBinding() instanceof IVariableBinding binding)
                || binding.isRecovered() || binding.isField() || binding.isParameter()
                || binding.isEffectivelyFinal() || !binding.getType().isPrimitive()
                || !"int".equals(binding.getType().getName())
                || !(binding.getVariableDeclaration().getDeclaringClass() == null)) continue;
            int firstIndex = block.statements().indexOf(firstStatement);
            if (firstIndex < 0 || firstIndex + 1 >= block.statements().size()
                || !(block.statements().get(firstIndex + 1) instanceof ExpressionStatement secondStatement)
                || !(secondStatement.getExpression() instanceof Assignment second)
                || second.getOperator() != Assignment.Operator.ASSIGN
                || !(second.getLeftHandSide() instanceof SimpleName secondLeft)
                || !(second.getRightHandSide() instanceof NumberLiteral secondValue)
                || !isIntLiteral(secondValue.getToken())
                || !(secondLeft.resolveBinding() instanceof IVariableBinding secondBinding)
                || secondBinding.isRecovered()
                || !sameBinding(binding, secondBinding)) continue;
            // Keep the variable local, not a parameter or field; find its declaration binding.
            final boolean[] localDeclaration = {false};
            unit.accept(new ASTVisitor() {
                @Override public boolean visit(VariableDeclarationFragment fragment) {
                    if (fragment.resolveBinding() instanceof IVariableBinding declared
                        && sameBinding(binding, declared)
                        && fragment.getParent() instanceof VariableDeclarationStatement) localDeclaration[0] = true;
                    return true;
                }
            });
            if (!localDeclaration[0]) continue;
            int start = firstStatement.getStartPosition();
            int end = start + firstStatement.getLength();
            if (AstSupport.hasComment(unit, start, end)
                || AstSupport.hasLeadingComment(unit, source, start)) continue;
            int lineStart = source.lastIndexOf('\n', start - 1) + 1;
            int lineEnd = source.indexOf('\n', end);
            if (lineEnd < 0) lineEnd = source.length();
            if (AstSupport.hasComment(unit, start, lineEnd)) continue;
            if (source.substring(lineStart, start).isBlank() && source.substring(end, lineEnd).isBlank()) {
                int deleteEnd = lineEnd < source.length() ? lineEnd + 1 : lineEnd;
                return Optional.of(new SourceEdit(lineStart, deleteEnd, ""));
            }
            return Optional.of(new SourceEdit(start, end, ""));
        }
        return Optional.empty();
    }

    private static boolean sameBinding(IVariableBinding first, IVariableBinding second) {
        String firstKey = first.getVariableDeclaration().getKey();
        return firstKey != null && firstKey.equals(second.getVariableDeclaration().getKey());
    }

    private static boolean isIntLiteral(String text) {
        if (!text.matches("(?:0|[1-9][0-9]*)")) return false;
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException outOfRange) {
            return false;
        }
    }
}
