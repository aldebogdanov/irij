package dev.irij;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Names in the main sources follow Java's convention: methods and
 *  fields in camelCase, UPPER_CASE only for `static final` constants.
 *  Read from the compiled classes, so private members count too. The
 *  ANTLR-generated lexer and parser are skipped: ANTLR names those. */
class SourceNamingTest {

    private static final String CAMEL = "[a-z][a-zA-Z0-9]*";

    @Test void methodsAndFieldsFollowJavaNaming() throws Exception {
        Path root = Path.of(IrijRuntimeError.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> bad = new ArrayList<>();
        try (var files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                String name = root.relativize(f).toString().replace('/', '.').replaceAll("\\.class$", "");
                if (name.matches("dev\\.irij\\.parser\\.Irij(Lexer|Parser|ParserBaseVisitor|ParserVisitor"
                        + "|ParserListener|ParserBaseListener)(\\$.*)?")) continue;
                Class<?> c = Class.forName(name, false, getClass().getClassLoader());
                for (var m : c.getDeclaredMethods()) {
                    if (m.isSynthetic() || m.getName().startsWith("lambda$")) continue;
                    if (!m.getName().matches(CAMEL)) bad.add(name + "." + m.getName() + "()");
                }
                for (var fld : c.getDeclaredFields()) {
                    if (fld.isSynthetic() || fld.isEnumConstant()) continue;
                    int mod = fld.getModifiers();
                    boolean constant = Modifier.isStatic(mod) && Modifier.isFinal(mod);
                    if (!fld.getName().matches(constant ? "[A-Z][A-Z0-9_]*|" + CAMEL : CAMEL)) {
                        bad.add(name + "." + fld.getName());
                    }
                }
            }
        }
        assertEquals(List.of(), bad, "rename to camelCase (UPPER_CASE only for static final constants)");
    }
}
