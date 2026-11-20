// Vendored from github.com/serezhka/java-airplay (MIT) — protocol + server modules,
// patched for Android (no java.util.HexFormat, no Stream.toList, hidden MAC fallback),
// and converted to Kotlin.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.slf4j:slf4j-api:2.0.16")
    implementation("com.googlecode.plist:dd-plist:1.28")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.whispersystems:curve25519-java:0.5.0")
    implementation("org.jmdns:jmdns:3.5.12")
    implementation("io.netty:netty-codec-http:4.1.115.Final")
    implementation("io.lindstrom:m3u8-parser:0.30")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.20")
}
