plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

group = "dev.irij"

// ── Commit-count versioning (replikativ-style) ──────────────────────
// The engine version is MAJOR.MINOR.<git-commit-count>. Bump the 2-part
// base by hand for a MAJOR/MINOR release; the patch is the commit count,
// so every commit advances it and nobody hand-picks a patch. CI checks
// out with fetch-depth: 0 (a shallow clone would count as 1).
val baseVersion = "0.9"
fun gitCommitCount(): String =
    try {
        val p = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.matches(Regex("\\d+"))) out else "0"
    } catch (e: Exception) { "0" }
version = "$baseVersion.${gitCommitCount()}"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

val antlr by configurations.creating

dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
    implementation("org.jline:jline:3.26.3")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.moandjiezana.toml:toml4j:0.7.2")
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-util:9.7")
    implementation("org.ow2.asm:asm-analysis:9.7")
    // LSP4J — Eclipse JSON-RPC + LSP message classes. Used by
    // `irij lsp` to drive the standard Language Server Protocol over
    // stdio. Pulls in only the protocol layer (no SWT, no Eclipse
    // framework).
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.23.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("uk.org.webcompere:system-stubs-jupiter:2.1.8")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val generateGrammarSource by tasks.registering(JavaExec::class) {
    val antlrDir = file("src/main/antlr")
    val outputDir = layout.buildDirectory.dir("generated-src/antlr/main/dev/irij/parser")

    inputs.dir(antlrDir)
    outputs.dir(outputDir)

    mainClass = "org.antlr.v4.Tool"
    classpath = antlr
    args = listOf(
        "-visitor",
        "-long-messages",
        "-package", "dev.irij.parser",
        "-o", outputDir.get().asFile.absolutePath,
        "-lib", antlrDir.absolutePath,
    ) + fileTree(antlrDir).matching { include("*.g4") }.files.map { it.absolutePath }

    doFirst {
        outputDir.get().asFile.mkdirs()
    }
}

sourceSets {
    main {
        java {
            srcDir(layout.buildDirectory.dir("generated-src/antlr/main"))
        }
    }
}

tasks.compileJava {
    dependsOn(generateGrammarSource)
}

tasks.processResources {
    // Without this, Gradle caches the expanded irij-version.properties on
    // the template file's content (unchanged) and the embedded version goes
    // stale — fatal here since `version` changes on every commit.
    inputs.property("version", project.version)
    filesMatching("irij-version.properties") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()

    inputs.files("editors/emacs", "editors/textmate")   // read by EditorGrammarSyncTest

    // Test output. `./gradlew test` prints one line per test, grouped
    // under its class; `./gradlew test -Pverbose` prints a block per
    // test (start, the test's stdout/stderr, result, full stack trace on
    // failure) with a blank line between blocks. Gradle's own event
    // logger is off: it puts a blank line before every event. Lines go
    // through the task logger, so `-q` keeps only failures and the summary.
    val verbose = project.hasProperty("verbose")
    val log = logger
    fun line(s: String) = log.lifecycle(s)

    testLogging {
        events = emptySet()      // no built-in per-event lines,
        showExceptions = false   // failures included
        showStandardStreams = false
    }

    // `ReplInputTest`; a nested class reads `Outer > Inner`
    fun className(d: TestDescriptor) =
        d.className?.substringAfterLast('.')?.replace("$", " > ") ?: ""

    addTestOutputListener { _, out ->
        if (verbose) out.message.trimEnd('\n').lines().forEach { line("      $it") }
    }

    addTestListener(object : TestListener {
        private var lastClass: String? = null

        override fun beforeSuite(s: TestDescriptor) {}

        override fun beforeTest(d: TestDescriptor) {
            if (verbose) line("${className(d)} > ${d.displayName}  STARTED")
        }

        override fun afterTest(d: TestDescriptor, r: TestResult) {
            val mark = when (r.resultType) {
                TestResult.ResultType.SUCCESS -> "✓"
                TestResult.ResultType.FAILURE -> "✗"
                else -> "○"
            }
            val ms = r.endTime - r.startTime
            val failed = r.resultType == TestResult.ResultType.FAILURE
            // Under -q only failures print, with the class in the line
            val out: (String) -> Unit = if (failed) log::quiet else ::line
            if (verbose) {
                out("${className(d)} > ${d.displayName}  $mark ${r.resultType} ($ms ms)")
            } else if (!log.isLifecycleEnabled) {
                if (failed) out("$mark ${className(d)} > ${d.displayName}")
            } else {
                val cls = className(d)
                if (cls != lastClass) { line(cls); lastClass = cls }
                out("  $mark ${d.displayName}")
            }
            for (e in r.exceptions) {
                // Exceptions cross from the test JVM as Gradle placeholders;
                // getExceptionClassName() has the original class.
                val name = runCatching {
                    e.javaClass.getMethod("getExceptionClassName").invoke(e) as String
                }.getOrDefault(e.javaClass.name).substringAfterLast('.')
                log.quiet("      $name: ${e.message?.lines()?.firstOrNull() ?: ""}")
                // Normal: the first frame in Irij code. Verbose: the trace up
                // to the last Irij frame, without the JUnit/JDK tail.
                val trace = e.stackTrace.toList()
                val lastOwn = trace.indexOfLast { it.className.startsWith("dev.irij") }
                val frames = when {
                    !verbose -> trace.filter { it.className.startsWith("dev.irij") }.take(1)
                    lastOwn >= 0 -> trace.take(lastOwn + 1)
                    else -> trace
                }
                frames.forEach {
                    log.quiet("          at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
                }
            }
            if (verbose) line("")
        }

        override fun afterSuite(s: TestDescriptor, r: TestResult) {
            if (s.parent == null) {
                println("\n┌─ Test Results ──────────────────────────────────────")
                println("│  ✓ Passed:  ${r.successfulTestCount}")
                if (r.failedTestCount > 0)
                    println("│  ✗ Failed:  ${r.failedTestCount}")
                if (r.skippedTestCount > 0)
                    println("│  ○ Skipped: ${r.skippedTestCount}")
                println("│  Total:     ${r.testCount}  (${r.endTime - r.startTime} ms)")
                println("└─────────────────────────────────────────────────────")
            }
        }
    })
}

// ── Shadow JAR (fat JAR with all dependencies) ──────────────────────

tasks.shadowJar {
    archiveBaseName.set("irij")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes("Main-Class" to "dev.irij.cli.IrijCli")
    }
    mergeServiceFiles()
}

// ── Run task (development) ──────────────────────────────────────────

tasks.register<JavaExec>("run") {
    dependsOn("compileJava")
    mainClass = "dev.irij.interpreter.IrijRunner"
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// ── Install task ────────────────────────────────────────────────────

tasks.register("install") {
    dependsOn("shadowJar")
    doLast {
        val home = System.getProperty("user.home")
        val libDir = file("$home/.local/lib").also { it.mkdirs() }
        val binDir = file("$home/.local/bin").also { it.mkdirs() }
        copy {
            from("build/libs/irij.jar")
            into(libDir)
        }
        val script = file("$binDir/irij")
        script.writeText("#!/bin/bash\nexec java --enable-native-access=ALL-UNNAMED -jar \"$home/.local/lib/irij.jar\" \"\$@\"\n")
        script.setExecutable(true)
        println("Installed: $binDir/irij")
    }
}

// ── Debug ────────────────────────────────────────────────────

tasks.withType<JavaCompile>().configureEach {
    options.isIncremental = true
    // options.debugOptions.debugLevel = "source,lines,vars"
    options.compilerArgs.add("-g") // Forces all debug information
    // No --enable-preview needed: unnamed patterns standard since JDK 22
}

tasks.test {
    // no extra jvmArgs needed
}
