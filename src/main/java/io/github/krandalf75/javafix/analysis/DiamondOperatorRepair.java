package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Adds diamond only for one simple, binding-identical local constructor expression. */
public final class DiamondOperatorRepair {
    private DiamondOperatorRepair() {}

    public static Optional<SourceEdit> rewrite(String source, String unitName, int line, int column,
                                              int targetSourceLevel)
        throws Exception {
        if (targetSourceLevel < 7) return Optional.empty();
        CompilationUnit unit = AstSupport.parse(source, unitName, true);
        if (AstSupport.hasErrors(unit)) return Optional.empty();
        int reported = AstSupport.offsetAt(unit, source, line, column);
        if (reported < 0) return Optional.empty();
        List<ClassInstanceCreation> candidates = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override public boolean visit(ClassInstanceCreation node) {
                int start = node.getStartPosition();
                if (start <= reported && reported < start + node.getLength()) candidates.add(node);
                return true;
            }
        });
        if (candidates.isEmpty()) return Optional.empty();
        candidates.sort(java.util.Comparator.comparingInt(ClassInstanceCreation::getLength));
        ClassInstanceCreation creation = candidates.getFirst();
        if (creation.getStartPosition() < 0 || creation.getExpression() != null
            || creation.getAnonymousClassDeclaration() != null || !creation.arguments().isEmpty()
            || !creation.typeArguments().isEmpty() || AstSupport.containsRecovery(creation)
            || !(creation.getType() instanceof ParameterizedType parameterized)
            || parameterized.typeArguments().size() != 1
            || !(parameterized.typeArguments().getFirst() instanceof SimpleType simpleArgument)
            || containsAnnotation(simpleArgument)
            || AstSupport.hasComment(unit, parameterized.getStartPosition(),
                parameterized.getStartPosition() + parameterized.getLength())
            || !(creation.getParent() instanceof VariableDeclarationFragment fragment)
            || fragment.getInitializer() != creation
            || !(fragment.getParent() instanceof VariableDeclarationStatement declaration)
            || declaration.fragments().size() != 1
            || !(declaration.getParent() instanceof org.eclipse.jdt.core.dom.Block)) return Optional.empty();
        IVariableBinding variable = fragment.resolveBinding();
        ITypeBinding declaredType = variable == null ? null : variable.getType();
        ITypeBinding createdType = creation.resolveTypeBinding();
        var constructor = creation.resolveConstructorBinding();
        if (variable == null || variable.isRecovered() || declaredType == null || createdType == null
            || constructor == null || constructor.isRecovered() || constructor.getMethodDeclaration().getKey() == null
            || declaredType.getTypeDeclaration().getKey() == null
            || !declaredType.getKey().equals(createdType.getKey())
            || !(simpleArgument.resolveBinding() instanceof ITypeBinding argumentType)
            || argumentType.isRecovered() || argumentType.getTypeDeclaration().getKey() == null) {
            return Optional.empty();
        }
        int typeStart = parameterized.getStartPosition();
        int typeEnd = typeStart + parameterized.getLength();
        String typeSource = source.substring(typeStart, typeEnd);
        int open = typeSource.indexOf('<');
        int close = typeSource.lastIndexOf('>');
        if (open < 0 || close <= open || typeSource.indexOf('<', open + 1) >= 0
            || typeSource.indexOf('>', open) != close) return Optional.empty();
        int start = typeStart + open;
        int end = typeStart + close + 1;
        String updated = source.substring(0, start) + "<>" + source.substring(end);
        CompilationUnit after = AstSupport.parse(updated, unitName, true);
        if (AstSupport.hasErrors(after)) return Optional.empty();
        int expectedStart = creation.getStartPosition();
        final boolean[] verified = {false};
        after.accept(new ASTVisitor() {
            @Override public boolean visit(ClassInstanceCreation rewritten) {
                if (rewritten.getStartPosition() != expectedStart
                    || !(rewritten.getType() instanceof ParameterizedType diamond)
                    || !diamond.typeArguments().isEmpty()) return true;
                var afterConstructor = rewritten.resolveConstructorBinding();
                ITypeBinding afterType = rewritten.resolveTypeBinding();
                verified[0] = afterConstructor != null && !afterConstructor.isRecovered()
                    && constructor.getMethodDeclaration().getKey()
                        .equals(afterConstructor.getMethodDeclaration().getKey())
                    && afterType != null && !afterType.isRecovered()
                    && createdType.getKey().equals(afterType.getKey());
                return true;
            }
        });
        if (!verified[0]) return Optional.empty();
        return Optional.of(new SourceEdit(start, end, "<>"));
    }

    private static boolean containsAnnotation(org.eclipse.jdt.core.dom.ASTNode node) {
        boolean[] found = {false};
        node.accept(new ASTVisitor() {
            private boolean markFound() {
                found[0] = true;
                return false;
            }

            @Override public boolean visit(MarkerAnnotation annotation) { return markFound(); }
            @Override public boolean visit(NormalAnnotation annotation) { return markFound(); }
            @Override public boolean visit(SingleMemberAnnotation annotation) { return markFound(); }
        });
        return found[0];
    }
}
