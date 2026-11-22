package io.github.krandalf75.javafix.analysis;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.WhileStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Adds braces to one conservative, single-line control-statement body. */
public final class ControlStatementBracesRepair {
    private ControlStatementBracesRepair() {}

    public static Optional<SourceEdit> rewrite(String source, String unitName, int line, int column)
        throws Exception {
        CompilationUnit unit = AstSupport.parse(source, unitName, false);
        if (AstSupport.hasErrors(unit)) return Optional.empty();
        int reportedStart = AstSupport.offsetAt(unit, source, line, column);
        if (reportedStart < 0) return Optional.empty();

        List<Body> candidates = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            private void add(ASTNode owner, Statement body) {
                if (body == null || body instanceof Block || body.getStartPosition() > reportedStart
                    || reportedStart >= body.getStartPosition() + body.getLength()) return;
                if (body instanceof IfStatement || body instanceof org.eclipse.jdt.core.dom.EmptyStatement
                    || body instanceof org.eclipse.jdt.core.dom.LabeledStatement) return;
                int start = body.getStartPosition();
                int end = start + body.getLength();
                if (start < 0 || end > source.length() || AstSupport.containsRecovery(body)
                    || unit.getLineNumber(start) != unit.getLineNumber(end - 1)
                    || AstSupport.hasComment(unit, start, end)) return;
                int lineStart = source.lastIndexOf('\n', Math.max(0, owner.getStartPosition())) + 1;
                int lineEnd = source.indexOf('\n', end);
                if (lineEnd < 0) lineEnd = source.length();
                if (lineEnd > end && source.substring(end, lineEnd).contains("//")) return;
                if (lineStart > owner.getStartPosition()) return;
                for (Object value : unit.getCommentList()) {
                    var comment = (org.eclipse.jdt.core.dom.Comment) value;
                    int commentStart = comment.getStartPosition();
                    if (commentStart >= owner.getStartPosition() && commentStart < lineEnd) return;
                }
                candidates.add(new Body(owner, body, start, end));
            }

            @Override public boolean visit(IfStatement node) {
                add(node, node.getThenStatement());
                add(node, node.getElseStatement());
                return true;
            }
            @Override public boolean visit(WhileStatement node) { add(node, node.getBody()); return true; }
            @Override public boolean visit(ForStatement node) { add(node, node.getBody()); return true; }
            @Override public boolean visit(DoStatement node) { add(node, node.getBody()); return true; }
        });
        if (candidates.size() != 1) return Optional.empty();
        Body candidate = candidates.getFirst();
        String bodyText = source.substring(candidate.start(), candidate.end());
        String changed = source.substring(0, candidate.start()) + "{ " + bodyText + " }"
            + source.substring(candidate.end());
        CompilationUnit after = AstSupport.parse(changed, unitName, false);
        if (AstSupport.hasErrors(after)) return Optional.empty();
        Statement newBody = bodyAt(after, candidate.owner().getStartPosition(), candidate.start());
        if (!(newBody instanceof Block block) || block.statements().size() != 1) return Optional.empty();
        Statement only = (Statement) block.statements().getFirst();
        if (!changed.substring(only.getStartPosition(), only.getStartPosition() + only.getLength())
            .equals(bodyText)) return Optional.empty();
        return Optional.of(new SourceEdit(candidate.start(), candidate.end(), "{ " + bodyText + " }"));
    }

    private static Statement bodyAt(CompilationUnit unit, int ownerStart, int bodyStart) {
        final Statement[] result = {null};
        unit.accept(new ASTVisitor() {
            private boolean same(ASTNode node) {
                return node.getStartPosition() == ownerStart;
            }
            @Override public boolean visit(IfStatement node) {
                if (same(node)) {
                    Statement body = node.getThenStatement().getStartPosition() == bodyStart
                        ? node.getThenStatement() : node.getElseStatement();
                    if (body != null && body.getStartPosition() == bodyStart) result[0] = body;
                }
                return result[0] == null;
            }
            @Override public boolean visit(WhileStatement node) {
                if (same(node) && node.getBody().getStartPosition() == bodyStart) result[0] = node.getBody();
                return result[0] == null;
            }
            @Override public boolean visit(ForStatement node) {
                if (same(node) && node.getBody().getStartPosition() == bodyStart) result[0] = node.getBody();
                return result[0] == null;
            }
            @Override public boolean visit(DoStatement node) {
                if (same(node) && node.getBody().getStartPosition() == bodyStart) result[0] = node.getBody();
                return result[0] == null;
            }
        });
        return result[0];
    }

    private record Body(ASTNode owner, Statement body, int start, int end) {}
}
