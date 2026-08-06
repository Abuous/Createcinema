package com.yfy.createcinema.client;

import java.util.Locale;

/** Central OS/architecture detection shared by native loading and browser backend selection. */
public final class PlatformInfo {
    enum Os {
        WINDOWS,
        LINUX,
        MACOS,
        ANDROID
    }

    private static final Os OS = detectOs();
    private static final String ARCH = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

    private PlatformInfo() {
    }

    static Os os() {
        return OS;
    }

    public static boolean isAndroid() {
        return OS == Os.ANDROID;
    }

    static boolean isWindows() {
        return OS == Os.WINDOWS;
    }

    static boolean isLinux() {
        return OS == Os.LINUX;
    }

    static boolean isMacos() {
        return OS == Os.MACOS;
    }

    static boolean isArm64() {
        return ARCH.contains("aarch64") || ARCH.contains("arm64");
    }

    static boolean isX86_64() {
        return ARCH.contains("amd64") || ARCH.contains("x86_64") && !ARCH.contains("arm");
    }

    static String arch() {
        return ARCH;
    }

    /** JavaCPP-style platform classifier for the current runtime (used at build time too). */
    static String javacppPlatform() {
        return switch (OS) {
            case WINDOWS -> isArm64() ? "windows-arm64" : "windows-x86_64";
            case MACOS -> isArm64() ? "macosx-arm64" : "macosx-x86_64";
            case LINUX -> isArm64() ? "linux-arm64" : "linux-x86_64";
            case ANDROID -> isArm64() ? "android-arm64" : "android-arm";
        };
    }

    /**
     * FCL and Pojav run a bionic-linked Linux JVM, which JavaCPP otherwise mistakes for Linux.
     * Select the Android NDK natives before JavaCPP loads its first platform-dependent class.
     */
    public static void ensureJavacppPlatform() {
        if (OS == Os.ANDROID) {
            System.setProperty("org.bytedeco.javacpp.platform", javacppPlatform());
            System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");
        }
    }

    static boolean hasBundledFfmpeg() {
        return switch (OS) {
            case WINDOWS -> !isArm64();
            case ANDROID -> isArm64();
            case LINUX, MACOS -> true;
        };
    }

    static String ffmpegSupportMessage() {
        if (OS == Os.WINDOWS && isArm64()) {
            return "Embedded FFmpeg has no upstream Windows ARM64 build; use an x86_64 Java runtime under Windows ARM emulation or provide custom JavaCPP natives";
        }
        if (OS == Os.ANDROID && !isArm64()) {
            return "Embedded FFmpeg has no upstream Android armv7 build; provide custom JavaCPP natives";
        }
        return "Embedded FFmpeg is unavailable on " + displayName();
    }

    /** Human-readable name used for logs and config text. */
    static String displayName() {
        return switch (OS) {
            case WINDOWS -> "Windows " + (isArm64() ? "ARM64" : hostHeader());
            case LINUX -> "Linux " + hostHeader();
            case MACOS -> "macOS " + hostHeader();
            case ANDROID -> "Android " + (isArm64() ? "ARM64" : "ARM");
        };
    }

    private static String hostHeader() {
        if (isArm64()) return "ARM64";
        if (isX86_64()) return "x86_64";
        return ARCH;
    }

    private static Os detectOs() {
        try {
            Class.forName("android.os.Build");
            return Os.ANDROID;
        } catch (Throwable ignored) {
            // Not running on an Android runtime.
        }
        if (hasEnvironmentVariable("POJAV_LAUNCHER") || hasEnvironmentVariable("FCL_VERSION_CODE")) {
            return Os.ANDROID;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return Os.WINDOWS;
        if (os.contains("mac")) return Os.MACOS;
        return Os.LINUX;
    }

    private static boolean hasEnvironmentVariable(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }
}
