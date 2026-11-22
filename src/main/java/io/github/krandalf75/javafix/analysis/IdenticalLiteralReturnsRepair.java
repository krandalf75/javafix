package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.NumberLiteral;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Merges two identical decimal-int returns guarded by a primitive boolean parameter read. */
public final class IdenticalLiteralReturnsRepair {
    private IdenticalLiteralReturnsRepair() {}

    public static Optional<SourceEdit> rewrite(String source, String unitName, int line, int column)
        throws Exception {
        CompilationUnit unit = AstSupport.parse(source, unitName, true);
        if (AstSupport.hasErrors(unit)) return Optional.empty();
        int reported = AstSupport.offsetAt(unit, source, line, column);
        if (reported < 0) return Optional.empty();
        List<IfStatement> candidates = new ArrayList<>();
        unit.accept(new ASTVisitor() {
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
                || !(block.getParent() instanceof MethodDeclaration method)
                || method.getReturnType2() == null || !"int".equals(method.getReturnType2().toString())
                || !(statement.getThenStatement() instanceof Block thenBlock)
                || !(statement.getElseStatement() instanceof Block elseBlock)
                || thenBlock.statements().size() != 1 || elseBlock.statements().size() != 1
                || !(thenBlock.statements().getFirst() instanceof ReturnStatement thenReturn)
                || !(elseBlock.statements().getFirst() instanceof ReturnStatement elseReturn)
                || !(thenReturn.getExpression() instanceof NumberLiteral thenValue)
                || !(elseReturn.getExpression() instanceof NumberLiteral elseValue)
                || !thenValue.getToken().matches("(?:0|[1-9][0-9]*)")
                || !thenValue.getToken().equals(elseValue.getToken())
                || !(statement.getExpression() instanceof SimpleName guard)
                || AstSupport.containsRecovery(statement)) continue;
            boolean parameter = false;
            for (Object value : method.parameters()) {
                SingleVariableDeclaration candidate = (SingleVariableDeclaration) value;
                IVariableBinding binding = candidate.resolveBinding();
                if (candidate.getName().getIdentifier().equals(guard.getIdentifier())
                    && binding != null && !binding.isRecovered()
                    && binding.getType().isPrimitive()
                    && "boolean".equals(binding.getType().getName())
                    && guard.resolveBinding() instanceof IVariableBinding guardBinding
                    && guardBinding.getVariableDeclaration().getKey() != null
                    && guardBinding.getVariableDeclaration().getKey()
                        .equals(binding.getVariableDeclaration().getKey())) parameter = true;
            }
            if (!parameter) continue;
            int start = statement.getStartPosition();
            int end = start + statement.getLength();
            if (AstSupport.hasComment(unit, start, end)) continue;
            String replacement = source.substring(thenReturn.getStartPosition(),
                thenReturn.getStartPosition() + thenReturn.getLength());
            String updated = source.substring(0, start) + replacement + source.substring(end);
            if (AstSupport.hasErrors(AstSupport.parse(updated, unitName, true))) continue;
            return Optional.of(new SourceEdit(start, end, replacement));
        }
        return Optional.empty();
    }
}
