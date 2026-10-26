package dev.irij.runtime;

import dev.irij.IrijRuntimeError;
import dev.irij.runtime.Values.IrijMap;
import dev.irij.runtime.Values.IrijVector;
import dev.irij.runtime.Values.Tagged;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * Capability provider for the {@code Db} effect of {@code std.jdbc}:
 * any database with a JDBC driver.
 *
 * <p>Bound from Irij with:
 * <pre>
 *   cap db-jdbc :: Db = "dev.irij.runtime.JdbcCapability"
 * </pre>
 *
 * <p>All methods are {@code public static}; the cap-dispatch emit
 * in {@link dev.irij.compiler.ClassEmitter} rewrites
 * {@code db-jdbc.open path} (inside a {@code Db}-handler clause)
 * into the JavaRef-equivalent call, which lands here.
 *
 * <p>This class replaces the former {@code raw-db-*} builtin
 * surface — those names are no longer registered in
 * {@code Builtins} / {@code EffectRowChecker.BUILTIN_EFFECTS} /
 * {@code ClassEmitter}. The only access path to JDBC is through
 * the {@code std.jdbc} effect ops, which route through this provider.
 *
 * <p>Irij bundles no driver. A program lists one in irij.toml
 * {@code [jvm]} ({@code "org.xerial:sqlite-jdbc"}), and {@link #open}
 * finds it with {@link ServiceLoader} in the program's Java libraries
 * ({@link DepsClassLoader#current()}). {@link DriverManager} can't be
 * used for those: it only hands a driver to a caller whose class loader
 * can see the driver's class, and this class is the runtime's, below
 * the deps loader.
 */
public final class JdbcCapability {

    private JdbcCapability() {}

    /** Drivers people reach for, by JDBC subprotocol: what to add to
     *  [jvm] when none is found. */
    private static final Map<String, String> KNOWN_DRIVERS = Map.of(
            "sqlite", "\"org.xerial:sqlite-jdbc\" = \"3.53.4.0\"",
            "postgresql", "\"org.postgresql:postgresql\" = \"<version>\"",
            "mysql", "\"com.mysql:mysql-connector-j\" = \"<version>\"",
            "mariadb", "\"org.mariadb.jdbc:mariadb-java-client\" = \"<version>\"",
            "h2", "\"com.h2database:h2\" = \"<version>\"");

    /** Open a connection to the JDBC URL {@code url}; returns a
     *  Tagged("DbConn", [conn]) value. SQLite files are switched to WAL. */
    public static Object open(Object urlArg) {
        String url = asStr(urlArg, "db-open");
        if (!url.startsWith("jdbc:")) {
            throw new IrijRuntimeError("db-open: expected a JDBC URL, got \"" + url + "\". "
                    + "For a SQLite file write \"jdbc:sqlite:" + url + "\" (std.db took a bare path; "
                    + "std.jdbc takes the URL any driver understands)");
        }
        try {
            Connection conn = connect(url);
            if (url.startsWith("jdbc:sqlite:")) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                }
            }
            return new Tagged("DbConn", List.of(conn), null, null);
        } catch (SQLException e) {
            throw new IrijRuntimeError("db-open: " + e.getMessage());
        }
    }

    /** The first driver that accepts {@code url}: the program's [jvm]
     *  libraries first, then whatever the JDK registered. */
    private static Connection connect(String url) throws SQLException {
        var drivers = ServiceLoader.load(Driver.class, DepsClassLoader.current()).iterator();
        while (true) {
            Driver d;
            try {
                if (!drivers.hasNext()) break;
                d = drivers.next();
            } catch (java.util.ServiceConfigurationError broken) {
                continue;                                    // a provider that can't load
            }
            if (d.acceptsURL(url)) {
                Connection c = d.connect(url, new Properties());
                if (c != null) return c;
            }
        }
        try {
            return DriverManager.getConnection(url);
        } catch (SQLException e) {
            if (!"08001".equals(e.getSQLState())) throw e;   // "No suitable driver"
        }
        String sub = url.substring("jdbc:".length());
        int colon = sub.indexOf(':');
        sub = colon < 0 ? sub : sub.substring(0, colon);
        String hint = KNOWN_DRIVERS.get(sub);
        throw new SQLException("no JDBC driver for " + url.substring(0, Math.min(url.length(), 5 + sub.length() + 1))
                + "… — Irij bundles none; list one in irij.toml [jvm]"
                + (hint == null ? " (its Maven coordinate)" : ":\n  [jvm]\n  " + hint));
    }

    public static Object close(Object connArg) {
        Connection conn = extractConnection(connArg, "db-jdbc.close");
        try { conn.close(); }
        catch (SQLException e) {
            throw new IrijRuntimeError("db-jdbc.close: " + e.getMessage());
        }
        return Values.UNIT;
    }

    /** {@code query conn sql params} → vector of row-maps. */
    public static Object query(Object connArg, Object sqlArg, Object paramsArg) {
        Connection conn = extractConnection(connArg, "db-jdbc.query");
        String sql = asStr(sqlArg, "db-jdbc.query");
        List<Object> params = extractParams(paramsArg, "db-jdbc.query");
        try {
            synchronized (conn) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    bindParams(ps, params);
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int cols = meta.getColumnCount();
                        String[] labels = new String[cols + 1];
                        int[] types = new int[cols + 1];
                        for (int i = 1; i <= cols; i++) {
                            labels[i] = meta.getColumnLabel(i);
                            types[i] = meta.getColumnType(i);
                        }
                        List<Object> rows = new ArrayList<>();
                        while (rs.next()) {
                            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= cols; i++) {
                                row.put(labels[i], sqlToIrij(rs, i, types[i]));
                            }
                            rows.add(new IrijMap(row));
                        }
                        return new IrijVector(rows);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IrijRuntimeError("db-jdbc.query: " + e.getMessage());
        }
    }

    /** {@code exec conn sql params} → affected-row count (Long). */
    public static Object exec(Object connArg, Object sqlArg, Object paramsArg) {
        Connection conn = extractConnection(connArg, "db-jdbc.exec");
        String sql = asStr(sqlArg, "db-jdbc.exec");
        List<Object> params = extractParams(paramsArg, "db-jdbc.exec");
        try {
            synchronized (conn) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    bindParams(ps, params);
                    return (long) ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IrijRuntimeError("db-jdbc.exec: " + e.getMessage());
        }
    }

    /** {@code transaction conn thunk} — runs thunk under setAutoCommit(false),
     *  commits on success, rolls back on throw. */
    public static Object transaction(Object connArg, Object thunk) {
        Connection conn = extractConnection(connArg, "db-jdbc.transaction");
        try {
            synchronized (conn) {
                boolean savedAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    Object result = dev.irij.compiler.RuntimeSupport
                            .callAny(thunk, new Object[0]);
                    conn.commit();
                    return result;
                } catch (Throwable t) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    if (t instanceof IrijRuntimeError ire) throw ire;
                    if (t instanceof RuntimeException re) throw re;
                    throw new IrijRuntimeError(
                            "db-jdbc.transaction: " + t.getMessage());
                } finally {
                    try { conn.setAutoCommit(savedAutoCommit); }
                    catch (SQLException ignored) {}
                }
            }
        } catch (SQLException e) {
            throw new IrijRuntimeError("db-jdbc.transaction: " + e.getMessage());
        }
    }

    // ── Helpers (private to this provider) ──────────────────────────────

    private static String asStr(Object v, String op) {
        if (v instanceof String s) return s;
        throw new IrijRuntimeError(op + ": expected Str, got " + v);
    }

    private static Connection extractConnection(Object value, String op) {
        if (value instanceof Tagged t
                && "DbConn".equals(t.tag())
                && !t.fields().isEmpty()
                && t.fields().get(0) instanceof Connection c) {
            return c;
        }
        throw new IrijRuntimeError(
                op + ": first argument must be a database connection (from db-open)");
    }

    private static List<Object> extractParams(Object value, String op) {
        if (value instanceof IrijVector v) return v.elements();
        throw new IrijRuntimeError(op + ": params must be a vector #[...]");
    }

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            int idx = i + 1;
            if (p == null || p == Values.UNIT) ps.setNull(idx, Types.NULL);
            else if (p instanceof Boolean b) ps.setBoolean(idx, b);
            else if (p instanceof Long l) ps.setLong(idx, l);
            else if (p instanceof java.math.BigInteger b) ps.setBigDecimal(idx, new java.math.BigDecimal(b));
            else if (p instanceof Values.Rational r) ps.setDouble(idx, r.toDouble());
            else if (p instanceof Integer in) ps.setInt(idx, in);
            else if (p instanceof Double d) ps.setDouble(idx, d);
            else if (p instanceof Float f) ps.setFloat(idx, f);
            else if (p instanceof String s) ps.setString(idx, s);
            else ps.setString(idx, p.toString());
        }
    }

    private static Object sqlToIrij(ResultSet rs, int i, int sqlType) throws SQLException {
        switch (sqlType) {
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.SMALLINT:
            case Types.TINYINT:
                long lv = rs.getLong(i);
                return rs.wasNull() ? Values.UNIT : (Object) lv;
            case Types.REAL:
            case Types.FLOAT:
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.DECIMAL:
                double dv = rs.getDouble(i);
                return rs.wasNull() ? Values.UNIT : (Object) dv;
            case Types.BOOLEAN:
            case Types.BIT:
                boolean bv = rs.getBoolean(i);
                return rs.wasNull() ? Values.UNIT : (Object) bv;
            case Types.NULL:
                return Values.UNIT;
            default:
                String sv = rs.getString(i);
                return rs.wasNull() ? Values.UNIT : (Object) sv;
        }
    }
}
