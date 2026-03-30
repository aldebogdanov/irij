---
name: irij-builtin
description: Add a new builtin function to the Irij interpreter
---

# /irij-builtin

Guide for adding a new builtin function to Irij. Argument: the builtin name and a brief description.

Example: `/irij-builtin to-upper — converts string to uppercase`

## Steps

1. **Add the builtin** to `src/main/java/dev/irij/interpreter/Builtins.java`:
   - Choose the right section (I/O, string, math, collection, etc.)
   - Determine arity and argument types
   - Decide if it needs effect tagging (e.g., `List.of("Console")` for I/O builtins)
   - Use `asString()`, `asLong()`, `asDouble()` helpers for type checking
   - Return `Values.UNIT` for side-effecting void operations

2. **Add Java unit tests** to `src/test/java/dev/irij/interpreter/InterpreterTest.java`:
   - Find the appropriate `@Nested` class or create one
   - Test normal cases, edge cases, and error cases
   - Use `run()` for output tests, `eval()` for value tests, `assertRuntimeError()` for error tests

3. **Add integration tests** to the appropriate `tests/test-*.irj` file:
   - Follow the existing pattern: `rN := test "name" (-> assert-eq expected actual)`
   - Remember: no `:=` bindings inside `(-> ...)` thunks — use helper `fn` if needed
   - Update the `summarize` call at the bottom

4. **Build and verify**: `./gradlew test --rerun && ./gradlew install && irij test`

5. **Update docs** if the builtin is part of a std module or a significant new feature.

## Key files
- `src/main/java/dev/irij/interpreter/Builtins.java` — builtin definitions
- `src/main/java/dev/irij/interpreter/Values.java` — value types
- `src/test/java/dev/irij/interpreter/InterpreterTest.java` — Java tests
- `tests/test-*.irj` — integration tests
