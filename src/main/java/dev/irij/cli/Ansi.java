package dev.irij.cli;

/**
 * Terminal colour for the CLI's own output ({@code irij test},
 * {@code irij quint doctor}). On only when stdout is a terminal, so
 * piped output and logs (CI, invar's gates) stay plain text:
 * {@code NO_COLOR} turns it off, {@code FORCE_COLOR} (or
 * {@code CLICOLOR_FORCE}) on — the usual conventions.
 */
final class Ansi {

    private final boolean on;

    Ansi(boolean on) { this.on = on; }

    /** Colour per the environment and whether stdout is a terminal. */
    static Ansi detect() {
        if (set("NO_COLOR")) return new Ansi(false);
        if (set("FORCE_COLOR") || set("CLICOLOR_FORCE")) return new Ansi(true);
        var console = System.console();
        return new Ansi(console != null && console.isTerminal());
    }

    private static boolean set(String name) {
        String v = System.getenv(name);
        return v != null && !v.isEmpty() && !v.equals("0");
    }

    boolean on() { return on; }

    private String wrap(String code, String s) {
        return on ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
    }

    String bold(String s)   { return wrap("1", s); }
    String dim(String s)    { return wrap("2", s); }
    String green(String s)  { return wrap("32", s); }
    String red(String s)    { return wrap("31", s); }
    String yellow(String s) { return wrap("33", s); }
    String cyan(String s)   { return wrap("36", s); }
    String boldGreen(String s)  { return wrap("1;32", s); }
    String boldRed(String s)    { return wrap("1;31", s); }
    String boldYellow(String s) { return wrap("1;33", s); }
    String boldCyan(String s)   { return wrap("1;36", s); }

    /** Visible width of {@code s}: escape codes don't take columns. */
    static int width(String s) {
        return s.replaceAll("\u001b\\[[0-9;]*m", "").codePointCount(0,
                s.replaceAll("\u001b\\[[0-9;]*m", "").length());
    }

    /** {@code s} padded with spaces to {@code cols} visible columns. */
    static String pad(String s, int cols) {
        int w = width(s);
        return w >= cols ? s : s + " ".repeat(cols - w);
    }
}
