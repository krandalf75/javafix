package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Removes only an unreferenced, side-effect-free int/boolean local declaration. */
public final class UnusedPrimitiveLocalRepair {
    private UnusedPrimitiveLocalRepair() {}

    public static Optional<SourceEdit> rewrite(String source, String unitName, int line, int column)
        throws Exception {
        CompilationUnit unit = AstSupport.parse(source, unitName, true);
        if (AstSupport.hasErrors(unit)) return Optional.empty();
        int offset = AstSupport.offsetAt(unit, source, line, column);
        if (offset < 0) return Optional.empty();
        List<VariableDeclarationFragment> candidates = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override public boolean visit(VariableDeclarationFragment node) {
                if (node.getStartPosition() <= offset
                    && offset < node.getStartPosition() + node.getLength()) candidates.add(node);
                return true;
            }
        });
        if (candidates.size() != 1) return Optional.empty();
        VariableDeclarationFragment fragment = candidates.getFirst();
        if (!(fragment.getParent() instanceof VariableDeclarationStatement declaration)
            || declaration.fragments().size() != 1 || !(declaration.getParent() instanceof Block)
            || fragment.getInitializer() == null || fragment.getExtraDimensions() != 0
            || !declaration.modifiers().isEmpty()
            || AstSupport.containsRecovery(declaration)) return Optional.empty();
        if (!(declaration.getType() instanceof PrimitiveType primitive)
            || !(primitive.getPrimitiveTypeCode() == PrimitiveType.INT
                || primitive.getPrimitiveTypeCode() == PrimitiveType.BOOLEAN)
            || !primitive.annotations().isEmpty()
            || !safeLiteral(fragment.getInitializer().toString(), primitive.getPrimitiveTypeCode())) {
            return Optional.empty();
        }
        int start = declaration.getStartPosition();
        int end = start + declaration.getLength();
        if (AstSupport.hasComment(unit, start, end)
            || AstSupport.hasLeadingComment(unit, source, start)) return Optional.empty();
        IVariableBinding binding = fragment.resolveBinding();
        if (binding == null || binding.isRecovered() || binding.getVariableDeclaration().getKey() == null) {
            return Optional.empty();
        }
        String key = binding.getVariableDeclaration().getKey();
        final boolean[] referenced = {false};
        unit.accept(new ASTVisitor() {
            @Override public boolean visit(SimpleName name) {
                if (name == fragment.getName()) return true;
                if (name.resolveBinding() instanceof IVariableBinding variable
                    && key.equals(variable.getVariableDeclaration().getKey())) referenced[0] = true;
                return !referenced[0];
            }
        });
        if (referenced[0]) return Optional.empty();

        int lineStart = source.lastIndexOf('\n', start - 1) + 1;
        int lineEnd = source.indexOf('\n', end);
        if (lineEnd < 0) lineEnd = source.length();
        if (lineEnd > end && source.charAt(lineEnd - 1) == '\r') lineEnd--;
        if (AstSupport.hasComment(unit, start, lineEnd)) return Optional.empty();
        boolean soleLine = source.substring(lineStart, start).isBlank()
            && source.substring(end, lineEnd).isBlank();
        if (soleLine) {
            int deleteEnd = source.indexOf('\n', lineEnd);
            if (deleteEnd < 0) deleteEnd = source.length();
            else deleteEnd++;
            return Optional.of(new SourceEdit(lineStart, deleteEnd, ""));
        }
        return Optional.of(new SourceEdit(start, end, ""));
    }

    private static boolean safeLiteral(String text, PrimitiveType.Code type) {
        if (type == PrimitiveType.BOOLEAN) return "true".equals(text) || "false".equals(text);
        if (!text.matches("(?:0|[1-9][0-9]*)")) return false;
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException outOfRange) {
            return false;
        }
    }
}
