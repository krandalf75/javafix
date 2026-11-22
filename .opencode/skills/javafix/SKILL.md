---
name: JavaFix
description: Validate changed or all Java sources with JavaFix, review findings and repair previews, and apply only fixes verified by the AI and explicitly requested by the user
---

# Use JavaFix

Use this workflow when asked to validate Java code before committing/pushing, scan
a project, or repair Java findings. JavaFix requires Java 25 or newer; do not run
it with Java 17.

## Locate JavaFix and choose the scope

Use `JAVAFIX_JAR` if configured. Otherwise, use the precompiled JAR at
`~/.local/share/javafix/javafix.jar`, automatically downloading the latest
published GitHub Release when it is missing or out of date. Never build the
JavaFix source checkout to obtain the CLI.

For the default installation, resolve the latest release tag and refresh the JAR
only if its recorded version differs. Verify the published checksum and confirm
that the JAR starts before replacing the installed copy:

```sh
set -eu
if [ -n "${JAVAFIX_JAR:-}" ]; then
  JAVAFIX_BIN=$JAVAFIX_JAR
  test -s "$JAVAFIX_BIN" || { echo "JAVAFIX_JAR does not exist" >&2; exit 1; }
else
  JAVAFIX_HOME="${XDG_DATA_HOME:-$HOME/.local/share}/javafix"
  JAVAFIX_BIN="$JAVAFIX_HOME/javafix.jar"
  VERSION_FILE="$JAVAFIX_HOME/version"
  TAG=$(curl -fsSL https://api.github.com/repos/krandalf75/javafix/releases/latest |
    sed -n 's/^[[:space:]]*"tag_name":[[:space:]]*"\([^"]*\)".*/\1/p')
  [ -n "$TAG" ] || { echo "No JavaFix GitHub Release is available" >&2; exit 1; }
  if [ ! -s "$JAVAFIX_BIN" ] || [ "$(cat "$VERSION_FILE" 2>/dev/null || true)" != "$TAG" ]; then
    TMP_DIR=$(mktemp -d)
    trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM
    BASE="https://github.com/krandalf75/javafix/releases/download/$TAG"
    curl -fsSL "$BASE/javafix.jar" -o "$TMP_DIR/javafix.jar"
    curl -fsSL "$BASE/SHA256SUMS" -o "$TMP_DIR/SHA256SUMS"
    EXPECTED=$(awk '$2 == "javafix.jar" { print $1 }' "$TMP_DIR/SHA256SUMS")
    if command -v shasum >/dev/null 2>&1; then
      ACTUAL=$(shasum -a 256 "$TMP_DIR/javafix.jar" | awk '{print $1}')
    else
      ACTUAL=$(sha256sum "$TMP_DIR/javafix.jar" | awk '{print $1}')
    fi
    [ -n "$EXPECTED" ] && [ "$EXPECTED" = "$ACTUAL" ] || {
      echo "JavaFix JAR checksum verification failed" >&2; exit 1;
    }
    java -jar "$TMP_DIR/javafix.jar" --help >/dev/null
    mkdir -p "$JAVAFIX_HOME"
    mv "$TMP_DIR/javafix.jar" "$JAVAFIX_BIN"
    printf '%s\n' "$TAG" > "$VERSION_FILE"
  fi
fi
```

If GitHub has no published release yet or the download/checksum/startup check
fails, report that blocker; do not fall back to compiling JavaFix. Use
`java -jar "$JAVAFIX_BIN" ...` for all commands below.

For pre-commit validation, analyze Java files changed in the current Git worktree:

```sh
git diff --name-only --diff-filter=ACMR HEAD -- '*.java'
git ls-files --others --exclude-standard -- '*.java'
```

Combine and deduplicate both lists so staged, unstaged, and untracked Java files
are included. Exclude deleted files. Use the repository root (or its
`pom.xml`/Gradle descriptor) as `--project`, and pass the paths as `--files`.
If there are no changed Java files, report that and do not silently scan
everything.

For a full scan, omit `--files`; JavaFix then analyzes all discovered Java files:

```sh
java -jar "$JAVAFIX_BIN" analyze --project <project-root-or-build-file>
```

## Build and analyze

For changed files:

```sh
java -jar "$JAVAFIX_BIN" analyze \
  --project <project-root-or-build-file> \
  --files <changed-A.java> <changed-B.java>
```

Keep stdout as JSON. Check `status` and `summary.analysisComplete`. If analysis is
`incomplete`, do not treat missing diagnostics as proof that a problem is absent.

## Assess findings and preview candidate repairs

Do not equate a tool diagnostic with a confirmed defect. For every finding that
matters, inspect the relevant source and surrounding code, assess whether the
finding applies and whether the proposed change preserves behavior, and explain
uncertainty. Treat SpringBoot heuristics and any other advisory finding as a
suggestion, not proof.

For fixes, select only `repairId` values present in the current report. Prefer
actions whose `fixesDiagnostic` is `true`. Parameterized actions may not contain
a `workspaceEdit` until JavaFix validates the required plan parameters. JDTLS
actions appear in `resolution.jdtlsActions`; JavaFix-owned rewrites appear in
`resolution.providerActions`. Verified PMD repairs include `PMD/CloseResource`,
`PMD/UnnecessaryImport`, the restricted `PMD/UnnecessaryLocalBeforeReturn` and
`PMD/VariableCanBeInlined` immediate-return rewrites, and these bounded
transformations: `ControlStatementBraces`, `CollapsibleIfStatements`,
`UseStandardCharsets`, `UnusedLocalVariable`, `UseDiamondOperator`,
`UseCollectionIsEmpty`, `UnconditionalIfStatement`,
`IdenticalConditionalBranches`, and `UnusedAssignment`. These cover only the
documented narrow shapes; other findings, including unsupported shapes of
these rules, remain diagnostic-only. See the JavaFix repository's
`docs/plans/verified-warning-repairs.md` evidence ledger for each boundary.
Also verified are parameterized Extract Constant for
`PMD/AvoidDuplicateLiterals` and only binding-verified static calls for
`PMD/LambdaCanBeMethodReference`. Extract Method for PMD
`CognitiveComplexity`/`NcssCount` is an explicit refactoring, not an automatic
diagnostic fix.

Pass parameters only when the action declares `requiredParameters`. Extract
Constant requires `parameters.constantName`; Extract Method requires a
user-selected `parameters.selection` LSP range and `parameters.methodName`.
Write a plan using only current IDs:

```json
{
  "schemaVersion": "1",
  "repairs": [{"id": "<repairId>", "parameters": {"constantName": "MESSAGE"}}]
}
```

Preview only; preview does not write source files:

```sh
java -jar "$JAVAFIX_BIN" repair --project <project> --plan <plan.json>
```

## Apply only with explicit approval

Before applying, independently review every proposed file change in the preview
against the source and finding. Reject changes that are incorrect, unsafe,
outside scope, or not supported by the evidence; do not invent a replacement
patch under the guise of JavaFix. Report the proposed diff and assessment.

Only use `--apply` if the user explicitly asks for the changes or approves the
reviewed preview. If the user asked only to validate, report findings and stop:

```sh
java -jar "$JAVAFIX_BIN" repair --project <project> --plan <plan.json> --apply
```

Check that the result says `applied`; inspect `postAnalysis`, the resulting diff,
and run relevant tests/build checks when feasible. Report which findings
disappeared or remain; do not claim success solely because the command exited
successfully. JavaFix returns exit code `2` for usage, plan, or JDTLS errors. An
outdated `repairId`, overlapping edits, or an edit outside the project must be
treated as a rejected repair, not worked around by hand-editing the source.
