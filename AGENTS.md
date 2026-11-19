# Project direction

- This repository is an early-stage Java code-correction CLI intended for AI use; it should use JDTLS to analyze Java sources and help fix common Java warnings.
- The CLI should accept either a list of Java files or a directory, report issues per class, and expose additional repair tools.
- The CLI is a Maven Java 25 artifact that bundles JDTLS 1.61.0; compiling and running it requires Java 25. `mvn clean verify` builds/tests it and downloads the pinned JDTLS distribution.
- The initial integration fixtures live under `acme/maven` and `acme/gradle`; keep expected diagnostics reproducible when changing JDTLS versions or compiler-warning settings.
