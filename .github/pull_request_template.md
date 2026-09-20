<!-- Use a Conventional Commit title, e.g. fix(http): reject invalid due dates. -->

## Summary

<!-- Describe the problem and resulting behavior. Include a before/after example when useful. -->

## Changes

<!-- List the changes a reviewer needs to understand. Omit this section for simple PRs. -->

## Validation

<!-- Describe the checks run and their results. If none, explain why.
Examples:
- ./auto/check              (hermetic: every unit and HTTP spec, plus the format check)
- ./auto/check --with-db    (adds the PostgreSQL integration suite; matches CI)
- scripts/smoke.sh          (end-to-end HTTP checks against a running instance)

Prefer ./auto/check over a hand-typed sbt invocation: a bare `sbt test` can skip
work in sbt 2, and `TODO_TEST_DB=1 sbt ...` is unreliable because the sbt server
pins its environment at startup.
-->

## Breaking changes or migrations

<!-- Describe any API, configuration, or database changes requiring action.
Include migration steps, or remove this section if not applicable. -->

## Related issues

<!-- Link relevant issues, e.g. Closes #123, or remove this section. -->
