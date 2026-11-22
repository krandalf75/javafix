# Implementation Plan: Verified Repairs for Java Warnings

## Goal and scope

Maximize the number of existing JavaFix warnings that can be corrected **without
introducing regressions**. This plan extends repair coverage, not the PMD
ruleset or the installer. A warning can remain valid even when JavaFix cannot
safely repair it. An unsupported case must keep its diagnostic and must not
gain a `repairId` or an automatic-fix flag. A disappearing warning, a successful
build, or an AI assessment alone is not evidence of behavior preservation.

Complete one work package at a time. Start with a failing fixture, implement the
narrow case, run the focused tests, then document the supported subset. Do not
expand a matcher to make an unrelated fixture pass. If a prerequisite cannot
be verified, record the blocker and leave that package unchecked.

**Completion gate:** the implementing model must produce a reproducible
[evidence packet](repair-evidence-template.md) for each package. A different
model must review the same code revision and write its own verdict. Until
that review is `APPROVED`, the package is not verified and must not be counted
in coverage or advertised as supported.

## Execution protocol: keep the Mac awake

- [ ] Before starting implementation or long-running tests on macOS, activate
  the built-in `caffeinate` utility so the machine does not sleep during the
  work. Run it as a tracked background process, record its exact PID, and
  install an exit/signal cleanup trap in the orchestrating shell:

  ```sh
  caffeinate -dimsu >/dev/null 2>&1 &
  CAFFEINATE_PID=$!
  trap 'kill "$CAFFEINATE_PID" 2>/dev/null || true' EXIT HUP INT TERM
  ```

- [ ] Keep that process active while implementing packages, running tests,
  producing evidence, and obtaining the independent review. Do not kill
  unrelated `caffeinate` processes; stop only the PID started for this plan.
- [ ] Once the plan is complete, blocked, or abandoned, explicitly deactivate
  the tracked process and verify it has exited. Ensure cleanup runs on failure
  or interruption. Record activation and deactivation in the handoff; do not
  leave the Mac awake indefinitely after work ends.
- [ ] On a non-macOS host or if `caffeinate` cannot be started, do not change
  system power settings or install a substitute implicitly. Record the blocker
  and ask whether to proceed without the keep-awake step.

| Order | Diagnostic | First supported case |
| --- | --- | --- |
| 1 | `PMD/ControlStatementBraces` | Wrap one unbraced `if` or loop body without changing the body text. |
| 2 | `PMD/CollapsibleIfStatements` | Merge two nested, braced `if` statements with no `else` and no intervening statements. |
| 3 | `PMD/UseStandardCharsets` | Replace a resolved `Charset.forName("…")` call for one of six standard names. |
| 4 | `PMD/UnnecessaryImport` | Reuse an existing, verified JDTLS unused-import deletion for the same import. |
| 5 | `PMD/UnusedLocalVariable` | Delete a single unused primitive local initialized by a matching literal. |
| 6 | `PMD/VariableCanBeInlined` | Reuse the verified immediate-return inline repair when both rules identify the same local. |
| 7 | `PMD/UseDiamondOperator` | Use `<>` only when inferred type arguments and constructor resolution are unchanged. |
| 8 | `PMD/UseCollectionIsEmpty` | Replace size checks only for freshly constructed, allowlisted JDK collections. |
| 9 | `PMD/UnconditionalIfStatement` | Retain the selected block of a literal `if/else`, preserving scope. |
| 10 | `PMD/IdenticalConditionalBranches` | Merge two identical literal returns guarded by a side-effect-free primitive boolean read. |
| 11 | `PMD/UnusedAssignment` | Delete a dead literal store immediately overwritten in the same block. |

Packages 1–6 are the first delivery; packages 7–11 require the additional
checks listed below. Continue after these packages with the diagnostic
inventory and coverage loop. Do not suppress warnings or reclassify existing
warnings to improve the coverage numbers.

## Current execution status

All eleven packages in this initial checklist have passed their evidence and
independent-review gates. This does not mean the rules are repaired for every
possible shape; each packet defines a conservative supported subset.

| Package | Implementation/test status | Remaining gate |
| --- | --- | --- |
| 1. Control braces | PMD-positive baseline/candidate CLI, reproducible integration test, preview/apply, runtime equivalence, multiline/comment rejection, and independent review ([packet](../repair-evidence/01-control-statement-braces.md)) | Approved |
| 2. Collapsible if | PMD-positive baseline/candidate CLI, reproducible integration test, preview/apply, short-circuit runtime equivalence, outer-else rejection, and independent review ([packet](../repair-evidence/02-collapsible-if.md)) | Approved |
| 3. Standard charset | Narrow matcher; baseline-red CLI comparison, packaged analyze/preview/apply/post-analysis, compile/runtime equivalence, rejected matcher cases, Java-level diagnostic-only gate, and independent review ([packet](../repair-evidence/03-standard-charsets.md)) | Approved |
| 4. Unnecessary import | Exact JDTLS action association; baseline-red, packaged preview/apply, static/wildcard/comment/disabled/multi-import negatives, and independent review ([packet](../repair-evidence/04-unnecessary-import.md)) | Approved |
| 5. Unused primitive local | Narrow matcher; binding/rejection matrix, compiled runtime equivalence, diagnostic-only reported negatives, packaged CLI preview/apply, and independent review ([packet](../repair-evidence/05-unused-primitive-local.md)) | Approved |
| 6. Variable can be inlined | Shared immediate-return action/ID; baseline-red, packaged preview/apply, Object/boxed-`Long` behavior, annotated-type guard, unsupported-shape evidence, and independent review ([packet](../repair-evidence/06-variable-can-be-inlined.md)) | Approved |
| 7. Diamond | Local-only binding matcher; baseline-red, packaged CLI preview/apply/runtime equivalence, annotated-type guard, explicit numeric per-module build-level gate, and independent review ([packet](../repair-evidence/07-diamond-operator.md)) | Approved |
| 8. Collection `isEmpty` | Direct-construction matcher; baseline-red, packaged preview/apply, runtime equality, unsupported-shape matrix, and independent review ([packet](../repair-evidence/08-collection-is-empty.md)) | Approved |
| 9. Unconditional `if` | Literal-only matcher; baseline-red, packaged preview/apply, runtime equality, rejected-shape evidence, and independent review ([packet](../repair-evidence/09-unconditional-if.md)) | Approved |
| 10. Identical returns | Literal-int/boolean-parameter matcher; baseline-red, packaged preview/apply, runtime equivalence, boxed-Boolean diagnostic-only evidence, and independent review ([packet](../repair-evidence/10-identical-literal-returns.md)) | Approved |
| 11. Unused assignment | Immediate literal-store matcher; baseline-red, packaged preview/apply, runtime equivalence, intervening-read/branch/compound/field/comment/side-effect rejection matrix, and independent review ([packet](../repair-evidence/11-unused-literal-assignment.md)) | Approved |

Shared source-coordinate tests and the `--expect-preview` gate have focused
coverage. The ACME ledger is in [repair-coverage.md](../repair-coverage.md).
The latest `mvn verify` passed after adding reproducible single-line-control
integration tests (100 tests; 0 failures/errors/skips). The focused
`PmdSingleLineControlRepairIntegrationTest` run passed (2 tests), as did the
18-test `VerifiedSimpleRepairsTest` run. Independent reviews approved all
eleven packages. Additional real-project sampling and further diagnostic
inventory work are outside this initial package set.

## 0. Prepare the shared path

- [ ] Use JDK 25+ for Maven. Run `mvn -version` before the first test run.
  Use the current PMD and JDTLS versions in `pom.xml`; do not update them to
  make a fixture pass.
- [ ] Read `Main.attachVerifiedPmdActions`, `Main.repairId`, `Main.lspPosition`,
  `Main.offset`, and `Main.repair` under `src/main/java/io/github/krandalf75/javafix/`.
  Use `LambdaMethodReferenceRepair` as the example of a binding-checked
  rewrite, `PmdInlineLocalRepairIntegrationTest` for the preview/apply assertions,
  and `CliProcessIntegrationTest` for the real CLI flow without a TTY.
- [ ] Before writing each matcher, use `PmdRuleProvider` in a test to capture
  the actual rule ID and reported node range on the fixture. PMD starts are
  1-based; its end is inclusive. JDT source offsets and LSP characters use
  UTF-16, and LSP ends are exclusive. Match the construct indicated by PMD,
  not the nearest construct on the same line. Test column mapping with tabs
  and non-ASCII text; if the mapping cannot be verified, decline the edit.
- [ ] Put the new AST matchers in `analysis/`, with one class per rule. A
  matcher takes the source snapshot and the diagnostic location and returns
  either no change or one replacement: start offset, exclusive end offset, and
  replacement text. It must not open or write a project file. Parse with JDT
  without statement recovery; reject malformed/recovered candidates. Use AST
  nodes to locate edits and source slices to preserve untouched text. Decline
  a repair if an affected comment cannot be preserved, including PMD suppression
  comments; do not search for comment markers inside string literals.
- [ ] For each new local rewrite, register the exact `ruleId` in
  `Main.attachVerifiedPmdActions`. Follow the existing snapshot guard: only
  attach after complete diagnostics and when the on-disk source still matches
  the snapshot. Check cancellation before and after computing the edit;
  propagate cancellation rather than treating it as an unsupported match.
- [ ] Build a localized `WorkspaceEdit.changes[uri]` from the replacement.
  Convert the two offsets with the LSP helper and assert they round-trip with
  `Main.offset`; reject invalid boundaries, including offsets inside a CRLF or
  a surrogate pair. Do not use a whole-document replacement for these repairs.
  Use `repairId(uri, title, diagnostic.range, workspaceEdit, sourceBytes)`.
- [ ] For locally generated edits, add `resolution.providerActions` entries with
  `source: "JavaFix"`, `kind: "quickfix"`, `workspaceEdit`, and `repairId`.
  Set `available`, `repairable`, `fixesDiagnostic`, `hasWorkspaceEdit`, and
  `resolution.automaticFixAvailable` to `true` only after all checks pass.
  Package 4 copies an existing `jdtlsActions` entry and package 6 reuses an
  existing inline implementation. Keep `schemaVersion: "1"` and use
  parameterless repair IDs for these packages.
- [ ] Keep file writes, overlap checks, and reanalysis in the existing repair
  flow. Do not add a write path, invoke interactive JDTLS commands, or make
  the CLI apply changes on `analyze` or bare `repair`.

## 1. Add braces to one control body

**Files:** `analysis/ControlStatementBracesRepair.java`,
`ControlStatementBracesRepairTest.java`, and
`PmdControlStatementBracesRepairIntegrationTest.java`.

```java
// Before
if (ready) run();

// After
if (ready) { run(); }
```

- [ ] Confirm PMD reports the child statement for an `if` branch and the loop
  node for `while`, `for`, enhanced `for`, and `do/while`. Match that exact
  AST node, then get the selected body (`then`, `else`, or loop `body`).
- [ ] Start with single-line bodies. Require one unambiguous body, not a block,
  `EmptyStatement`, `LabeledStatement`, or `IfStatement`. In particular, do not
  wrap an `else if`, move a label, or bind a dangling `else` to another `if`.
  Decline ambiguous comment placement, including a comment between the head and
  the selected body or trailing on the body's closing line.
- [ ] Replace just the body span with `{ ` + exact body source + ` }`.
  Reparse and verify the corresponding parent has one new block in the same
  branch/body slot, with the original statement as its sole child. Do not
  synthesize a semicolon or reindent the file.
- [ ] Unit-test `then`, plain `else`, `while`, basic/enhanced `for`, and
  `do/while`. Add two sibling findings on one line and assert that each repair
  targets only its own body. Add negatives for an existing block, `else if`,
  a dangling `else` shape, an empty/labeled body, a comment, and a multiline
  body.
- [ ] Integration-test an `if` and a loop in the same file: both localized
  edits can be previewed together. For a reported but declined case, use an
  empty loop body and assert PMD still reports it without an action.

## 2. Collapse two nested `if` statements

**Files:** `analysis/CollapsibleIfRepair.java`, `CollapsibleIfRepairTest.java`,
and `PmdCollapsibleIfRepairIntegrationTest.java`.

```java
// Before
if (a || b) { if (check()) { run(); } }

// After
if ((a || b) && (check())) { run(); }
```

- [ ] Match the reported **inner** `IfStatement` exactly. Require its parent
  to be the outer `if`'s then-`Block` and to contain exactly that one inner
  statement. Require neither `if` to have an `else`, and the inner then-body
  to be a `Block`. Do not support the unbraced form in this package.
- [ ] Decline if either condition contains an `instanceof` pattern, if the
  AST would be ambiguous, or if comments in the replaced outer subtree could
  be lost or moved. Keep the first implementation conservative: it may refuse
  all comments in that subtree. Do not flatten the inner block; declarations
  in it must keep their original scope.
- [ ] Build a new guard `(<outer condition>) && (<inner condition>)` from the
  two exact condition source slices, in that order. Replace the outer `if`
  with `if (` + guard + `) ` + the exact original inner block. Reparse and verify
  an `if` with no `else` and a top-level conditional AND.
- [ ] Test mixed `||`/`&&` precedence and side-effect ordering using a
  compiled fixture: if the outer condition is false, the inner condition is
  not called; if it is true, the inner condition is evaluated exactly once
  and the body only runs when both conditions are true. Add negatives for
  either `else`, extra outer statements, patterns, unbraced nested `if`s,
  comments, and selecting the outer instead of the reported inner position.
- [ ] Use an actually PMD-reported negative (e.g. unbraced nested `if`s) to
  verify detection remains available without a repair. If two findings
  produce overlapping edits, keep the transaction's existing rejection and
  instruct the user to apply one, then reanalyze before selecting the next.

## 3. Use a standard charset constant

**Files:** `analysis/StandardCharsetRepair.java`, `StandardCharsetRepairTest.java`,
and `PmdStandardCharsetRepairIntegrationTest.java`.

```java
// Before (Charset is java.nio.charset.Charset)
return Charset.forName("utf-8");

// After
return java.nio.charset.StandardCharsets.UTF_8;
```

- [ ] Match the PMD-reported `MethodInvocation` exactly. Require an explicit
  qualifier that resolves to the **type** `java.nio.charset.Charset` and a
  non-recovered method binding for static
  `java.nio.charset.Charset.forName(java.lang.String)` with return type
  `java.nio.charset.Charset`. Do not remove evaluation of an expression
  receiver, even if it resolves to the same static method.
- [ ] Require one `StringLiteral` argument. Map its decoded value using
  `Locale.ROOT` for case normalization. Whitelist these canonical names only:
  `US-ASCII → US_ASCII`, `ISO-8859-1 → ISO_8859_1`, `UTF-8 → UTF_8`,
  `UTF-16 → UTF_16`, `UTF-16BE → UTF_16BE`, `UTF-16LE → UTF_16LE`.
  Do not use the running JDK to infer arbitrary aliases, or suggest UTF-32
  constants based on the JavaFix runtime rather than the target project.
- [ ] Replace only the invocation with `java.nio.charset.StandardCharsets.`
  plus the selected field. Do not add/remove imports or rewrite callers that
  accept strings. Use the binding-enabled parse approach in
  `LambdaMethodReferenceRepair`; reparse the result and require the replacement
  to resolve to the expected public static final field declared by
  `java.nio.charset.StandardCharsets`, with type `Charset`. If shadowing
  (including a variable named `java`) or unresolved bindings prevent proof,
  decline. It is acceptable for the first version to decline units whose
  classpath cannot be resolved by the existing parser setup.
- [ ] Test all six mappings, including lowercase input, fully qualified
  original calls, and a same-file type or variable that would shadow a shorter
  name or the qualified replacement. Add negatives for a user-defined
  `Charset`, expression receiver, static import, unknown/alias name, constant
  variable, concatenation, constructor argument, `getBytes("UTF-8")`, and
  comments inside the invocation. Use at least one PMD-reported negative,
  such as a constant variable argument or `getBytes("UTF-8")`.
- [ ] Verify the target `UseStandardCharsets` warning disappears after
  apply. If the old `Charset` import becomes unused, leave it for an independent
  repair; do not report every remaining warning as fixed. Assert consistent
  charset results for representative non-ASCII input in a compiled fixture.

## 4. Associate a PMD unnecessary import with a verified JDTLS deletion

**Files:** `Main.java` (integration), `MainTest.java` (matcher and action-shape
tests), and `PmdUnnecessaryImportRepairIntegrationTest.java`.

```java
// Before: Set is unused; List is used.
import java.util.Set;
import java.util.List;
class Example { List<String> values; }

// After
import java.util.List;
class Example { List<String> values; }
```

- [ ] Confirm PMD reports `PMD/UnnecessaryImport` and the pinned JDTLS
  reports `UnusedImport` for the same fixture. Match the JDTLS source and
  native diagnostic code from `META-INF/jdtls-code-catalog.json`
  (`Java`, `268435844` at the time of writing; accept the observed JSON
  string or numeric representation). Do not match an English message.
- [ ] Require both diagnostic spans to identify the same JDT `ImportDeclaration`
  in the same file, and restrict the first version to imports that are
  neither static nor on-demand. Use the source snapshot and cancellation
  guards from package 0. Only accept an available JDTLS `quickfix` with
  `fixesDiagnostic: true`, a `repairId`, a direct `workspaceEdit`, no command,
  and no suppression.
- [ ] Inspect the actual JDTLS edit shape in the fixture. Only accept one
  text deletion in the target URI covering that import declaration, optionally
  including the indentation, trailing whitespace, and single line ending of
  a line containing only that import. Check the bounds against the snapshot.
  Reject comments in the import or on its line, any nonempty `newText`,
  additional edits/files, resource operations, and organize-imports actions.
- [ ] Copy the verified JDTLS action into the PMD diagnostic's
  `resolution.jdtlsActions`, preserving the original `repairId`. Set
  `hasJdtlsAction` and `automaticFixAvailable` only on a verified match.
  Do not create a second edit or ID. An agent selecting from both diagnostics
  must deduplicate this ID before writing the repair plan.
- [ ] Test a positive with a second, used import that must remain. Cover a
  nearby but different JDTLS diagnostic, unsupported code, missing/disabled
  action, organize-imports/multi-edit actions, comments, and wildcard/static
  imports. Include a PMD finding with no JDTLS equivalent (for example with
  the JDTLS unused-import warning disabled) and assert that PMD remains
  advisory. Integration-test that both providers' findings for the one
  selected import disappear and the used import remains unchanged.

## 5. Remove an unused primitive local

**Files:** `analysis/UnusedPrimitiveLocalRepair.java` and the corresponding
unit and PMD integration tests.

```java
int unused = 42; // Illustrative only: the matcher declines trailing comments.
```

- [ ] Locate the reported `VariableDeclarationFragment` by the exact range
  shape seen in the fixture. Require a `VariableDeclarationStatement` that
  is a direct child of a `Block`, with one fragment and no annotations,
  modifiers, extra dimensions, or attached comments.
- [ ] Initially accept only `int` initialized by an unsigned decimal integer
  literal in `int` range, or `boolean` initialized by a `BooleanLiteral`.
  Reject `var`, method calls, allocations, reads of fields, boxing, casts,
  and anything that could throw or perform effects. Do not emit the
  initializer as a new expression statement.
- [ ] Resolve the declaration and check references by binding identity in the
  enclosing executable, including nested lambdas and local/anonymous
  classes. A read **or** write blocks deletion; a same-spelled unrelated
  declaration does not count. Decline if a potential reference is unresolved.
- [ ] Delete exactly the statement; optionally consume indentation and one
  line ending if it is the only statement on that line. Check that the
  remaining source reparses. Leave `for` initializers and resource
  declarations unchanged.
- [ ] Test an unused `int` and `boolean`, a captured local, a write-only local
  assigned again, multiple declarations on one line, a comment, and a PMD
  finding with an initializer that increments a counter or throws. That
  last case must stay advisory and its initializer must remain untouched.

## 6. Reuse the verified inline-local repair

**Files:** `Main.java`, `MainTest.java`, and
`PmdVariableCanBeInlinedRepairIntegrationTest.java`.

- [ ] Identify the exact AST node reported by `PMD/VariableCanBeInlined`.
  Confirm a fixture such as `long result = 1; return result;` produces both
  that warning and `PMD/UnnecessaryLocalBeforeReturn`. Match both to the
  same local declaration and immediately following `return`; a shared line
  alone is not enough.
- [ ] After the existing inline action is generated, associate that same
  action and ID with the second PMD finding. Do not change the existing
  inliner's preconditions or synthesize another patch. If the action is not
  available, leave the new finding advisory regardless of diagnostic order.
- [ ] Verify one preview preserves `return (long) 1;` and that the result
  still returns a boxed `Long` when the return type is `Object`. Add
  reported but unsupported `var`, `throw` and `yield` cases, as well as
  a fixture with two separate findings on one line. Verify a deduplicated
  single-ID plan resolves both matched diagnostics.

## 7. Replace explicit constructor type arguments with `<>`

**Files:** `analysis/DiamondOperatorRepair.java` and the corresponding unit
and PMD integration tests.

```java
ArrayList<String> names = new ArrayList<String>();
// Becomes: ArrayList<String> names = new ArrayList<>();
```

- [ ] Require a single explicitly typed local whose initializer is a
  `ClassInstanceCreation` with no qualifier, arguments, explicit constructor
  type arguments, or anonymous body. Require the declared local type and
  constructed type to have the same resolved parameterized binding. Begin
  with one simple reference type argument; reject raw, wildcard, annotated,
  or unresolved types.
- [ ] Replace only the constructed type's explicit angle-bracket span with
  `<>`; do not touch the declaration type. Check there are no removed
  comments. Require a supported target source level (Java 7+), not just a
  JavaFix runtime capable of parsing diamond.
- [ ] Reparse with bindings and verify the same constructor declaration key,
  constructed generic declaration, and inferred type argument binding. An
  erasure-only match is insufficient. Reject method arguments, `var`,
  anonymous classes, and custom/generic cases with unresolved or different
  bindings; do not extend the first version to assignments or return sites.
- [ ] Compile the original and rewritten fixture at the same target level.
  Add a PMD-reported negative for a redundant explicit type argument in a
  context excluded above, such as a constructor with an argument. Assert
  only the local initializer type arguments were changed in the preview.

## 8. Replace a proven collection size check with `isEmpty()`

**Files:** `analysis/CollectionIsEmptyRepair.java` and the corresponding unit
and PMD integration tests.

```java
return new java.util.ArrayList<String>().size() == 0;
// Becomes: return new java.util.ArrayList<String>().isEmpty();
```

- [ ] Find the exact reported comparison or its `size` invocation, depending
  on the fixture. Require a two-operand `InfixExpression` with no extended
  operands. Start with `size() == 0` and `size() != 0`, in that order.
  Require the literal token `0` and a zero-argument `size()` call.
- [ ] Require a directly constructed, non-anonymous receiver of exact JDK
  `java.util.ArrayList` or `java.util.HashSet` type. Verify `size()` resolves
  to the expected JDK implementation. Do not infer runtime type from a
  variable declaration, cast, factory method, or return type.
- [ ] Preserve the entire receiver expression, evaluating it exactly once.
  For `!=`, replace the comparison with `(!receiver.isEmpty())` to preserve
  grouping in its enclosing expression. Resolve the rewritten invocation to
  the expected `isEmpty()` implementation; if it cannot be proven, decline.
- [ ] Compile and run empty/nonempty fixtures for both operators, including
  an `ArrayList` created from a collection-producing call with a call
  counter. Add a reported negative for a `List` parameter, plus negatives
  for anonymous/custom subclasses, other thresholds, comments, and
  unresolved bindings. Never call the original and rewritten expression in
  production code as a runtime equivalence check.

## 9. Simplify a literal `if/else`

**Files:** `analysis/UnconditionalIfRepair.java` and the corresponding unit
and PMD integration tests.

```java
if (false) { cold(); } else { hot(); }
// Becomes: { hot(); }
```

- [ ] Require the reported node to be an `IfStatement` directly inside a
  method-body `Block`, with a `BooleanLiteral` condition and two braced
  branches. Start with `true` and `false` only; do not evaluate arbitrary
  constant expressions or follow local variable assignments.
- [ ] Replace the entire `if` with the exact selected block, including its
  braces, so local scopes are not widened. Reject comments in the affected
  subtree in the first version. Reject if the new unit gains unreachable
  code, definite-assignment errors, or any other compiler errors when
  checked under the same language and compiler settings.
- [ ] Test both literal values and observable calls in the two branches.
  Add a reported negative for a literal with no `else` and another negative
  for a following statement made unreachable by a selected `return`. Include
  a name reused in a later block to verify that the surviving block keeps
  its scope.

## 10. Merge identical literal returns

**Files:** `analysis/IdenticalLiteralReturnsRepair.java` and the corresponding
unit and PMD integration tests.

```java
if (flag) { return 7; } else { return 7; }
// Becomes: return 7;
```

- [ ] First support only a reported `IfStatement` that is a direct child of a
  method body, with both branches containing one `ReturnStatement`. Require
  an explicit `int` return type and the same decimal integer literal token
  in both returns. Do not rewrite ternaries, compare branch text generally,
  or merge arbitrary expressions.
- [ ] Require the guard to be a `SimpleName` bound to a primitive `boolean`
  parameter of that method. Reading it cannot execute application code or
  throw. Reject unboxing, fields (including volatile fields), assignments,
  method calls, or unresolved bindings. Reject comments in the `if`.
- [ ] Replace the `if` with one exact `return` slice and compile the
  candidate. Run the fixture for both flag values. Test a reported negative
  with a guard that increments a counter and one with a boxed `Boolean`
  that may throw on unboxing. Confirm the original guard is not removed.

## 11. Remove a dead primitive store

**Files:** `analysis/UnusedLiteralAssignmentRepair.java` and the corresponding
unit and PMD integration tests.

```java
count = 1;
count = 2;
return count;
// Delete only the first assignment.
```

- [ ] Confirm PMD flags the first store rather than the declaration. Require
  two consecutive `ExpressionStatement` siblings in a `Block`, each
  containing a plain `=` assignment to the same resolved non-final
  primitive `int` local. A parameter, field, array element, compound
  assignment, or recovery binding is out of scope.
- [ ] Require both right-hand sides to be unsigned decimal integer literals
  in `int` range. This proves the first RHS has no effects or exceptions
  and the second RHS does not read the value. Neither statement can be a
  label target, and the second must execute immediately after the first.
  Decline comments associated with the deleted statement.
- [ ] Delete only the first statement. Reparse and verify the remaining
  assignment resolves to the same local. Compile and execute before and
  after; verify the same result and no change to the remaining store.
- [ ] Test negatives for an intervening read, branch, compound assignment,
  field, and RHS call. Use a PMD-reported negative where the dead store's
  RHS has a side effect, and verify no action is attached.

## 12. Grow and measure coverage across all reported diagnostics

- [ ] Create a coverage ledger in `docs/repair-coverage.md`. Seed it from
  `src/main/resources/pmd/javafix-rules.xml`, the observed JDTLS code
  catalog, and `SpringBootRuleProvider`. Give each diagnostic a row:
  provider/rule/code, counts from a fixed corpus, supported subset, positive
  and negative fixtures, and the reason other cases stay advisory. Mark
  existing repairs as verified only where the existing tests demonstrate it.
- [ ] Use completed analysis of the ACME Maven/Gradle fixtures and small
  targeted projects as the baseline. Add representative real projects only
  when available and authorized. Record total warning occurrences and
  occurrences with at least one verified repair, by provider and rule;
  also count unique repair actions by ID so linked diagnostics do not
  inflate that number. Keep detection unchanged between baseline and
  comparison. Do not count incomplete runs or planned packages as gains.
- [ ] For the next increment, pick the most frequent uncovered pattern
  with a small, provable rewrite. Repeat: record the exact warning and
  incorrect-code counterexample, isolate the supported AST shape, implement
  one matcher, add before/after regression evidence, and remeasure coverage.
  Extend one condition at a time (for example, add a new brace-body shape or
  a verified JDK overload), not an entire PMD rule at once.
- [ ] Keep *intent-changing* and *unproven* cases visible but separate from
  verified fixes. Examples: choosing whether `null` or an empty collection
  is correct, altering `equals(null)`, changing exception types/logging or
  `BigDecimal` rounding, changing Spring annotations, editing concurrency
  behavior, or modifying APIs that may have external callers. Report why
  they are not safe to rewrite mechanically and what contract/test would
  be needed to revisit them. Do not expose them as automatic repairs.
- [ ] Do not reuse a JDTLS edit merely because it has a repair ID. For more
  JDTLS-mediated coverage, repeat package 4's checks for each exact code
  and edit shape and require a positive and a counterexample. For wider
  try-with-resources coverage, specifically test primary/suppressed exceptions,
  close order, null resources, and existing `finally` side effects; without
  equivalent behavior keep the warning advisory.

## Acceptance checklist for each work package

- [ ] The report contains the expected **exact rule ID** and a repair only
  for the intended occurrence. At least one integration negative is still
  reported by PMD with no new automatic action. Unit tests also cover invalid
  ranges and two candidates on the same line.
- [ ] Use a temporary Maven project fixture with source at
  `src/main/java/sample/Example.java`, following the existing PMD integration
  tests. Use `{"schemaVersion":"1","repairs":["<reported repairId>"]}`.
  Assert that `repair` returns `preview`, lists the expected file and exact
  proposed content, and leaves the original bytes unchanged.
- [ ] Exercise the real reanalysis path for apply. Assert `status: "applied"`,
  complete `postAnalysis`, target warning removed at the edited occurrence,
  and no new compiler errors. If testing with two occurrences, assert the
  untouched one remains. Use `diagnosticChanges` as additional evidence, not
  as a substitute for inspecting the scope and result.
- [ ] Compile both fixture variants with the same target release and settings.
  Where behavior is observable, compare returns, exceptions and side effects
  for the cases named by the package. Add a counterexample that would catch
  an overbroad matcher. A build alone cannot prove behavioral equivalence.
- [ ] Preview selected edits together in at least one multi-warning test.
  If fixes overlap or interact, do not force the batch: apply one verified
  group, reanalyze, and generate new IDs before the next group. Verify that
  selecting conflicting, non-redundant edits fails without writing anything.
- [ ] Test stale repair IDs after changing source bytes: the real CLI returns
  code `2` and leaves the modified bytes alone. Cover incomplete analysis
  and snapshot mismatch without launching JDTLS where a controlled test
  suffices. Cover LF, CRLF, BOM, tabs, and non-ASCII input at least once in
  the shared suite; any deliberately unsupported format must keep the
  diagnostic and omit the repair, not edit the wrong span.
- [ ] Run the focused test pair (for example
  `mvn -Dtest=ControlStatementBracesRepairTest,PmdControlStatementBracesRepairIntegrationTest test`).
  Run `mvn clean verify` with JDK 25+ after the series. Report failures or
  blocked integration runs explicitly; do not mark them passed from a syntax
  check or a mocked apply alone.
- [ ] Publish the packet location in the handoff. The implementing model
  records actual outputs and conclusions, not expected output presented as
  a run. A skipped, mocked, incomplete, or blocked run cannot satisfy a
  required end-to-end check.
- [ ] Obtain an independent review using the template. The reviewer must
  reproduce at least one positive and one reported-but-rejected case and
  challenge the claimed behavior preservation with a counterexample. If the
  reviewed code, tests, fixtures, or build inputs change, refresh the evidence
  and review the changed revision.
- [ ] Update the verified-repair list in `README.md`, the supported subset in
  `.opencode/skills/javafix/SKILL.md`, and the coverage ledger only after the
  tests pass and the reviewer approves the package. Document when no fix is
  offered.

## Agent review checklist before applying repairs

- [ ] Treat the diagnostic and `fixesDiagnostic` flag as a proposal, not proof.
  Read the affected Java code, confirm the specific preconditions above, and
  record which findings are confirmed, uncertain, or outside the repair subset.
- [ ] Build a plan only for current, deduplicated IDs and review the preview
  for every affected path. Reject edits outside the requested file scope,
  unrelated formatting, or changes with uncertain behavior. If scope, plan,
  or source bytes change after review, regenerate and review the preview.
- [ ] For an authorized repair request, validate the **combined preview** in
  an isolated copy of the current project snapshot before writing originals.
  Include local changes that the build depends on; do not change the user's
  Git index or replace the worktree. Run the appropriate trusted Maven/Gradle
  build and impacted tests on the baseline and the candidate with identical
  settings. Check regressions in warnings, compile errors, and test results.
  If the project or tests cannot be run, report the blocker and keep the
  affected repairs as proposals rather than applying an unverified batch.
  If a batch fails, validate smaller groups in isolation instead of discarding
  every candidate. Recheck the remaining groups after each application.
- [ ] Make the validation reviewable: retain the affected paths, reviewed
  diff and hashes, commands and exit codes for baseline and candidate, and
  diagnostic differences. Link available results in the handoff. Use
  synthetic fixtures for shareable artifacts; do not publish private source,
  secrets, or private build logs.
- [ ] Immediately before apply, regenerate a read-only preview from the same
  plan and verify the affected paths and `beforeHash`/`afterHash` pairs match
  the validated preview. Stop on a mismatch; preservation of the reviewed
  bytes takes priority over the number of repairs applied. Until the CLI
  gate below is implemented, the skill-side comparison is not an atomic
  guarantee against concurrent writers.
- [ ] Stop after reporting if the user asked only for validation. Use `--apply`
  only for the reviewed changes the user explicitly requested or approved.
  Then check `postAnalysis`, review the actual diff, and report any warnings
  that remain or any checks that could not be completed.

## Follow-up: Bind application to the reviewed preview

- [ ] Add optional `repair --apply --expect-preview <result.json>` to
  `Main.Options` and help. Use the existing v1 preview result as input;
  do not change the repair-plan schema or remove existing manual CLI
  behavior. Reject the option without `--apply`.
- [ ] Before `writeTransaction`, compare the regenerated result with the
  expected preview: require v1 `preview` status, the same repair IDs, and
  the exact set of project-relative paths with matching `beforeHash` and
  `afterHash` values. Reject duplicate paths, malformed hashes, and
  mismatches with exit code `2` before any source is written. Do not use
  the supplied preview's `content` as a source of edits.
- [ ] Test an exact match, changed source bytes, a missing or extra path,
  a changed proposed hash, and an incomplete preview file. Verify all
  rejections leave the sources unchanged and that the existing transaction
  still rechecks source bytes before replacing files. Document the remaining
  race limit with non-cooperating writers. Once shipped, require this option
  for the skill's verified batch path.
