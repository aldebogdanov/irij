package dev.irij.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A module's private top-level names can't collide with any other's. */
class ModulePrivacyTest {

    @TempDir Path root;

    private String run(String main) throws Exception {
        Map<String, byte[]> classes = IrijCompiler.compileSourceMulti(main, "irij.Priv",
                root, CompileOptions.defaults().withSpecLint(false), List.of(), "main.irj");
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classes.get(name);
                if (b == null) throw new ClassNotFoundException(name);
                return defineClass(name, b, 0, b.length);
            }
        };
        PrintStream orig = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        try {
            Method m = loader.loadClass("irij.Priv").getMethod("main", String[].class);
            m.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(orig);
        }
        return buf.toString().trim();
    }

    private void module(String qname, String source) throws Exception {
        Path p = root.resolve(qname.replace('.', '/') + ".irj");
        Files.createDirectories(p.getParent());
        Files.writeString(p, source);
    }

    @Test void programNameDoesNotReplaceAModulesPrivateFn() throws Exception {
        module("lib.a", "mod lib.a\n\nfn helper :: Int Int\n  (x -> x + 1)\n\npub fn api :: Int Int\n  (x -> helper x)\n");
        assertEquals("2\n100", run("use lib.a :open\n\nfn helper :: Int Int\n  (x -> x * 100)\n\nprintln (api 1)\nprintln (helper 1)\n"));
    }

    @Test void twoModulesKeepTheirOwnSameNamedHelpers() throws Exception {
        module("lib.a", "mod lib.a\n\nfn helper :: Int Int\n  (x -> x + 1)\n\npub fn a-api :: Int Int\n  (x -> helper x)\n");
        module("lib.b", "mod lib.b\n\nfn helper :: Int Int\n  (x -> x + 2)\n\npub fn b-api :: Int Int\n  (x -> helper x)\n");
        assertEquals("11 12", run("use lib.a :open\nuse lib.b :open\nprintln ((to-str (a-api 10)) ++ \" \" ++ (to-str (b-api 10)))\n"));
    }

    @Test void localsShadowingAPrivateNameKeepTheirMeaning() throws Exception {
        // `helper` is also a parameter, a local and a lambda param here; the
        // consistent rename must leave each binding resolving as before.
        module("lib.c", """
                mod lib.c

                fn helper :: Int Int
                  (x -> x + 1)

                pub fn uses-param :: Int Int
                  (helper -> helper * 2)

                pub fn uses-local :: Int Int
                  => x
                  helper := x * 3
                  helper

                pub fn uses-both :: Int Int
                  (x -> (helper x) + ((helper -> helper * 10) x))
                """);
        assertEquals("8 9 23", run("use lib.c :open\nprintln ((to-str (uses-param 4)) ++ \" \" ++ (to-str (uses-local 3)) ++ \" \" ++ (to-str (uses-both 2)))\n"));
    }

    @Test void privateTopLevelBindingsArePrivateToo() throws Exception {
        module("lib.d", "mod lib.d\n\nlimit := 5\n\npub fn clamp :: Int Int\n  (x -> if (x > limit) limit else x)\n");
        assertEquals("5", run("use lib.d :open\n\nlimit := 1000\n\nprintln (clamp 99)\n"));
    }

    @Test void programNameDoesNotReplaceAModulesPubFnInsideTheModule() throws Exception {
        module("lib.p", "mod lib.p\n\npub fn shout :: Str Str\n  (s -> s ++ \"!\")\n\npub fn hello :: Str Str\n  (s -> shout (\"hi \" ++ s))\n");
        // The program's own `shout` is what the program sees; lib.p's
        // `hello` still calls lib.p's `shout`.
        assertEquals("hi bo!\nx?", run("use lib.p :open\n\nfn shout :: Str Str\n  (s -> s ++ \"?\")\n\nprintln (hello \"bo\")\nprintln (shout \"x\")\n"));
    }

    @Test void twoModulesExportingOneNameKeepTheirOwnInternally() throws Exception {
        module("lib.a", "mod lib.a\n\npub fn tag :: Str Str\n  (s -> \"a:\" ++ s)\n\npub fn a-api :: Str Str\n  (s -> tag s)\n");
        module("lib.b", "mod lib.b\n\npub fn tag :: Str Str\n  (s -> \"b:\" ++ s)\n\npub fn b-api :: Str Str\n  (s -> tag s)\n");
        assertEquals("a:x b:x", run("use lib.a :open\nuse lib.b :open\nprintln ((a-api \"x\") ++ \" \" ++ (b-api \"x\"))\n"));
    }

    @Test void aliasQualifiedCallsReachTheModuleDespiteAShadow() throws Exception {
        module("lib.q", "mod lib.q\n\npub fn shout :: Str Str\n  (s -> s ++ \"!\")\n");
        assertEquals("x! x?", run("use lib.q :as q\n\nfn shout :: Str Str\n  (s -> s ++ \"?\")\n\nprintln ((q.shout \"x\") ++ \" \" ++ (shout \"x\"))\n"));
    }

    @Test void pubBindingsAreTheModulesOwnToo() throws Exception {
        module("lib.c2", "mod lib.c2\n\npub limit := 5\n\npub fn clamp :: Int Int\n  (x -> if (x > limit) limit else x)\n");
        assertEquals("5 1000", run("use lib.c2 :open\n\nlimit := 1000\n\nprintln ((to-str (clamp 99)) ++ \" \" ++ (to-str limit))\n"));
    }

    @Test void specFailuresNameTheFnAsWritten() {
        assertDoesNotThrow(() -> module("lib.s", "mod lib.s\n\npub fn shout :: Str Str\n  (s -> s ++ \"!\")\n"));
        var e = assertThrows(Exception.class, () -> run("use lib.s :open\nprintln (shout 42)\n"));
        Throwable t = e;
        while (t.getCause() != null && !(t instanceof dev.irij.IrijRuntimeError)) t = t.getCause();
        assertTrue(t.getMessage().contains("of shout:"), t.getMessage());
    }

    /** The message of the compile error a program raises. */
    private String compileError(String main) {
        var e = assertThrows(IrijCompiler.CompileException.class, () -> run(main));
        return e.getMessage();
    }

    @Test void effectErrorsNameTheFnAsWrittenAndItsFile() throws Exception {
        module("lib.eff", "mod lib.eff\n\nfn helper :: Int Int\n  (x -> println x)\n\npub fn g :: Int Int\n  (x -> helper x)\n");
        String m = compileError("use lib.eff :open\nprintln (g 1)\n");
        assertTrue(m.startsWith("Effect 'Console' not declared in fn helper: 'println' requires") && m.contains(" at lib/eff.irj:4:"), m);
        module("lib.eff2", "mod lib.eff2\n\npub fn g :: Int Int\n  (x -> println x)\n");
        m = compileError("use lib.eff2 :open\nprintln (g 1)\n");
        assertTrue(m.contains("in fn g:") && m.contains(" at lib/eff2.irj:4:"), m);
    }

    @Test void rowVariableErrorsNameTheFnAsWrittenAndItsFile() throws Exception {
        module("lib.row", "mod lib.row\n\nuse std.collection :open\n\npub fn total :: Vec Int\n  (xs -> fold (acc x -> println x) 0 xs)\n");
        String m = compileError("use lib.row :open\nprintln (total #[1])\n");
        assertTrue(m.contains("in fn total\n  at lib/row.irj:6:") && m.contains("call 'fold'") && !m.contains("$"), m);
    }

    @Test void aliasQualifiedHandlersAreTheHandlers() throws Exception {
        // A nested `with` of a qualified handler failed state-machine
        // lowering: `m.tock-one` stayed a dot access, which the lowering
        // can't classify; it is the program-wide handler `tock-one`.
        module("lib.h", """
                mod lib.h

                pub effect Tick
                  tick :: () Int

                pub effect Tock
                  tock :: () Int

                pub handler tick-forty :: Tick
                  tick => resume 40

                pub handler tock-one :: Tock
                  tock => resume 1

                pub fn both :: () Int ::: Tick Tock
                  (_ -> (tick ()) + (tock ()))
                """);
        assertEquals("41", run("""
                use lib.h :as h

                fn run :: () Int
                  => _
                  with h.tick-forty
                    with h.tock-one
                      h.both ()

                println (run ())
                """));
        module("lib.only", "mod lib.only\n\npub effect Beep\n  beep :: () Int\n\npub handler beep-seven :: Beep\n  beep => resume 7\n");
        assertEquals("7", run("use lib.only :as o\n\nfn go :: () Int\n  => _\n  with o.beep-seven\n    with o.beep-seven\n      beep ()\n\nprintln (go ())\n"));
    }

    @Test void pubNamesStayReachable() throws Exception {
        module("lib.e", "mod lib.e\n\npub fn shared :: Int Int\n  (x -> x + 7)\n\nfn inner :: Int Int\n  (x -> shared x)\n\npub fn outer :: Int Int\n  (x -> inner x)\n");
        assertEquals("8 9", run("use lib.e :open\nprintln ((to-str (shared 1)) ++ \" \" ++ (to-str (outer 2)))\n"));
    }
}
