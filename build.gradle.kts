plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

group = "dev.irij"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val antlr by configurations.creating

dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
    implementation("org.jline:jline:3.26.3")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
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

tasks.test {
    useJUnitPlatform()
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
        script.writeText("#!/bin/bash\nexec java -jar \"$home/.local/lib/irij.jar\" \"\$@\"\n")
        script.setExecutable(true)
        println("Installed: $binDir/irij")
    }
}
