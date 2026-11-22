plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

    // Test-only LZ4 encoder for the decoder round trip.
    testImplementation("org.apache.commons:commons-compress:1.28.0")
tasks.test {
    // The cross-check test reads the reference vectors from the local SDK clone; it is skipped when the clone is absent.
    systemProperty("glassSdkDir", projectDir.resolve("../../third_party/plugin-open-platform").absolutePath)
}
