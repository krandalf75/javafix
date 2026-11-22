# Repair coverage baseline

Fixed corpus: the checked-in `acme/maven` and `acme/gradle` fixtures, analyzed
with the current PMD ruleset and JDTLS 1.61.0. Reproduce with:

```sh
java -jar target/javafix-0.2.0.jar analyze --project acme/maven
java -jar target/javafix-0.2.0.jar analyze --project acme/gradle
```

Captured on macOS with Java 25.0.3 and Maven 3.10.1, from the packaged
`0.2.0` artifact. Full JSON was retained locally as
`target/acme-maven-analysis.json` and `target/acme-gradle-analysis.json`.
Both runs completed. Maven: 2 files, 8 diagnostics (7 warnings, 1 error);
Gradle: 1 file, 6 diagnostics (6 warnings, 0 errors). The Maven error is the
intentional broken-syntax fixture and remains advisory.

| Corpus | Provider counts | Diagnostics with `automaticFixAvailable` | Distinct repair IDs |
| --- | --- | ---: | ---: |
| ACME Maven | Java 5, PMD 3, SpringBoot 0 | 6 | 7 |
| ACME Gradle | Java 3, PMD 3, SpringBoot 0 | 5 | 5 |

Counts above describe analysis output, not a promise that every offered JDTLS
action is behavior-preserving for every project. IDs are counted uniquely
across linked findings; several actions can belong to one diagnostic.

## Diagnostic inventory for the fixed corpus

Maven and Gradle columns are occurrence counts. `0` means the rule did not
appear in these fixtures; it does not imply the rule is disabled. “Narrow”
means a matcher exists with evidence; it is not a claim that every occurrence
of that PMD rule is repairable. Unsupported shapes remain diagnostic-only.

| Provider / diagnostic | Maven | Gradle | Repair evidence / status |
| --- | ---: | ---: | --- |
| PMD/AvoidPrintStackTrace | 0 | 0 | Advisory; no new matcher |
| PMD/PreserveStackTrace | 0 | 0 | Advisory; no new matcher |
| PMD/SwitchStmtsShouldHaveDefault | 0 | 0 | Advisory; no new matcher |
| PMD/UnusedAssignment | 0 | 0 | Narrow literal overwrite matcher; targeted baseline/candidate CLI, preview/apply, runtime and rejection evidence; approved |
| PMD/UnusedLocalVariable | 1 | 0 | Narrow unused primitive literal matcher; unit + preview/apply batch |
| PMD/UnusedPrivateField | 0 | 0 | Advisory; no new matcher |
| PMD/UnusedPrivateMethod | 0 | 0 | Advisory; no new matcher |
| PMD/UseCollectionIsEmpty | 0 | 1 | Narrow direct JDK construction matcher; unit evidence; corpus case declined |
| PMD/UseStandardCharsets | 0 | 0 | Narrow allowlist matcher; unit + preview/apply batch |
| PMD/UseTryWithResources | 0 | 0 | Advisory; no equivalent edit established |
| PMD/SystemPrintln | 0 | 0 | Advisory; intent-dependent |
| PMD/JUnitTestsShouldIncludeAssert | 0 | 0 | Advisory; intent-dependent |
| PMD/AssignmentInOperand | 0 | 0 | Advisory; no new matcher |
| PMD/AssertEqualsArgumentOrder | 0 | 0 | Advisory; no new matcher |
| PMD/AvoidCatchingGenericException | 0 | 0 | Advisory; API/behavior dependent |
| PMD/AvoidDecimalLiteralsInBigDecimalConstructor | 0 | 0 | Advisory; rounding semantics |
| PMD/BrokenNullCheck | 0 | 0 | Advisory; no new matcher |
| PMD/CloseResource | 1 | 1 | Existing JDTLS try-with-resources association; tested separately |
| PMD/EmptyCatchBlock | 0 | 0 | Advisory; intent-dependent |
| PMD/EqualsNull | 0 | 0 | Advisory; intent-dependent |
| PMD/UnconditionalIfStatement | 0 | 0 | Literal branch matcher; targeted packaged CLI, preview/apply, runtime and rejection evidence; approved |
| PMD/IdenticalConditionalBranches | 0 | 0 | Primitive-boolean/int-return matcher; targeted packaged CLI, preview/apply, runtime and diagnostic-only evidence; approved |
| PMD/ReturnEmptyCollectionRatherThanNull | 0 | 0 | Advisory; API contract dependent |
| PMD/UnusedReturnValue | 0 | 0 | Advisory; intent-dependent |
| PMD/UselessPureMethodCall | 0 | 0 | Advisory; no new matcher |
| PMD/AvoidDuplicateLiterals | 0 | 0 | Existing parameterized Extract Constant action |
| PMD/AvoidDeeplyNestedIfStmts | 0 | 0 | Advisory; structural intent required |
| PMD/AvoidThrowingNullPointerException | 0 | 0 | Advisory; intent-dependent |
| PMD/AvoidThrowingRawExceptionTypes | 0 | 0 | Advisory; API/behavior dependent |
| PMD/CollapsibleIfStatements | 0 | 0 | Narrow short-circuit-preserving matcher; targeted packaged CLI evidence; approved |
| PMD/CognitiveComplexity | 0 | 0 | Existing explicit-selection Extract Method action |
| PMD/CyclomaticComplexity | 0 | 0 | Advisory; explicit refactoring scope needed |
| PMD/NcssCount | 0 | 0 | Existing explicit-selection Extract Method action |
| PMD/SignatureDeclareThrowsException | 0 | 0 | Advisory; API contract dependent |
| PMD/UseUtilityClass | 0 | 0 | Advisory; API/instantiation intent dependent |
| PMD/ControlStatementBraces | 0 | 0 | Narrow statement-body matcher; targeted packaged CLI evidence; approved |
| PMD/LambdaCanBeMethodReference | 0 | 0 | Existing binding-checked narrow rewrite |
| PMD/ClassNamingConventions | 0 | 0 | Advisory; renaming/API impact |
| PMD/FieldNamingConventions | 0 | 0 | Advisory; API impact |
| PMD/FormalParameterNamingConventions | 0 | 0 | Advisory; API impact |
| PMD/LocalVariableNamingConventions | 0 | 0 | Advisory; style-only |
| PMD/MethodNamingConventions | 0 | 0 | Advisory; API impact |
| PMD/UnnecessaryLocalBeforeReturn | 0 | 0 | Existing narrow immediate-return rewrite; integration-tested |
| PMD/UnnecessaryImport | 1 | 1 | Same verified JDTLS action/ID; preview/apply integration-tested |
| PMD/UseDiamondOperator | 0 | 0 | Narrow local binding-equivalent matcher; focused unit evidence |
| PMD/VariableCanBeInlined | 0 | 0 | Reuses the exact `UnnecessaryLocalBeforeReturn` action and ID |
| PMD/AvoidInstantiatingObjectsInLoops | 0 | 0 | Advisory; allocation behavior dependent |
| PMD/InefficientStringBuffering | 0 | 0 | Advisory; no new matcher |
| PMD/UseStringBufferForStringAppends | 0 | 0 | Advisory; no new matcher |
| PMD/HardCodedCryptoKey | 0 | 0 | Advisory; security intent required |
| PMD/InsecureCryptoIv | 0 | 0 | Advisory; security intent required |
| PMD/DoubleCheckedLocking | 0 | 0 | Advisory; concurrency-sensitive |
| Java/268435844 (`UnusedImport`) | 1 | 1 | Same JDTLS action linked to the matching PMD import |
| Java/536871799 (`UnclosedCloseable`) | 1 | 1 | Existing JDTLS quick-fix integration |
| Java/536870973 (`LocalVariableIsNeverUsed`) | 2 | 1 | JDTLS actions observed; not counted as a new PMD primitive matcher |
| Java/1610612967 (`ParsingErrorInsertTokenAfter`) | 1 | 0 | Diagnostic-only syntax error |
| SpringBoot/FieldInjection | 0 | 0 | Advisory heuristic; no automatic repair |
| SpringBoot/PrivateTransactionalMethod | 0 | 0 | Advisory heuristic; no automatic repair |
| SpringBoot/ConfigurationValidation | 0 | 0 | Advisory heuristic; no automatic repair |
| SpringBoot/GetMappingRequestBody | 0 | 0 | Advisory heuristic; no automatic repair |

## Limitations and next measurement

- The fixed ACME fixtures do not exercise the two single-line-control rules;
  targeted PMD-positive packaged CLI fixtures now provide evidence for those
  matchers, but corpus occurrence counts remain zero.
- ACME fixtures do not contain every PMD diagnostic. Targeted fixtures provide
  positive counts for several matchers, but this ledger does not extrapolate
  those counts into the corpus totals.
- Broader real-project sampling has not been authorized or performed.
- The eleven repair packages in the initial implementation checklist have
  independent approval; additional matchers require the same evidence gate.
