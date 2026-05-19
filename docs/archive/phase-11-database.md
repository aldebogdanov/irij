# Phase 11 — Database Effect (SQLite)

## Overview

Phase 11 adds embedded database support via SQLite (sqlite-jdbc). The design follows Irij's effect system: raw builtins provide the actual JDBC operations, while `std.db` wraps them in an algebraic effect (`Db`) that can be mocked in tests.

## Architecture

### Raw Builtins (Builtins.java + Interpreter.java)

| Builtin | Arity | Description |
|---------|-------|-------------|
| `raw-db-open` | 1 | Opens SQLite connection (path or `:memory:`), enables WAL mode |
| `raw-db-query` | 3 | `conn sql params` -> IrijVector of IrijMaps |
| `raw-db-exec` | 3 | `conn sql params` -> Long (affected row count) |
| `raw-db-close` | 1 | Closes connection |
| `raw-db-transaction` | 2 | `conn thunk` -> runs thunk in BEGIN/COMMIT, ROLLBACK on error |

Raw builtins have **no effect tags** because handler clause bodies cannot have the effect they implement.

### Connection Representation

Connections are stored as `Tagged("DbConn", List.of(connection))` where the `java.sql.Connection` object is opaque to Irij code. Helper `extractConnection()` validates the Tagged wrapper.

### Type Mapping (ResultSet -> Irij)

| SQL Type | Irij Type |
|----------|-----------|
| INTEGER | Long |
| REAL | Double |
| TEXT/VARCHAR | Str |
| NULL | Unit `()` |

### Transaction Semantics

`raw-db-transaction` uses `synchronized(conn)` for thread safety:
1. `setAutoCommit(false)`
2. Apply thunk
3. On success: `commit()`, `setAutoCommit(true)`
4. On error: `rollback()`, `setAutoCommit(true)`, rethrow

## std.db Module

```irj
mod std.db

pub effect Db
  db-query :: _ Str _ -> _
  db-exec :: _ Str _ -> Int
  db-transaction :: _ Fn -> _

pub fn db-open :: Str _
  (path -> raw-db-open path)

pub fn db-close :: _ ()
  (conn -> raw-db-close conn)

pub handler default-db :: Db
  db-query conn sql params =>
    resume (raw-db-query conn sql params)
  db-exec conn sql params =>
    resume (raw-db-exec conn sql params)
  db-transaction conn thunk =>
    resume (raw-db-transaction conn (-> db-tx-wrapper thunk))
```

### Transaction Effect Re-establishment

The `db-transaction` handler clause wraps the user's thunk in `db-tx-wrapper`, which re-establishes `with default-db` inside the transaction. Without this, effect ops called inside the transaction thunk would fail with "no handler on stack" since the handler clause body runs outside the handled scope.

## Usage

```irj
use std.db :open

conn := db-open ":memory:"
with default-db
  db-exec conn "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INT)" #[]
  db-exec conn "INSERT INTO users (name, age) VALUES (?, ?)" #["Alice" 30]
  users := db-query conn "SELECT * FROM users WHERE age > ?" #[21]
  ;; users = #[{id= 1 name= "Alice" age= 30}]

  ;; Transactions: auto-commit on success, rollback on error
  db-transaction conn (->
    db-exec conn "UPDATE accounts SET balance = balance - 100 WHERE id = ?" #[1]
    db-exec conn "UPDATE accounts SET balance = balance + 100 WHERE id = ?" #[2])

db-close conn
```

### Mocking in Tests

```irj
handler mock-db :: Db
  db-query conn sql params =>
    resume #[{name= "mock-alice" age= 99}]
  db-exec conn sql params =>
    resume 0
  db-transaction conn thunk =>
    resume (thunk ())

with mock-db
  users := fetch-users "fake-conn"
  ;; returns mock data, no real DB
```

## Test Coverage

- **Java tests**: 12 tests in `DbBuiltinTest.java`
- **Integration tests**: 11 tests in `tests/test-db.irj`
- Total test suite: 643 Java + 264 integration = 907 tests

## Files Modified/Created

- `build.gradle.kts` — added `org.xerial:sqlite-jdbc:3.47.1.0`
- `Builtins.java` — `raw-db-open`, `raw-db-query`, `raw-db-exec`, `raw-db-close` + helpers
- `Interpreter.java` — `raw-db-transaction` + module forwarding list
- `src/main/resources/std/db.irj` — new module
- `src/test/java/.../DbBuiltinTest.java` — Java tests
- `tests/test-db.irj` — integration tests

## Future Work

- JDBC abstraction / HikariCP / PostgreSQL (deferred until after JVM interop phase)
- Migration system
- Query builder DSL
