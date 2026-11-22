package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;

/** Replaces a resolved Charset.forName call only for the six standard constants. */
public final class StandardCharsetRepair {
    private static final Map<String, String> CONSTANTS = Map.of(
        "US-ASCII", "US_ASCII",
        "ISO-8859-1", "ISO_8859_1",
        "UTF-8", "UTF_8",
        "UTF-16", "UTF_16",
        "UTF-16BE", "UTF_16BE",
        "UTF-16LE", "UTF_16LE");

    private StandardCharsetRepair() {}

    public static Optional<SourceEdit> rewrite(String source, String unitName, int line, int column)
        throws Exception {
        CompilationUnit unit = AstSupport.parse(source, unitName, true);
        if (AstSupport.hasErrors(unit)) return Optional.empty();
        int reportedOffset = AstSupport.offsetAt(unit, source, line, column);
        if (reportedOffset < 0) return Optional.empty();
        final MethodInvocation[] found = {null};
        unit.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation node) {
                int start = node.getStartPosition();
                int end = start + node.getLength();
                if (start <= reportedOffset && reportedOffset < end
                    && (found[0] == null || node.getLength() < found[0].getLength())) found[0] = node;
                return true;
            }
        });
        MethodInvocation call = found[0];
        if (call == null || !(call.getExpression() instanceof Name receiver)
            || !(call.arguments().size() == 1 && call.arguments().getFirst() instanceof StringLiteral literal)
            || AstSupport.containsRecovery(call)) return Optional.empty();
        IBinding receiverBinding = receiver.resolveBinding();
        IMethodBinding method = call.resolveMethodBinding();
        if (!(receiverBinding instanceof ITypeBinding receiverType)
            || !"java.nio.charset.Charset".equals(receiverType.getTypeDeclaration().getQualifiedName())
            || method == null || method.isRecovered() || !"forName".equals(method.getName())
            || !Modifier.isStatic(method.getModifiers())
            || !"java.nio.charset.Charset".equals(method.getDeclaringClass().getTypeDeclaration().getQualifiedName())
            || method.getParameterTypes().length != 1
            || !"java.lang.String".equals(method.getParameterTypes()[0].getErasure().getQualifiedName())
            || !"java.nio.charset.Charset".equals(method.getReturnType().getErasure().getQualifiedName())) {
            return Optional.empty();
        }
        String normalizedName = asciiUppercase(literal.getLiteralValue());
        String field = normalizedName == null ? null : CONSTANTS.get(normalizedName);
        if (field == null || AstSupport.hasComment(unit, call.getStartPosition(),
            call.getStartPosition() + call.getLength())) return Optional.empty();

        String replacement = "java.nio.charset.StandardCharsets." + field;
        String changed = source.substring(0, call.getStartPosition()) + replacement
            + source.substring(call.getStartPosition() + call.getLength());
        CompilationUnit after = AstSupport.parse(changed, unitName, true);
        if (AstSupport.hasErrors(after)) return Optional.empty();
        int expectedStart = call.getStartPosition();
        final boolean[] verified = {false};
        after.accept(new ASTVisitor() {
            @Override public boolean visit(QualifiedName node) {
                if (node.getStartPosition() != expectedStart || !replacement.equals(node.toString())) return true;
                IBinding binding = node.resolveBinding();
                if (!(binding instanceof IVariableBinding variable) || variable.isRecovered()) return true;
                ITypeBinding declaring = variable.getDeclaringClass();
                ITypeBinding type = variable.getType();
                verified[0] = variable.isField() && Modifier.isStatic(variable.getModifiers())
                    && Modifier.isFinal(variable.getModifiers()) && declaring != null
                    && "java.nio.charset.StandardCharsets".equals(declaring.getTypeDeclaration().getQualifiedName())
                    && "java.nio.charset.Charset".equals(type.getErasure().getQualifiedName());
                return true;
            }
        });
        if (!verified[0]) return Optional.empty();
        return Optional.of(new SourceEdit(call.getStartPosition(), call.getStartPosition() + call.getLength(),
            replacement));
    }

    private static String asciiUppercase(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character > 0x7f) return null;
            normalized.append(character >= 'a' && character <= 'z'
                ? (char) (character - ('a' - 'A')) : character);
        }
        return normalized.toString();
    }
}
