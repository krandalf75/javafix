package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A deliberately narrow AST rewrite for a local used only by the immediately following return. */
public final class InlineLocalRepair {
    private InlineLocalRepair() {}

    public static Optional<String> rewriteAtLine(String source, int targetLine) throws Exception {
        if (targetLine < 1) return Optional.empty();
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setUnitName("InlineLocal.java");
        parser.setResolveBindings(false);
        Map<String, String> options = new HashMap<>(JavaCore.getOptions());
        JavaCore.setComplianceOptions(JavaCore.VERSION_25, options);
        parser.setCompilerOptions(options);
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        for (var problem : unit.getProblems()) {
            if (problem.isError()) return Optional.empty();
        }

        List<Candidate> candidates = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(Block block) {
                List<?> statements = block.statements();
                for (int index = 0; index + 1 < statements.size(); index++) {
                    if (!(statements.get(index) instanceof VariableDeclarationStatement declaration)
                        || !(statements.get(index + 1) instanceof ReturnStatement returnStatement)) continue;
                    if (unit.getLineNumber(declaration.getStartPosition()) != targetLine) continue;
                    Candidate candidate = candidate(source, declaration, returnStatement);
                    if (candidate != null) candidates.add(candidate);
                }
                return true;
            }
        });
        if (candidates.size() != 1) return Optional.empty();

        Candidate candidate = candidates.get(0);
        AST ast = unit.getAST();
        var rewrite = org.eclipse.jdt.core.dom.rewrite.ASTRewrite.create(ast);
        rewrite.remove(candidate.declaration(), null);
        CastExpression cast = ast.newCastExpression();
        cast.setType((Type) rewrite.createCopyTarget(candidate.declaration().getType()));
        cast.setExpression((Expression) rewrite.createCopyTarget(candidate.initializer()));
        rewrite.replace(candidate.returnStatement().getExpression(), cast, null);

        Document document = new Document(source);
        TextEdit edit = rewrite.rewriteAST(document, options);
        edit.apply(document);
        String updated = document.get();
        return source.equals(updated) ? Optional.empty() : Optional.of(updated);
    }

    private static Candidate candidate(String source, VariableDeclarationStatement declaration,
                                       ReturnStatement returnStatement) {
        if (declaration.fragments().size() != 1
            || !(declaration.fragments().get(0) instanceof VariableDeclarationFragment fragment)
            || fragment.getInitializer() == null || fragment.getExtraDimensions() != 0
            || declaration.getType().toString().equals("var")
            || fragment.getInitializer().getNodeType() == ASTNode.ARRAY_INITIALIZER
            || !(returnStatement.getExpression() instanceof SimpleName returnedName)
            || !fragment.getName().getIdentifier().equals(returnedName.getIdentifier())) return null;
        for (Object modifier : declaration.modifiers()) {
            if (modifier instanceof Annotation || !(modifier instanceof Modifier)) return null;
        }
        boolean[] annotatedType = {false};
        declaration.getType().accept(new ASTVisitor() {
            private boolean found() {
                annotatedType[0] = true;
                return false;
            }

            @Override public boolean visit(MarkerAnnotation annotation) { return found(); }
            @Override public boolean visit(NormalAnnotation annotation) { return found(); }
            @Override public boolean visit(SingleMemberAnnotation annotation) { return found(); }
        });
        if (annotatedType[0]) return null;

        MethodDeclaration method = enclosingMethod(declaration);
        if (method == null || method.getBody() == null || enclosingMethod(returnStatement) != method) return null;
        String name = fragment.getName().getIdentifier();
        int[] references = {0};
        method.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (name.equals(node.getIdentifier())) references[0]++;
                return true;
            }
        });
        if (references[0] != 2) return null; // declaration and the one return use only

        int start = declaration.getStartPosition();
        int end = returnStatement.getStartPosition() + returnStatement.getLength();
        if (start < 0 || end > source.length() || end < start) return null;
        String affectedText = source.substring(start, end);
        if (affectedText.contains("//") || affectedText.contains("/*")) return null;
        return new Candidate(declaration, fragment.getInitializer(), returnStatement);
    }

    private static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof MethodDeclaration method) return method;
        }
        return null;
    }

    private record Candidate(VariableDeclarationStatement declaration, Expression initializer,
                             ReturnStatement returnStatement) {}
}
