import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.pwtoolkit"
// -PtsBackend=auto|native|tsserver|tsls: default TypeScript server of this build (users can change it in Settings)
val tsBackend = providers.gradleProperty("tsBackend").getOrElse("auto")
version = providers.gradleProperty("pluginVersion").get() + if (tsBackend == "auto") "" else "-$tsBackend"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// GigaIDE plugins for tests, copied without their own kotlin-stdlib (it clashes with the platform one in the test JVM)
fun gigaTestPlugin(name: String): String {
    val target = layout.projectDirectory.dir(".intellijPlatform/giga-test-plugins/$name/lib").asFile
    if (!target.isDirectory) {
        copy {
            from("${providers.gradleProperty("gigaIdePath").get()}/Contents/plugins/$name/lib")
            exclude("kotlin-*", "annotations-*")
            into(target)
        }
    }
    return target.parentFile.path
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("gigaIdePath"))
        testFramework(TestFrameworkType.Platform)
        // GigaIDE's JS/TS support, so tests see the same PSI and highlighting as the real IDE
        localPlugin(gigaTestPlugin("gigaide-pro"))
        localPlugin(gigaTestPlugin("javascript"))
        bundledPlugins("com.intellij.java", "org.intellij.plugins.markdown")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        changeNotes = "TypeScript errors in the editor with quick fixes (Alt+Enter: Add import), auto-import in completion, Playwright API for untyped receivers. See CHANGELOG.md."
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    inputs.property("tsBackend", tsBackend)
    filesMatching("pwtoolkit/build.properties") { expand("tsBackend" to tsBackend) }
}

tasks.test {
    // integration tests use the sample project in samples/playwright-offline (run `npm install` there first)
    systemProperty("pwtoolkit.sample", file("samples/playwright-offline").absolutePath)
    // TypeScript 5 for the Node.js servers (tsserver.js, typescript-language-server): `npm install` in samples/typescript5
    systemProperty("pwtoolkit.ts5", file("samples/typescript5/node_modules/typescript/lib/tsserver.js").absolutePath)
    providers.systemProperty("pwtoolkit.report.file").orNull?.let { systemProperty("pwtoolkit.report.file", it) }
    providers.systemProperty("pwtoolkit.docs.dir").orNull?.let { systemProperty("pwtoolkit.docs.dir", it) }
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
}
