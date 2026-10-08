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

/** A file reaches exactly what it declares, imports, or has as a builtin. */
class ModuleScopeTest {

    @TempDir Path root;

    private static final String M = """
            mod lib.m

            pub spec Mode
              Calm
              Busy Int

            pub limit := 5

            fn helper :: Int Int
              (x -> x + 1)

            pub effect Tick
              tick :: () Int

            pub handler fixed-tick :: Tick
              tick => resume 42

            pub fn twice :: Int Int
              (x -> helper (x * 2) - 1)

            pub fn mode-of :: Int Mode
              (n -> if (n > limit) (Busy n) else Calm)
            """;

    private String run(String main) throws Exception {
        Map<String, byte[]> classes = IrijCompiler.compileSourceMulti(main, "irij.Scope",
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
            Method m = loader.loadClass("irij.Scope").getMethod("main", String[].class);
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

    private String compileError(String main) {
        return assertThrows(IrijCompiler.CompileException.class, () -> run(main)).getMessage();
    }

    @Test void anAliasImportsNothingBare() throws Exception {
        module("lib.m", M);
        assertEquals("6", run("use lib.m :as m\nprintln (m.twice 3)\n"));
        String e = compileError("use lib.m :as m\nprintln (twice 3)\n");
        assertTrue(e.startsWith("`twice` is not imported here: it is lib.m's; import it with `use lib.m {twice}`")
                && e.endsWith(" at main.irj:2:10"), e);
    }

    @Test void aSelectiveImportBringsOnlyItsNames() throws Exception {
        module("lib.m", M);
        assertEquals("6", run("use lib.m {twice}\nprintln (twice 3)\n"));
        assertTrue(compileError("use lib.m {twice}\nprintln limit\n").startsWith("`limit` is not imported here"));
    }

    @Test void importingWhatAModuleLacksIsAnError() throws Exception {
        module("lib.m", M);
        assertEquals("`use lib.m {nope}`: lib.m exports no `nope` at main.irj:1:1",
                compileError("use lib.m {nope}\nprintln 1\n"));
        assertTrue(compileError("use lib.m {helper}\nprintln 1\n").contains("(it is private to lib.m)"));
        assertEquals("`m.nope`: lib.m exports no `nope` at main.irj:2:10",
                compileError("use lib.m :as m\nprintln (m.nope 1)\n"));
    }

    @Test void aPrivateNameStaysPrivate() throws Exception {
        module("lib.m", M);
        assertEquals("`helper` is private to lib.m at main.irj:2:10",
                compileError("use lib.m :open\nprintln (helper 1)\n"));
    }

    @Test void aModulesExportDoesNotReplaceABuiltin() throws Exception {
        // `pub fn length` was a program-wide name: every file's `length`
        // became the module's, imported or not.
        module("lib.f", "mod lib.f\n\npub fn length :: _ Int\n  (x -> 99)\n");
        assertEquals("2 99", run("use lib.f :as f\nprintln ((to-str (length #[1 2])) ++ \" \" ++ (to-str (f.length #[1 2])))\n"));
        assertEquals("99", run("use lib.f {length}\nprintln (length #[1 2])\n"));
    }

    @Test void aLocalShadowsAnAlias() throws Exception {
        module("lib.m", M);
        assertEquals("9 6", run("""
                use lib.m :as m

                fn field :: Map Int
                  (m -> m.twice)

                println ((to-str (field {twice= 9})) ++ " " ++ (to-str (m.twice 3)))
                """));
    }

    @Test void specsVariantsAndEffectsAreImportedByName() throws Exception {
        module("lib.m", M);
        assertTrue(compileError("use lib.m :as m\nprintln Calm\n").startsWith(
                "`Calm` is not imported here: it is lib.m's (`Calm`, of spec `Mode`); import it with `use lib.m {Mode}`"));
        assertTrue(compileError("use lib.m :as m\nprintln m.Calm\n").startsWith(
                "`m.Calm`: specs, variants and effects are not reached through an alias"));
        assertEquals("9 0", run("""
                use lib.m :as m
                use lib.m {Mode}

                fn size :: Mode Int
                  => md
                  match md
                    Busy k => k
                    Calm => 0

                println ((to-str (size (m.mode-of 9))) ++ " " ++ (to-str (size Calm)))
                """));
        assertTrue(compileError("use lib.m {fixed-tick}\nfn g :: () Int ::: Tick\n  (_ -> 1)\nprintln 1\n")
                .startsWith("`Tick` is not imported here: it is lib.m's (effect `Tick`)"));
        assertTrue(compileError("use lib.m {Tick}\nfn run :: () Int\n  => _\n  with fixed-tick\n    tick ()\nprintln (run ())\n")
                .startsWith("`fixed-tick` is not imported here"));
        assertEquals("42", run("""
                use lib.m {Tick fixed-tick}

                fn g :: () Int ::: Tick
                  (_ -> tick ())

                fn run :: () Int
                  => _
                  with fixed-tick
                    g ()

                println (run ())
                """));
    }

    @Test void aNameTwoOpenImportsShareIsAmbiguous() throws Exception {
        module("lib.a", "mod lib.a\n\npub fn tag :: Str Str\n  (s -> \"a:\" ++ s)\n");
        module("lib.b", "mod lib.b\n\npub fn tag :: Str Str\n  (s -> \"b:\" ++ s)\n");
        assertEquals("`tag` is imported from both lib.a and lib.b; import one of them with `:as` to choose at main.irj:3:10",
                compileError("use lib.a :open\nuse lib.b :open\nprintln (tag \"x\")\n"));
    }

    @Test void aModuleSeesOnlyItsOwnImports() throws Exception {
        module("lib.m", M);
        module("lib.k", "mod lib.k\n\npub fn go :: Int Int\n  (x -> twice x)\n");
        String e = compileError("use lib.m :open\nuse lib.k :open\nprintln (go 1)\n");
        assertTrue(e.startsWith("`twice` is not imported here: it is lib.m's") && e.endsWith(" at lib/k.irj:4:9"), e);
        module("lib.p", "mod lib.p\n\npub fn go :: Int Int\n  (x -> mine x)\n");
        e = compileError("use lib.p :open\n\nfn mine :: Int Int\n  (x -> x)\n\nprintln (go 1)\n");
        assertTrue(e.startsWith("`mine` is not defined in lib.p: the program defines one"), e);
    }

    @Test void pubUseReexports() throws Exception {
        module("lib.m", M);
        module("lib.r", "mod lib.r\n\npub use lib.m {twice Mode}\n");
        assertEquals("6 Calm", run("use lib.r :open\nprintln ((to-str (twice 3)) ++ \" \" ++ (to-str Calm))\n"));
    }

    @Test void mutableBindingsAreReachedThroughTheAlias() throws Exception {
        module("lib.v", """
                mod lib.v

                pub hits :! 0

                pub fn bump :: () Int
                  => _
                  hits <- hits + 1
                  hits
                """);
        assertEquals("2 3", run("use lib.v :as v\n\nfn seen :: () Int\n  (_ -> v.hits)\n\nv.bump ()\nv.bump ()\nprintln ((to-str (seen ())) ++ \" \" ++ (to-str (v.bump ())))\n"));
    }
}
