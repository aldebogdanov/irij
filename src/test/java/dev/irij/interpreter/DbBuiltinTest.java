package dev.irij.interpreter;

import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.Values.*;
import dev.irij.parser.IrijParseDriver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 11 database builtins (SQLite).
 */
class DbBuiltinTest {

    private String run(String source) {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var parseResult = IrijParseDriver.parse(source + "\n");
        assertFalse(parseResult.hasErrors(),
            () -> "Parse errors: " + parseResult.errors());
        var ast = new AstBuilder().build(parseResult.tree());
        var interp = new Interpreter(out);
        interp.setSpecLintEnabled(false);
        interp.run(ast);
        return baos.toString().strip();
    }

    private Object eval(String source) {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var parseResult = IrijParseDriver.parse(source + "\n");
        assertFalse(parseResult.hasErrors(),
            () -> "Parse errors: " + parseResult.errors());
        var ast = new AstBuilder().build(parseResult.tree());
        var interp = new Interpreter(out);
        interp.setSpecLintEnabled(false);
        return interp.run(ast);
    }

    // ═══════════════════════════════════════════════════════════════════
    // raw-db-open
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void openInMemoryDb() {
        var result = eval("raw-db-open \":memory:\"");
        assertInstanceOf(Tagged.class, result);
        assertEquals("DbConn", ((Tagged) result).tag());
    }

    // ═══════════════════════════════════════════════════════════════════
    // raw-db-exec + raw-db-query round-trip
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void createTableAndInsert() {
        var output = run("""
            conn := raw-db-open ":memory:"
            raw-db-exec conn "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)" #[]
            raw-db-exec conn "INSERT INTO t (name, age) VALUES (?, ?)" #["Alice" 30]
            raw-db-exec conn "INSERT INTO t (name, age) VALUES (?, ?)" #["Bob" 25]
            rows := raw-db-query conn "SELECT * FROM t ORDER BY id" #[]
            println (length rows)
            println (head rows).name
            println (head (tail rows)).age
            raw-db-close conn
            """);
        assertEquals("2\nAlice\n25", output);
    }

    @Test
    void queryReturnsCorrectTypes() {
        var output = run("""
            conn := raw-db-open ":memory:"
            raw-db-exec conn "CREATE TABLE t (i INTEGER, r REAL, s TEXT)" #[]
            raw-db-exec conn "INSERT INTO t VALUES (?, ?, ?)" #[42 3.14 "hello"]
            rows := raw-db-query conn "SELECT * FROM t" #[]
            row := head rows
            println (type-of row.i)
            println (type-of row.r)
            println (type-of row.s)
            println row.i
            raw-db-close conn
            """);
        assertEquals("Int\nFloat\nStr\n42", output);
    }

    @Test
    void queryWithNullValues() {
        var output = run("""
            conn := raw-db-open ":memory:"
            raw-db-exec conn "CREATE TABLE t (a TEXT, b TEXT)" #[]
            raw-db-exec conn "INSERT INTO t VALUES (?, ?)" #["x" ()]
            rows := raw-db-query conn "SELECT * FROM t" #[]
            row := head rows
            println row.a
            println (type-of row.b)
            raw-db-close conn
            """);
        assertEquals("x\nUnit", output);
    }

    @Test
    void execReturnsAffectedCount() {
        var output = run("""
            conn := raw-db-open ":memory:"
            raw-db-exec conn "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)" #[]
            raw-db-exec conn "INSERT INTO t (v) VALUES (?)" #["a"]
            raw-db-exec conn "INSERT INTO t (v) VALUES (?)" #["b"]
            raw-db-exec conn "INSERT INTO t (v) VALUES (?)" #["c"]
            n := raw-db-exec conn "DELETE FROM t WHERE id > ?" #[1]
            println n
            raw-db-close conn
            """);
        assertEquals("2", output);
    }

    // ═══════════════════════════════════════════════════════════════════
    // raw-db-transaction
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void transactionCommits() {
        var output = run("""
            conn := raw-db-open ":memory:"
            raw-db-exec conn "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)" #[]
            raw-db-transaction conn (->
              raw-db-exec conn "INSERT INTO t (v) VALUES (?)" #["inside-tx"]
            )
            rows := raw-db-query conn "SELECT * FROM t" #[]
            println (length rows)
            println (head rows).v
            raw-db-close conn
            """);
        assertEquals("1\ninside-tx", output);
    }

    @Test
    void transactionRollsBackOnError() {
        // Transaction rollback: insert inside transaction, error causes rollback
        // Uses helper fn because lambda (-> ...) can't have multiple statements
        var output = run("""
            fn insert-and-fail
              => conn
              raw-db-exec conn "INSERT INTO t (v) VALUES (?)" #["should-rollback"]
              error "boom"

            conn := raw-db-open ":memory:"
            raw-db-exec conn "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)" #[]
            try (-> raw-db-transaction conn (-> insert-and-fail conn))
            rows := raw-db-query conn "SELECT * FROM t" #[]
            println (length rows)
            raw-db-close conn
            """);
        assertEquals("0", output);
    }

    // ═══════════════════════════════════════════════════════════════════
    // std.db module
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void stdDbModuleWorks() {
        var output = run("""
            use std.db :open
            conn := db-open ":memory:"
            with default-db
              db-exec conn "CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)" #[]
              db-exec conn "INSERT INTO items (name) VALUES (?)" #["widget"]
              items := db-query conn "SELECT * FROM items" #[]
              println (head items).name
            db-close conn
            """);
        assertEquals("widget", output);
    }

    @Test
    void stdDbTransaction() {
        var output = run("""
            use std.db :open

            fn insert-two ::: Db
              => conn
              db-exec conn "INSERT INTO t (v) VALUES (?)" #["a"]
              db-exec conn "INSERT INTO t (v) VALUES (?)" #["b"]

            conn := db-open ":memory:"
            with default-db
              db-exec conn "CREATE TABLE t (v TEXT)" #[]
              db-transaction conn (-> insert-two conn)
              rows := db-query conn "SELECT * FROM t ORDER BY v" #[]
              println (length rows)
            db-close conn
            """);
        assertEquals("2", output);
    }

    @Test
    void mockDbHandler() {
        var output = run("""
            use std.db :open

            handler mock-db :: Db
              db-query conn sql params =>
                resume #[{name= "mock-alice" age= 99}]
              db-exec conn sql params =>
                resume 0
              db-transaction conn thunk =>
                resume (thunk ())

            fn fetch-users ::: Db
              (conn -> db-query conn "SELECT * FROM users" #[])

            with mock-db
              users := fetch-users "fake-conn"
              println (head users).name
            """);
        assertEquals("mock-alice", output);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Error cases
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void queryOnClosedConnectionFails() {
        assertThrows(IrijRuntimeError.class, () -> eval("""
            conn := raw-db-open ":memory:"
            raw-db-close conn
            raw-db-query conn "SELECT 1" #[]
            """));
    }

    @Test
    void invalidConnectionArgFails() {
        assertThrows(IrijRuntimeError.class, () -> eval("""
            raw-db-query "not-a-conn" "SELECT 1" #[]
            """));
    }
}
