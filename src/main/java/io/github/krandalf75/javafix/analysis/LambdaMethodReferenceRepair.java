package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Converts only statically-qualified calls that forward every lambda parameter unchanged. */
public final class LambdaMethodReferenceRepair {
    private LambdaMethodReferenceRepair() {}

    public static Optional<String> rewriteAtOffset(String source, int targetOffset) throws Exception {
        return rewriteAtOffset(source, "JavaFix.java", targetOffset);
    }

    public static Optional<String> rewriteAtOffset(String source, String unitName, int targetOffset)
        throws Exception {
        if (targetOffset < 0 || targetOffset >= source.length()) return Optional.empty();
        Map<String, String> options = new HashMap<>(JavaCore.getOptions());
        JavaCore.setComplianceOptions(JavaCore.VERSION_25, options);
        CompilationUnit original = parse(source, unitName, options);
        for (var problem : original.getProblems()) {
            if (problem.isError()) return Optional.empty();
        }

        List<Candidate> candidates = new ArrayList<>();
        original.accept(new ASTVisitor() {
            @Override
            public boolean visit(LambdaExpression lambda) {
                int start = lambda.getStartPosition();
                if (targetOffset < start || targetOffset >= start + lambda.getLength()) return true;
                Candidate candidate = candidate(lambda);
                if (candidate != null) candidates.add(candidate);
                return true;
            }
        });
        if (candidates.size() != 1) return Optional.empty();

        Candidate candidate = candidates.getFirst();
        AST ast = original.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        TypeMethodReference reference = ast.newTypeMethodReference();
        reference.setType(ast.newSimpleType((Name) rewrite.createCopyTarget(candidate.receiver())));
        reference.setName(ast.newSimpleName(candidate.invocation().getName().getIdentifier()));
        rewrite.replace(candidate.lambda(), reference, null);
        Document document = new Document(source);
        TextEdit edit = rewrite.rewriteAST(document, options);
        edit.apply(document);
        String updated = document.get();

        CompilationUnit reparsed = parse(updated, unitName, options);
        MethodReference[] replacement = {null};
        reparsed.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeMethodReference node) {
                if (node.getStartPosition() == candidate.lambda().getStartPosition()) replacement[0] = node;
                return true;
            }

            @Override
            public boolean visit(ExpressionMethodReference node) {
                if (node.getStartPosition() == candidate.lambda().getStartPosition()) {
                    replacement[0] = node;
                }
                return true;
            }
        });
        if (replacement[0] == null) return Optional.empty();
        IMethodBinding resolved = replacement[0].resolveMethodBinding();
        if (resolved == null || resolved.getMethodDeclaration().getKey() == null
            || !candidate.methodKey().equals(resolved.getMethodDeclaration().getKey())) {
            return Optional.empty();
        }
        return source.equals(updated) ? Optional.empty() : Optional.of(updated);
    }

    private static Candidate candidate(LambdaExpression lambda) {
        if (!(lambda.getBody() instanceof MethodInvocation invocation)
            || !(invocation.getExpression() instanceof Name receiver)
            || !invocation.typeArguments().isEmpty()
            || invocation.arguments().size() != lambda.parameters().size()) {
            return null;
        }
        IBinding receiverBinding = receiver.resolveBinding();
        IMethodBinding method = invocation.resolveMethodBinding();
        if (!(receiverBinding instanceof ITypeBinding receiverType) || method == null
            || !Modifier.isStatic(method.getModifiers()) || method.isRecovered()
            || method.getTypeParameters().length != 0) {
            return null;
        }
        String methodKey = method.getMethodDeclaration().getKey();
        if (methodKey == null) return null;

        List<VariableDeclaration> parameters = new ArrayList<>();
        for (Object parameter : lambda.parameters()) {
            if (!(parameter instanceof VariableDeclaration declaration)) return null;
            parameters.add(declaration);
        }
        List<?> arguments = invocation.arguments();
        for (int index = 0; index < parameters.size(); index++) {
            if (!(arguments.get(index) instanceof SimpleName argument)) return null;
            IBinding argumentBinding = argument.resolveBinding();
            IVariableBinding parameterBinding = parameters.get(index).resolveBinding();
            if (!(argumentBinding instanceof IVariableBinding argumentVariable) || parameterBinding == null) {
                return null;
            }
            String argumentKey = argumentVariable.getVariableDeclaration().getKey();
            String parameterKey = parameterBinding.getVariableDeclaration().getKey();
            if (argumentKey == null || !argumentKey.equals(parameterKey)) return null;
        }
        return new Candidate(lambda, receiver, invocation, methodKey);
    }

    private static CompilationUnit parse(String source, String unitName, Map<String, String> options) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setUnitName(unitName);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(false);
        parser.setEnvironment(null, null, null, true);
        parser.setCompilerOptions(options);
        return (CompilationUnit) parser.createAST(null);
    }

    private record Candidate(LambdaExpression lambda, Name receiver, MethodInvocation invocation,
                             String methodKey) {}
}
