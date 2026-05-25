package dev.irij.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Text-position helpers used by the LSP server. Pure functions; no
 * I/O. Kept separate from {@link IrijLspServer} so the helpers can
 * be unit-tested without spinning up a server.
 */
final class LspText {

    private LspText() {}

    /** Resolve an LSP {@link Position} (zero-based line + UTF-16
     *  column) to a byte offset into the source string. Returns
     *  source-length on out-of-range. */
    static int offsetOf(String source, Position pos) {
        int line = pos.getLine();
        int col = pos.getCharacter();
        int lineStart = 0;
        for (int i = 0; i < line && lineStart < source.length(); i++) {
            int nl = source.indexOf('\n', lineStart);
            if (nl < 0) return source.length();
            lineStart = nl + 1;
        }
        return Math.min(source.length(), lineStart + col);
    }

    /** Extract the Irij identifier surrounding the given position.
     *  Identifiers may include `-` (Irij allows kebab-case names),
     *  `?` and `!` as trailing predicates, and underscores. Returns
     *  empty string if the cursor isn't on a word character. */
    static String wordAt(String source, Position pos) {
        int off = offsetOf(source, pos);
        if (off >= source.length()) off = source.length() - 1;
        if (off < 0) return "";
        int start = off;
        while (start > 0 && isWord(source.charAt(start - 1))) start--;
        int end = off;
        while (end < source.length() && isWord(source.charAt(end))) end++;
        if (start == end) return "";
        return source.substring(start, end);
    }

    /** Convert a 1-based (line, col) source location — the shape used
     *  throughout Irij's own {@code SourceLoc} — into a zero-width
     *  LSP Range. */
    static Range zeroWidthAt(int oneBasedLine, int oneBasedCol) {
        int line = Math.max(0, oneBasedLine - 1);
        int col = Math.max(0, oneBasedCol - 1);
        Position p = new Position(line, col);
        return new Range(p, p);
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_'
                || c == '?' || c == '!';
    }
}
