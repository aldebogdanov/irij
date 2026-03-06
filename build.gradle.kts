plugins {
    java
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

// Run Irij programs: ./gradlew run --args="examples/hello.irj"
tasks.register<JavaExec>("run") {
    dependsOn("compileJava")
    mainClass = "dev.irij.interpreter.IrijRunner"
    classpath = sourceSets["main"].runtimeClasspath
    // Pass --args from CLI
}
