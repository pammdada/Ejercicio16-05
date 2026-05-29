
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "com"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

ktor {
    fatJar {
        archiveFileName.set("app.jar")
    }
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(libs.logback.classic)


    implementation("ai.koog:koog-ktor:1.0.0-beta")

    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.serialization.kotlinx.json)

    implementation("org.eclipse.angus:angus-mail:2.0.3")

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}
