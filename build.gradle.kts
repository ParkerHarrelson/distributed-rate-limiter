// Root build: shared conventions for every module. Module-specific
// dependencies live in each module's own build.gradle.kts.

plugins {
    base
}

allprojects {
    group = "dev.parkerharrelson.ratelimiter"
    version = "0.1.0"
    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-parameters"))
            options.encoding = "UTF-8"
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStandardStreams = false
            }
            // Redis integration tests use Testcontainers; skip them with -PskipIntegration
            if (project.hasProperty("skipIntegration")) {
                systemProperty("ratelimiter.skipIntegration", "true")
            }
        }
    }
}
