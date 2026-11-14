# Toolchain for crates whose build script runs cmake without driving the NDK itself
# (audiopus_sys). Selected per target through .cargo/config.toml; the NDK comes
# from ANDROID_NDK_HOME, as for cargo-ndk.
set(ANDROID_ABI arm64-v8a)
set(ANDROID_PLATFORM android-31)
include("$ENV{ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")
