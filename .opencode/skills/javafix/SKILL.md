---
name: JavaFix
description: Analyze Java projects and safely preview or apply JDTLS repairs using JavaFix plans
---

# Use JavaFix

Use this workflow when asked to inspect or repair a Java project with JavaFix.
JavaFix requires Java 25 or newer; do not run it with Java 17.

## Build and analyze

1. If the executable JAR is missing or stale, build it with Java 25:

   ```sh
   mvn clean verify
   ```

2. Analyze the requested project. Keep stdout as JSON:

   ```sh
   java -jar target/javafix-0.1.0.jar analyze --project <pom.xml|build.gradle|directory>
   ```

3. Check `status` and `summary.analysisComplete`. If analysis is `incomplete`, do
   not treat missing diagnostics as proof that a problem is absent.

## Select and preview repairs

1. Select only `repairId` values actually present in the report. Prefer actions
   whose `fixesDiagnostic` is `true` and which include a direct `workspaceEdit`.
   A PMD finding is repairable only when its report explicitly contains a linked
   JDTLS action; currently the verified PMD link is `PMD/CloseResource`.
2. Do not invent edits, action IDs, or refactoring parameters. This version does
   not support commands requiring follow-up interaction or parameters such as
   `constantName`.
3. Write a plan using the selected IDs:

   ```json
   {
     "schemaVersion": "1",
     "repairs": ["<repairId>"]
   }
   ```

4. Preview by default:

   ```sh
   java -jar target/javafix-0.1.0.jar repair \
     --project <project> --plan <plan.json>
   ```

   Confirm `status` is `preview`; review every path and proposed content before
   applying. Preview does not write source files.

## Apply only with explicit approval

Only use `--apply` when the user explicitly requests or approves applying the
selected repairs:

```sh
java -jar target/javafix-0.1.0.jar repair \
  --project <project> --plan <plan.json> --apply
```

Check that the result says `applied` and inspect `postAnalysis`. Report which
findings disappeared or remain; do not claim success solely because the command
exited successfully. JavaFix returns exit code `2` for usage, plan, or JDTLS
errors. An outdated `repairId`, overlapping edits, or an edit outside the
project must be treated as a rejected repair, not worked around by hand-editing
the source.
