// server: one API replica. Spring Boot wires the limiter from core into an
// HTTP filter and exposes Prometheus metrics.

plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":core"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.prometheus)
    implementation(libs.lettuce)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

springBoot {
    mainClass.set("dev.parkerharrelson.ratelimiter.server.ServerApplication")
}

tasks.bootJar {
    archiveFileName.set("server.jar")
}
