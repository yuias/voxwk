plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Written as a literal coordinate because the PoC build, which includes this module, has its own version catalogue.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(libs.junit)
    // Test-only LZ4 encoder for the decoder round trip.
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}

tasks.test {
    // The cross-check test reads the reference vectors from the local SDK clone; it is skipped when the clone is absent.
    systemProperty("glassSdkDir", projectDir.resolve("../../third_party/plugin-open-platform").absolutePath)
}
