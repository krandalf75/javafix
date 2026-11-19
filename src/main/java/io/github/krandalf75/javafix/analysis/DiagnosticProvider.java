package io.github.krandalf75.javafix.analysis;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface DiagnosticProvider {
    String id();

    Map<Path, List<ObjectNode>> analyze(List<Path> targets) throws IOException;
}
