---
name: irij-test
description: Run Irij test suite (Java + integration) and report results
---

# /irij-test

Run the Irij test suite. Accepts optional arguments:

- `/irij-test` — run all Java tests + all integration tests
- `/irij-test java` — run only Java tests (./gradlew test --rerun)
- `/irij-test integration` — run only integration tests (irij test)
- `/irij-test tests/test-json.irj` — run a specific integration test file

## Steps

1. If a specific file is given, run `irij test <file>` and report results.
2. If `java` is given, run `./gradlew test --rerun` and report results.
3. If `integration` is given, run `./gradlew install && irij test` and report results.
4. Otherwise (no args), run both:
   - `./gradlew test --rerun` (Java unit tests)
   - `./gradlew install && irij test` (integration tests)
5. Report total pass/fail counts. If any failures, show the failing test names and relevant error messages.
6. Do NOT modify any code. Just report results.
