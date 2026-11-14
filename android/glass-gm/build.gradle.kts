plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // The cross-check test reads the reference vectors from the local SDK clone; it is skipped when the clone is absent.
    systemProperty("glassSdkDir", projectDir.resolve("../../third_party/plugin-open-platform").absolutePath)
}
