package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The editor highlighters' keyword and builtin lists are hand-written;
 *  keep them equal to the lexer's keywords and to the names a file
 *  reaches without an import ({@link IntrinsicsEmitter#NAMES}). */
class EditorGrammarSyncTest {

    private static final Path EMACS = Path.of("editors/emacs/irij-mode.el");
    private static final Path TEXTMATE = Path.of("editors/textmate/irij.tmLanguage.json");

    /** Lowercase literal tokens of IrijLexer.g4: `FN : 'fn' ;`. */
    static Set<String> lexerKeywords() throws Exception {
        String g4 = Files.readString(Path.of("src/main/antlr/IrijLexer.g4"));
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^[A-Z_]+\\s*:\\s*'([a-z][a-z-]*)'").matcher(g4);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Builtins a highlighter should mark: intrinsics (minus internal
     *  raw-* hooks), the constants, and the handler-clause `resume`. */
    static Set<String> expectedBuiltins() {
        Set<String> out = new TreeSet<>();
        for (String n : IntrinsicsEmitter.NAMES) if (!n.startsWith("raw-")) out.add(n);
        out.addAll(Set.of("pi", "e", "resume"));
        return out;
    }

    /** Quoted strings inside `(defconst NAME '( ... )`. */
    static Set<String> elispList(String src, String name) {
        int start = src.indexOf("(defconst " + name);
        int open = src.indexOf("'(", start);
        int depth = 0, end = open + 1;
        for (; end < src.length(); end++) {
            char c = src.charAt(end);
            if (c == ';') { end = src.indexOf('\n', end); continue; }
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) break;
        }
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(src.substring(open, end));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Alternatives of the `(?:a|b|c)` group in a TextMate `match`. */
    static Set<String> tmAlternatives(String json, String marker) {
        int at = json.indexOf(marker);
        int open = json.indexOf("(?:", at) + 3;
        int close = json.indexOf(")", open);
        Set<String> out = new TreeSet<>();
        for (String alt : json.substring(open, close).split("\\|"))
            out.add(alt.replace("\\\\", ""));
        return out;
    }

    @Test void emacsKeywordsMatchLexer() throws Exception {
        Set<String> kw = elispList(Files.readString(EMACS), "irij-keywords");
        kw.add("model");   // soft keyword, matched only at a declaration start
        kw.add("cond");    // soft keywords, matched only where they open arms
        kw.add("case");
        assertEquals(lexerKeywords(), kw);
    }

    @Test void emacsBuiltinsMatchIntrinsics() throws Exception {
        assertEquals(expectedBuiltins(), elispList(Files.readString(EMACS), "irij-builtin-fns"));
    }

    @Test void textMateKeywordsMatchLexer() throws Exception {
        String json = Files.readString(TEXTMATE);
        Set<String> kw = new TreeSet<>();
        kw.addAll(tmAlternatives(json, "\"keyword.control.irij\""));
        kw.addAll(tmAlternatives(json, "\"keyword.other.declaration.irij\""));
        kw.add("model");   // `model-decl` rule
        kw.add("cond");    // `arms-head` rule
        kw.add("case");
        assertEquals(lexerKeywords(), kw);
    }

    @Test void textMateBuiltinsMatchIntrinsics() throws Exception {
        assertEquals(expectedBuiltins(),
                tmAlternatives(Files.readString(TEXTMATE), "\"support.function.builtin.irij\""));
    }
}
