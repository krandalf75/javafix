package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NumberLiteral;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Rewrites size comparisons only for exact, directly-created JDK collection classes. */
public final class CollectionIsEmptyRepair {
    private CollectionIsEmptyRepair() {}

    public static Optional<SourceEdit> rewrite(String source, String unitName, int line, int column)
        throws Exception {
        CompilationUnit unit = AstSupport.parse(source, unitName, true);
        if (AstSupport.hasErrors(unit)) return Optional.empty();
        int reported = AstSupport.offsetAt(unit, source, line, column);
        if (reported < 0) return Optional.empty();
        List<InfixExpression> candidates = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override public boolean visit(InfixExpression node) {
                if (node.getStartPosition() <= reported
                    && reported < node.getStartPosition() + node.getLength()) candidates.add(node);
                return true;
            }
        });
        candidates.sort(java.util.Comparator.comparingInt(InfixExpression::getLength));
        for (InfixExpression comparison : candidates) {
            if (!comparison.extendedOperands().isEmpty()
                || !(comparison.getLeftOperand() instanceof MethodInvocation size)
                || !(comparison.getRightOperand() instanceof NumberLiteral zero)
                || !"0".equals(zero.getToken())
                || !(size.getExpression() instanceof ClassInstanceCreation created)
                || created.getAnonymousClassDeclaration() != null || !size.arguments().isEmpty()
                || !created.arguments().isEmpty()
                || !"size".equals(size.getName().getIdentifier())) continue;
            boolean equal = comparison.getOperator() == InfixExpression.Operator.EQUALS;
            boolean notEqual = comparison.getOperator() == InfixExpression.Operator.NOT_EQUALS;
            if (!equal && !notEqual) continue;
            var sizeBinding = size.resolveMethodBinding();
            var createdType = created.resolveTypeBinding();
            if (sizeBinding == null || sizeBinding.isRecovered() || createdType == null || createdType.isRecovered()
                || sizeBinding.getParameterTypes().length != 0
                || !"int".equals(sizeBinding.getReturnType().getName())) continue;
            String typeName = createdType.getTypeDeclaration().getQualifiedName();
            if (!typeName.equals("java.util.ArrayList") && !typeName.equals("java.util.HashSet")) continue;
            String methodOwner = sizeBinding.getMethodDeclaration().getDeclaringClass()
                .getTypeDeclaration().getQualifiedName();
            if (!typeName.equals(methodOwner) && !"java.util.AbstractCollection".equals(methodOwner)) continue;
            int start = comparison.getStartPosition();
            int end = start + comparison.getLength();
            if (AstSupport.containsRecovery(comparison) || AstSupport.hasComment(unit, start, end)) continue;
            String receiver = source.substring(created.getStartPosition(),
                created.getStartPosition() + created.getLength());
            String replacement = equal ? receiver + ".isEmpty()" : "(!" + receiver + ".isEmpty())";
            String changed = source.substring(0, start) + replacement + source.substring(end);
            CompilationUnit after = AstSupport.parse(changed, unitName, true);
            if (AstSupport.hasErrors(after) || !resolvesIsEmpty(after, start, typeName)) continue;
            return Optional.of(new SourceEdit(start, end, replacement));
        }
        return Optional.empty();
    }

    private static boolean resolvesIsEmpty(CompilationUnit unit, int start, String typeName) {
        final boolean[] found = {false};
        unit.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation invocation) {
                if (invocation.getStartPosition() < start || invocation.getStartPosition() > start + 8
                    || !"isEmpty".equals(invocation.getName().getIdentifier())) {
                    return true;
                }
                var method = invocation.resolveMethodBinding();
                found[0] = method != null && !method.isRecovered() && method.getParameterTypes().length == 0
                    && method.getReturnType().isPrimitive()
                    && "boolean".equals(method.getReturnType().getName())
                    && (typeName.equals(method.getMethodDeclaration().getDeclaringClass()
                        .getTypeDeclaration().getQualifiedName())
                        || "java.util.AbstractCollection".equals(method.getMethodDeclaration()
                            .getDeclaringClass().getTypeDeclaration().getQualifiedName()));
                return !found[0];
            }
        });
        return found[0];
    }
}
