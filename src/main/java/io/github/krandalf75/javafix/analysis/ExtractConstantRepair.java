package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import javax.lang.model.SourceVersion;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Extracts one selected string literal into a private static final field. */
public final class ExtractConstantRepair {
    private ExtractConstantRepair() {}

    public static boolean isValidName(String name) {
        return name != null && SourceVersion.isIdentifier(name)
            && !SourceVersion.isKeyword(name, SourceVersion.latest());
    }

    public static boolean canExtractAtOffset(String source, int offset) throws Exception {
        for (int suffix = 0; suffix < 100; suffix++) {
            if (rewriteAtOffset(source, offset, "JAVA_FIX_CONSTANT_" + suffix).isPresent()) return true;
        }
        return false;
    }

    public static Optional<String> rewriteAtOffset(String source, int offset, String name) throws Exception {
        if (!isValidName(name) || offset < 0 || offset >= source.length()) return Optional.empty();
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setUnitName("ExtractConstant.java");
        parser.setResolveBindings(false);
        Map<String, String> options = new HashMap<>(JavaCore.getOptions());
        JavaCore.setComplianceOptions(JavaCore.VERSION_25, options);
        parser.setCompilerOptions(options);
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        for (var problem : unit.getProblems()) {
            if (problem.isError()) return Optional.empty();
        }

        StringLiteral[] selected = {null};
        TypeDeclaration[] owner = {null};
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(StringLiteral node) {
                if (node.getStartPosition() == offset) {
                    selected[0] = node;
                    for (var parent = node.getParent(); parent != null; parent = parent.getParent()) {
                        if (parent instanceof TypeDeclaration declaration) {
                            owner[0] = declaration;
                            break;
                        }
                    }
                }
                return true;
            }
        });
        if (selected[0] == null || owner[0] == null || owner[0].isInterface()
            || hasNameCollision(owner[0], name)) {
            return Optional.empty();
        }
        String value = selected[0].getLiteralValue();
        if (literalOccurrences(owner[0], value) < 2) return Optional.empty();

        AST ast = unit.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName(name));
        fragment.setInitializer((StringLiteral) rewrite.createCopyTarget(selected[0]));
        FieldDeclaration field = ast.newFieldDeclaration(fragment);
        field.setType(ast.newSimpleType(ast.newSimpleName("String")));
        field.modifiers().add(ast.newModifier(org.eclipse.jdt.core.dom.Modifier.ModifierKeyword.PRIVATE_KEYWORD));
        field.modifiers().add(ast.newModifier(org.eclipse.jdt.core.dom.Modifier.ModifierKeyword.STATIC_KEYWORD));
        field.modifiers().add(ast.newModifier(org.eclipse.jdt.core.dom.Modifier.ModifierKeyword.FINAL_KEYWORD));
        rewrite.getListRewrite(owner[0], TypeDeclaration.BODY_DECLARATIONS_PROPERTY).insertFirst(field, null);
        owner[0].accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                return node == owner[0];
            }

            @Override
            public boolean visit(StringLiteral node) {
                if (value.equals(node.getLiteralValue())) {
                    rewrite.replace(node, ast.newSimpleName(name), null);
                }
                return true;
            }
        });

        Document document = new Document(source);
        TextEdit edit = rewrite.rewriteAST(document, options);
        edit.apply(document);
        String updated = document.get();
        return source.equals(updated) ? Optional.empty() : Optional.of(updated);
    }

    private static boolean hasNameCollision(TypeDeclaration owner, String name) {
        final boolean[] collision = {false};
        owner.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (name.equals(node.getIdentifier())) collision[0] = true;
                return !collision[0];
            }

        });
        return collision[0];
    }

    private static int literalOccurrences(TypeDeclaration owner, String value) {
        int[] count = {0};
        owner.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                return node == owner;
            }

            @Override
            public boolean visit(StringLiteral node) {
                if (value.equals(node.getLiteralValue())) count[0]++;
                return true;
            }
        });
        return count[0];
    }
}
