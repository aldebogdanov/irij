package dev.irij.cli;

import dev.irij.compiler.IrijCompiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * `irij compile` subcommand: parse an Irij source file and emit a JVM class
 * (or runnable jar) via the experimental bytecode compiler.
 *
 * Usage:
 *   irij compile file.irj                 # emits file.class (class name = irij.Program)
 *   irij compile file.irj -o Out.class    # writes bytes to Out.class
 *   irij compile file.irj -o app.jar      # packages into runnable jar
 */
public final class CompileCommand {

    private static final String CLASS_NAME = "irij.Program";

    private CompileCommand() {}

    public static void run(String[] args) throws Exception {
        String input = null;
        String output = null;
        for (int i = 0; i < args.length; i++) {
            if ("-o".equals(args[i]) && i + 1 < args.length) {
                output = args[++i];
            } else if (input == null && !args[i].startsWith("-")) {
                input = args[i];
            } else {
                System.err.println("Unknown argument: " + args[i]);
                System.exit(1);
                return;
            }
        }
        if (input == null) {
            System.err.println("Usage: irij compile <file.irj> [-o out.class|out.jar]");
            System.exit(1);
            return;
        }

        Path src = Path.of(input);
        byte[] bytes;
        try {
            bytes = IrijCompiler.compileFile(src, CLASS_NAME);
        } catch (IrijCompiler.CompileException e) {
            System.err.println("Compile error: " + e.getMessage());
            System.exit(1);
            return;
        }

        String outPath = output != null ? output : defaultOutput(src);
        if (outPath.endsWith(".jar")) {
            writeJar(Path.of(outPath), bytes);
            System.out.println("Wrote " + outPath + " (runnable: java -jar " + outPath + ")");
        } else {
            Files.write(Path.of(outPath), bytes);
            System.out.println("Wrote " + outPath + " (class: " + CLASS_NAME + ")");
        }
    }

    private static String defaultOutput(Path src) {
        String name = src.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + ".class";
    }

    private static void writeJar(Path jar, byte[] classBytes) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, CLASS_NAME);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos, mf)) {
            Set<String> written = new HashSet<>();
            // User program
            jos.putNextEntry(new JarEntry(CLASS_NAME.replace('.', '/') + ".class"));
            jos.write(classBytes);
            jos.closeEntry();
            written.add(CLASS_NAME.replace('.', '/') + ".class");
            // Bundle the runtime classes from the irij jar itself so it's self-contained.
            bundleRuntimeClasses(jos, written);
        }
        Files.write(jar, baos.toByteArray());
    }

    /** Copy all dev/irij/** classes (except cli/repl/nrepl/mcp) from our own jar. */
    private static void bundleRuntimeClasses(JarOutputStream jos, Set<String> written) throws Exception {
        URL selfUrl = CompileCommand.class.getProtectionDomain().getCodeSource().getLocation();
        File selfFile = new File(selfUrl.toURI());
        if (!selfFile.isFile()) return; // running from classes dir (tests) — skip
        try (JarFile jf = new JarFile(selfFile)) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.endsWith(".class")) continue;
                if (!name.startsWith("dev/irij/")) continue;
                if (name.startsWith("dev/irij/cli/")
                        || name.startsWith("dev/irij/repl/")
                        || name.startsWith("dev/irij/nrepl/")
                        || name.startsWith("dev/irij/mcp/")) continue;
                if (written.contains(name)) continue;
                jos.putNextEntry(new JarEntry(name));
                try (var is = jf.getInputStream(e)) { is.transferTo(jos); }
                jos.closeEntry();
                written.add(name);
            }
        }
    }
}
