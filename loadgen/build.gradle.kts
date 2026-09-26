// loadgen: open-loop HTTP load generator. Plain JDK (java.net.http + virtual
// threads), no dependencies, so the fat jar is just the jar.

plugins {
    java
    application
}

dependencies {
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.parkerharrelson.ratelimiter.loadgen.LoadGen")
}

tasks.jar {
    archiveFileName.set("loadgen.jar")
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
}
