pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }

    // Register the version of plugins applied in build.gradle.kts here (without applying
    // them right away): unlike Stonecutter/loom-back-compat, Kotlin is not a "settings"
    // plugin and must be applied in the project's build script, not here.
    plugins {
        // Kotlin: used for the mod's code (not for mixins, which stay in Java)
        id("org.jetbrains.kotlin.jvm") version "2.4.10"

        // Shadow: bundles MoulConfig into our own jar under a relocated package
        // (see build.gradle.kts) so it doesn't collide with other mods that also
        // bundle it (SkyHanni, Firmament, ...).
        id("com.gradleup.shadow") version "9.6.0"
    }
}

plugins {
    // Stonecutter: lets us keep a single codebase for multiple Minecraft versions.
    // Check the latest version at https://stonecutter.kikugie.dev/blog/changes/0.9
    id("dev.kikugie.stonecutter") version "0.9.7"

    // Compatibility between pre/post 26.1 Loom APIs (https://codeberg.org/KikuGie/loom-back-compat)
    id("dev.kikugie.loom-back-compat") version "0.4.2"

    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // Currently supported version. To add future versions,
        // add the string here and a new table in stonecutter.properties.toml
        // (see PROJECT_MAP.md -> "Adding a new Minecraft version").
        version("26.1.2", "26.1.2")
        vcsVersion = "26.1.2"
    }
}

rootProject.name = "SkyQuant"
