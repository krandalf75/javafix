package io.github.krandalf75.javafix.analysis;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One localized, snapshot-relative source replacement. */
public record SourceEdit(int start, int end, String replacement) {
    public SourceEdit {
        if (start < 0 || end < start || replacement == null) {
            throw new IllegalArgumentException("Invalid source edit.");
        }
    }

    public ObjectNode workspaceEdit(String uri, String source) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode edits = result.putObject("changes").putArray(uri);
        ObjectNode textEdit = edits.addObject();
        ObjectNode range = textEdit.putObject("range");
        range.set("start", position(source, start));
        range.set("end", position(source, end));
        textEdit.put("newText", replacement);
        return result;
    }

    private static ObjectNode position(String source, int offset) {
        if (offset > source.length()
            || (offset > 0 && offset < source.length()
                && Character.isHighSurrogate(source.charAt(offset - 1))
                && Character.isLowSurrogate(source.charAt(offset)))) {
            throw new IllegalArgumentException("Source edit splits a UTF-16 code point.");
        }
        int line = 0;
        int lineStart = 0;
        for (int index = 0; index < offset; index++) {
            char value = source.charAt(index);
            if (value == '\r') {
                if (index + 1 < source.length() && source.charAt(index + 1) == '\n') {
                    if (offset == index + 1) throw new IllegalArgumentException("Source edit splits CRLF.");
                    index++;
                }
                line++;
                lineStart = index + 1;
            } else if (value == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        return JsonNodeFactory.instance.objectNode()
            .put("line", line).put("character", offset - lineStart);
    }
}
