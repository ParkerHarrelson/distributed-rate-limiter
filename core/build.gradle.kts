// core: the rate limiter itself. Deliberately framework-free so that the
// algorithms can be read, tested and benchmarked without Spring in the way.

plugins {
    `java-library`
    alias(libs.plugins.jmh)
}

dependencies {
    api(platform(libs.spring.boot.bom))

    implementation(libs.lettuce)
    implementation(libs.snakeyaml)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.logback)
    testRuntimeOnly(libs.junit.platform.launcher)
}

jmh {
    // `./gradlew :core:jmh` benchmarks the in-memory algorithms.
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    threads.set(8)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
}
