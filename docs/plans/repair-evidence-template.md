# Evidence Packet: JavaFix Warning Repair

Copy this template for each work package in
[Verified Repairs for Java Warnings](verified-warning-repairs.md). Store the
filled report in `docs/repair-evidence/<package>-<rule>.md`. Link exact test
methods and retained CI artifacts or local outputs the reviewer can access.
Keep large logs and generated fixtures out of Git. Include enough small,
sanitized excerpts and reproduction instructions to review the result even
if an artifact expires. Do not publish credentials or private source.

## Implementer: scope and reproduction

- **Package and diagnostic:** exact provider and rule ID or code.
- **Input under review:** baseline commit and candidate commit. If uncommitted,
  give the base commit and a complete, accessible patch or snapshot, including
  new fixtures and tests. State which tree each command used.
- **Environment:** OS; exact Java, Maven, PMD, and JDTLS versions; fixture target
  release; non-default build settings required to reproduce.
- **Keep-awake lifecycle:** on macOS, record that the plan's `caffeinate` PID
  was started and stopped, and confirm it exited. If not applicable or blocked,
  state why and whether work proceeded with user approval.
- **Supported shape and exclusions:** code locations for the matcher and its
  guards. Explain why the rewrite preserves the relevant values, types, scopes,
  exceptions, and evaluation order. Name the most likely regression and the
  test that would catch it.
- **Runs:** fill in each applicable row. Attach actual output excerpts and
  links to logs or reports. Use `PASS`, `FAIL`, `BLOCKED`, or `NOT RUN`;
  explain any claim of non-applicability. An expectation or assertion in the
  test source is not a test result.

| Check | Exact command, working directory, and test method | Observed exit code, result, and evidence |
| --- | --- | --- |
| Red: new regression test on the baseline | | Expected assertion failure for the missing repair, not setup failure. Use a CLI-level test if a new helper does not exist on the baseline. |
| Green: same test on the candidate | | Observed pass; record test counts, including skipped tests. |
| Positive CLI: `analyze → repair` without `--apply` `→ repair --apply` on a disposable fixture | | Exact initial diagnostic and selected `repairId`; plan; preview diff and hashes; original bytes unchanged after preview; applied bytes match preview; complete post-analysis showing the target occurrence gone and no new compiler errors. |
| Reported but rejected case | | Exact diagnostic is still present, matching finding has no new action or ID, and fixture bytes are unchanged. |
| Behavior comparison at the same target release | | Before/after observations for the package's relevant return values, exceptions, side effects, or bindings; connect each observation to a test. |
| Failure and transaction checks | | Stale/conflict status as applicable and matching original source hashes on rejection; link the relevant shared checks if reused. |
| Focused tests and full verification | | Test summaries and exit codes for the required focused run and `mvn clean verify` against the final series, or explicit blockers. |

- **Remaining risks or unsupported cases:**
- **Status:** `READY FOR REVIEW` or `BLOCKED`. Do not claim independent approval.

## Independent reviewer: reproduce and challenge

- [ ] Record reviewer/model identity, reviewed commit or patch, and environment.
  Verify that the cited artifacts refer to that exact candidate.
- [ ] Inspect the matcher, guards, fixture assertions, and preview diff.
  Confirm tests assert the specific occurrence and behavior, not just a
  successful exit or a lower warning count.
- [ ] Rerun the positive CLI flow and a reported-but-rejected case on disposable
  fixtures. Record the exact commands, exit codes, and your observed outputs.
- [ ] Challenge the rewrite with at least one counterexample you choose
  independently of the implementer. Record its code or test reference, the
  failure mode it probes, and whether JavaFix declines it or preserves the
  expected behavior. Request a regression test if it exposes a gap.
- [ ] Record `APPROVED`, `CHANGES REQUIRED`, or `BLOCKED`, with reasons and links
  to the evidence. Missing mandatory evidence, a skipped rerun, or a failing
  safety check cannot yield `APPROVED`.
